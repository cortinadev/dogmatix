package com.cortinadev.dogmatix.data.service.convert

import android.os.ParcelFileDescriptor
import com.cortinadev.dogmatix.data.service.convert.chd.CdFrameSource
import com.cortinadev.dogmatix.data.service.convert.chd.CdSheetException
import com.cortinadev.dogmatix.data.service.convert.chd.CdSheets
import com.cortinadev.dogmatix.data.service.convert.chd.CdToc
import com.cortinadev.dogmatix.data.service.convert.chd.ChannelInput
import com.cortinadev.dogmatix.data.service.convert.chd.ChdCancelledException
import com.cortinadev.dogmatix.data.service.convert.chd.ChdCodec
import com.cortinadev.dogmatix.data.service.convert.chd.ChdFormatException
import com.cortinadev.dogmatix.data.service.convert.chd.ChdInput
import com.cortinadev.dogmatix.data.service.convert.chd.ChdReader
import com.cortinadev.dogmatix.data.service.convert.chd.ChdWriteSummary
import com.cortinadev.dogmatix.data.service.convert.chd.ChdWriter
import com.cortinadev.dogmatix.data.service.convert.chd.toHex
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel

/** What to turn into a CHD. */
sealed interface ChdSource {
    /**
     * A .cue or .gdi sheet and the files it names. Keys of [files] are matched against the names in
     * the sheet exactly, then ignoring case, then by base name ignoring case (so SAF display names
     * work). The caller owns and closes the inputs.
     */
    class Sheet(val sheetName: String, val sheetText: String, val files: Map<String, ChdInput>) : ChdSource

    /** A plain CD image (.iso / .bin of 2048, 2336 or 2352-byte sectors) as a single-track CD, like `chdman createcd -i x.iso`. */
    class CdImage(val name: String, val input: ChdInput) : ChdSource

    /** A DVD image (.iso, 2048-byte sectors), like `chdman createdvd`. */
    class Dvd(val input: ChdInput) : ChdSource
}

enum class ChdCompression(val cd: List<ChdCodec>, val dvd: List<ChdCodec>) {
    /** chdman's default codecs minus FLAC/Huffman: LZMA and deflate, the smaller per hunk. */
    BEST(listOf(ChdCodec.CD_LZMA, ChdCodec.CD_ZLIB), listOf(ChdCodec.LZMA, ChdCodec.ZLIB)),

    /** Deflate only: several times faster to write (and to read), a few percent larger. */
    FAST(listOf(ChdCodec.CD_ZLIB), listOf(ChdCodec.ZLIB)),
}

data class ChdOptions(
    val compression: ChdCompression = ChdCompression.BEST,
    /** Null = chdman's default: 8 frames (19 584 bytes) for CD, 4096 for DVD. */
    val hunkBytes: Int? = null,
    /** Compression threads; memory is roughly 1 MB per thread plus 4 hunks per thread in flight. */
    val threads: Int = defaultThreads(),
) {
    companion object {
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }
}

enum class ChdFailure {
    /** A file named in the sheet was not given. */
    MISSING_FILE,

    /** The sheet could not be parsed, or the image size does not fit the format. */
    BAD_INPUT,

    /** Something chdman accepts that this engine does not (WAVE tracks, FLAC hunks…). */
    UNSUPPORTED,
    READ_ERROR,
    WRITE_ERROR,
    NO_SPACE,

    /** The output cannot seek (a pipe from a remote provider): write to a local file and copy it. */
    OUTPUT_NOT_SEEKABLE,

    /** Verification found a damaged or inconsistent CHD. */
    CORRUPT,
}

sealed class ChdResult {
    data class Success(val summary: ChdWriteSummary) : ChdResult()
    data class Failure(val reason: ChdFailure, val message: String) : ChdResult()
    data object Cancelled : ChdResult()
}

/**
 * CHD (MAME "Compressed Hunks of Data" v5) creation and verification in pure Kotlin/JVM, equivalent to
 * `chdman createcd` / `chdman createdvd` with the cdlz,cdzl (lzma,zlib) codecs. Blocking: call it from
 * a background thread. Same input → same data SHA-1 and overall SHA-1 as chdman 0.276 (FLAC aside,
 * only the compressed bytes differ).
 *
 * ```
 * val result = ChdEngine.create(
 *     ChdSource.Sheet("Game.cue", cueText, mapOf("Game (Track 1).bin" to ChannelInput(ch1), …)),
 *     outputPfd,                          // opened "rw" (positional writes; truncated first)
 *     ChdOptions(),
 *     onProgress = { f -> … },            // 0..1, at most every 0.1 %
 *     isCancelled = { job.isCancelled },
 * )
 * if (result is ChdResult.Success) {
 *     val check = ChdEngine.verify(ChannelInput(readChannel), expectedRawSha1 = result.summary.rawSha1)
 *     // retire the original only if check is Success
 * }
 * ```
 */
object ChdEngine {

    fun create(
        source: ChdSource,
        output: FileChannel,
        options: ChdOptions = ChdOptions(),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ChdResult {
        try {
            try {
                output.position(0)
            } catch (e: IOException) {
                return ChdResult.Failure(ChdFailure.OUTPUT_NOT_SEEKABLE, e.message ?: "Output is not seekable")
            }
            val writer = when (source) {
                is ChdSource.Dvd -> dvdWriter(source.input, output, options, onProgress, isCancelled)
                is ChdSource.CdImage -> cdWriter(CdSheets.parseIso(source.name, source.input.size), { source.input },
                    output, options, onProgress, isCancelled)
                is ChdSource.Sheet -> {
                    val lookup = FileLookup(source.files)
                    val toc = CdSheets.parse(source.sheetName, source.sheetText) { lookup.find(it)?.size }
                    cdWriter(toc, { name -> lookup.find(name) ?: throw MissingFileException(name) },
                        output, options, onProgress, isCancelled)
                }
            }
            return ChdResult.Success(writer.write())
        } catch (e: ChdCancelledException) {
            return ChdResult.Cancelled
        } catch (e: MissingFileException) {
            return ChdResult.Failure(ChdFailure.MISSING_FILE, "Missing file: ${e.name}")
        } catch (e: CdSheetException) {
            return ChdResult.Failure(sheetFailure(e), e.message ?: "Bad sheet")
        } catch (e: IllegalArgumentException) {
            return ChdResult.Failure(ChdFailure.BAD_INPUT, e.message ?: "Bad input")
        } catch (e: ReadException) {
            return ChdResult.Failure(if (isNoSpace(e.cause)) ChdFailure.NO_SPACE else ChdFailure.READ_ERROR,
                e.cause?.message ?: "Read error")
        } catch (e: IOException) {
            return ChdResult.Failure(if (isNoSpace(e)) ChdFailure.NO_SPACE else ChdFailure.WRITE_ERROR, e.message ?: "Write error")
        }
    }

    /** [create] onto a SAF document opened in "rw" mode. The caller closes [output]. */
    fun create(
        source: ChdSource,
        output: ParcelFileDescriptor,
        options: ChdOptions = ChdOptions(),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ChdResult = create(source, FileOutputStream(output.fileDescriptor).channel, options, onProgress, isCancelled)

    /** A seekable SAF document opened "r" as input. The caller closes [pfd] (closing the channel is not needed). */
    fun input(pfd: ParcelFileDescriptor): ChdInput = ChannelInput(FileInputStream(pfd.fileDescriptor).channel)

    /**
     * Cheap check without compressing: parses the sheet, checks that every file it names is there
     * and that sizes fit the format. Null when [create] can start; otherwise the failure it would
     * report. [logicalBytes] gives the uncompressed size of the CHD.
     */
    fun inspect(source: ChdSource): ChdResult.Failure? = try {
        logicalBytes(source)
        null
    } catch (e: MissingFileException) {
        ChdResult.Failure(ChdFailure.MISSING_FILE, "Missing file: ${e.name}")
    } catch (e: CdSheetException) {
        ChdResult.Failure(sheetFailure(e), e.message ?: "Bad sheet")
    } catch (e: IllegalArgumentException) {
        ChdResult.Failure(ChdFailure.BAD_INPUT, e.message ?: "Bad input")
    }

    /** Uncompressed size of the CHD [source] makes (throws like [inspect] reports). */
    fun logicalBytes(source: ChdSource): Long = when (source) {
        is ChdSource.Dvd -> source.input.size.also {
            require(it > 0 && it % DVD_SECTOR == 0L) { "DVD image size $it is not a multiple of $DVD_SECTOR" }
        }
        is ChdSource.CdImage -> CdSheets.parseIso(source.name, source.input.size).logicalBytes
        is ChdSource.Sheet -> {
            val lookup = FileLookup(source.files)
            val toc = CdSheets.parse(source.sheetName, source.sheetText) { lookup.find(it)?.size }
            toc.files.forEach { lookup.find(it) ?: throw MissingFileException(it) }
            toc.logicalBytes
        }
    }

    private fun sheetFailure(e: CdSheetException): ChdFailure = when {
        e.message?.startsWith("Missing file") == true -> ChdFailure.MISSING_FILE
        e.message?.contains("not supported") == true -> ChdFailure.UNSUPPORTED
        else -> ChdFailure.BAD_INPUT
    }

    /**
     * Decompresses the whole CHD, checks every hunk's CRC-16, the data SHA-1, the overall SHA-1 and,
     * when given, that the data SHA-1 is [expectedRawSha1] (what [create] read from the source).
     */
    fun verify(
        chd: ChdInput,
        expectedRawSha1: String? = null,
        threads: Int = ChdOptions.defaultThreads(),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ChdResult = try {
        val reader = ChdReader(chd)
        val raw = reader.verify(threads, onProgress, isCancelled)
        if (expectedRawSha1 != null && !raw.toHex().equals(expectedRawSha1, ignoreCase = true)) {
            ChdResult.Failure(ChdFailure.CORRUPT, "Data SHA-1 ${raw.toHex()} is not the source's $expectedRawSha1")
        } else {
            ChdResult.Success(ChdWriteSummary(reader.logicalBytes, chd.size, reader.hunkBytes, reader.hunkCount,
                raw.toHex(), reader.sha1.toHex(), emptyMap()))
        }
    } catch (e: ChdCancelledException) {
        ChdResult.Cancelled
    } catch (e: ChdFormatException) {
        val unsupported = e.message?.startsWith("Unsupported") == true
        ChdResult.Failure(if (unsupported) ChdFailure.UNSUPPORTED else ChdFailure.CORRUPT, e.message ?: "Corrupt CHD")
    } catch (e: IOException) {
        ChdResult.Failure(ChdFailure.READ_ERROR, e.message ?: "Read error")
    }

    // ----------------------------------------------------------------------------------- internals

    private class MissingFileException(val name: String) : IOException("Missing file: $name")
    private class ReadException(cause: Throwable) : IOException(cause)

    private fun isNoSpace(e: Throwable?): Boolean {
        var t = e
        while (t != null) {
            val m = t.message ?: ""
            if (m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)) return true
            t = t.cause
        }
        return false
    }

    private fun dvdWriter(
        input: ChdInput,
        output: FileChannel,
        options: ChdOptions,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): ChdWriter {
        val size = input.size
        require(size > 0 && size % DVD_SECTOR == 0L) { "DVD image size $size is not a multiple of $DVD_SECTOR" }
        val hunk = options.hunkBytes ?: (2 * DVD_SECTOR)
        require(hunk % DVD_SECTOR == 0 && hunk in DVD_SECTOR..MAX_HUNK) { "Bad DVD hunk size $hunk" }
        return ChdWriter(output, size, hunk, DVD_SECTOR, options.compression.dvd,
            listOf(DVD_TAG to byteArrayOf(0)), options.threads,
            readHunk = { index, dst ->
                val pos = index.toLong() * hunk
                val n = minOf(hunk.toLong(), size - pos).toInt()
                try { input.readFully(pos, dst, 0, n) } catch (e: IOException) { throw ReadException(e) }
            },
            onProgress = onProgress, isCancelled = isCancelled)
    }

    private fun cdWriter(
        toc: CdToc,
        files: (String) -> ChdInput,
        output: FileChannel,
        options: ChdOptions,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
    ): ChdWriter {
        val hunk = options.hunkBytes ?: (CdToc.FRAMES_PER_HUNK * CdToc.FRAME_SIZE)
        require(hunk % CdToc.FRAME_SIZE == 0 && hunk in CdToc.FRAME_SIZE..MAX_HUNK) { "Bad CD hunk size $hunk" }
        // Resolve every file up front: a missing one fails before anything is written.
        toc.files.forEach { files(it) }
        val frames = CdFrameSource(toc, files)
        val framesPerHunk = hunk / CdToc.FRAME_SIZE
        val metadata = toc.metadata().map { (tag, text) -> tag to (text.toByteArray(Charsets.US_ASCII) + 0.toByte()) }
        return ChdWriter(output, toc.logicalBytes, hunk, CdToc.FRAME_SIZE, options.compression.cd, metadata,
            options.threads,
            readHunk = { index, dst ->
                try { frames.read(index.toLong() * framesPerHunk, framesPerHunk, dst) } catch (e: IOException) { throw ReadException(e) }
            },
            onProgress = onProgress, isCancelled = isCancelled)
    }

    private class FileLookup(private val files: Map<String, ChdInput>) {
        private fun base(name: String) = name.substringAfterLast('/').substringAfterLast('\\').lowercase()
        fun find(name: String): ChdInput? =
            files[name]
                ?: files.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
                ?: files.entries.firstOrNull { base(it.key) == base(name) }?.value
    }

    private const val DVD_SECTOR = 2048
    private const val MAX_HUNK = 1 shl 20
    private const val DVD_TAG = "DVD "
}
