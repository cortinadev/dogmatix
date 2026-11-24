// Port of MAME's CHD/CD-ROM code (src/lib/util: chd.cpp, chdcodec.cpp, cdrom.cpp, huffman.cpp, bitstream.h),
// BSD-3-Clause, copyright-holders Aaron Giles, R. Belmont. Binary redistribution must reproduce the BSD-3 notice.
package com.cortinadev.dogmatix.data.service.convert.chd

import org.tukaani.xz.ArrayCache
import org.tukaani.xz.BasicArrayCache
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAInputStream
import org.tukaani.xz.LZMAOutputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/*
 * The CHD v5 codecs Dogmatix writes and reads, compatible with MAME's chdcodec.cpp (BSD-3-Clause):
 *  - zlib: raw deflate (no zlib header), level 9.
 *  - lzma: raw LZMA1 without end marker; lc 3, lp 0, pb 2. The decoder never reads properties from
 *    the file, it derives them from the hunk size, so only these three values matter.
 *  - cdzl / cdlz: a CD hunk split into sector data (deflate / LZMA) and subcode (always deflate),
 *    with the sync header and ECC of verifiable sectors stripped (see [CdEcc]).
 * FLAC (cdfl/flac), Huffman and zstd are not written; the reader reports them as unsupported.
 */

enum class ChdCodec(val tag: String) {
    ZLIB("zlib"),
    LZMA("lzma"),
    CD_ZLIB("cdzl"),
    CD_LZMA("cdlz");

    val id: Int = tag.fold(0) { acc, c -> (acc shl 8) or (c.code and 0xFF) }

    val isCd: Boolean get() = this == CD_ZLIB || this == CD_LZMA

    companion object {
        fun tagToInt(tag: String): Int =
            tag.toByteArray(Charsets.US_ASCII).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }

        fun intToTag(id: Int): String =
            String(byteArrayOf((id ushr 24).toByte(), (id ushr 16).toByte(), (id ushr 8).toByte(), id.toByte()), Charsets.ISO_8859_1)

        fun byId(id: Int): ChdCodec? = entries.firstOrNull { it.id == id }
    }
}

/** Thrown by [LimitedOutput] when a codec's output reaches its budget: the codec has lost already. */
private object OverBudget : IOException("over budget") {
    override fun fillInStackTrace(): Throwable = this
}

/** Writes into a fixed slice of an array and gives up as soon as [limit] bytes would be reached. */
private class LimitedOutput(val buf: ByteArray, val start: Int, val limit: Int) : OutputStream() {
    var count = 0
    override fun write(b: Int) {
        if (count + 1 >= limit) throw OverBudget
        buf[start + count++] = b.toByte()
    }
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (count + len >= limit) throw OverBudget
        System.arraycopy(b, off, buf, start + count, len)
        count += len
    }
}

/** One codec's compressor; not thread-safe (one per worker thread). Returns -1 when it does not help. */
internal abstract class ChdCompressor {
    /** Compresses [len] bytes of [src] into [dest] at [destOff]; the result must be shorter than [limit]. */
    abstract fun compress(src: ByteArray, srcOff: Int, len: Int, dest: ByteArray, destOff: Int, limit: Int): Int
    open fun close() {}
}

internal class ZlibCompressor : ChdCompressor() {
    private val deflater = Deflater(Deflater.BEST_COMPRESSION, true)

    override fun compress(src: ByteArray, srcOff: Int, len: Int, dest: ByteArray, destOff: Int, limit: Int): Int {
        deflater.reset()
        deflater.setInput(src, srcOff, len)
        deflater.finish()
        var n = 0
        while (!deflater.finished()) {
            if (n >= limit) return -1
            n += deflater.deflate(dest, destOff + n, limit - n)
        }
        return if (n >= limit) -1 else n
    }

    override fun close() = deflater.end()
}

internal class LzmaCompressor(hunkBytes: Int) : ChdCompressor() {
    private val cache: ArrayCache = BasicArrayCache()
    private val options = LZMA2Options().apply {
        // MAME: LzmaEncProps level 8 → normal mode, BT4, fast bytes 64, depth 48, dictionary reduced
        // to the hunk; the decoder only depends on lc/lp/pb.
        dictSize = maxOf(LZMA2Options.DICT_SIZE_MIN, hunkBytes)
        setLcLp(3, 0)
        pb = 2
        mode = LZMA2Options.MODE_NORMAL
        niceLen = 64
        matchFinder = LZMA2Options.MF_BT4
        depthLimit = 0
    }

    override fun compress(src: ByteArray, srcOff: Int, len: Int, dest: ByteArray, destOff: Int, limit: Int): Int {
        val out = LimitedOutput(dest, destOff, limit)
        return try {
            val lzma = LZMAOutputStream(out, options, false, cache)
            lzma.write(src, srcOff, len)
            lzma.finish()
            out.count
        } catch (e: IOException) {
            if (e === OverBudget) -1 else throw e
        }
    }
}

/** cdzl / cdlz: chd_cd_compressor in chdcodec.cpp. */
internal class CdCompressor(hunkBytes: Int, private val base: ChdCompressor) : ChdCompressor() {
    private val subcode = ZlibCompressor()
    private val frames = hunkBytes / CdToc.FRAME_SIZE
    private val buffer = ByteArray(frames * CdToc.FRAME_SIZE)

    init {
        require(hunkBytes % CdToc.FRAME_SIZE == 0) { "CD hunks must hold whole frames" }
    }

    override fun compress(src: ByteArray, srcOff: Int, len: Int, dest: ByteArray, destOff: Int, limit: Int): Int {
        val frames = len / CdToc.FRAME_SIZE
        val complenBytes = if (len < 65536) 2 else 3
        val eccBytes = (frames + 7) / 8
        val headerBytes = eccBytes + complenBytes
        if (headerBytes >= limit) return -1
        java.util.Arrays.fill(dest, destOff, destOff + headerBytes, 0)
        val sectorArea = frames * CdToc.MAX_SECTOR_DATA
        for (f in 0 until frames) {
            val sector = f * CdToc.MAX_SECTOR_DATA
            System.arraycopy(src, srcOff + f * CdToc.FRAME_SIZE, buffer, sector, CdToc.MAX_SECTOR_DATA)
            System.arraycopy(src, srcOff + f * CdToc.FRAME_SIZE + CdToc.MAX_SECTOR_DATA, buffer,
                sectorArea + f * CdToc.MAX_SUBCODE_DATA, CdToc.MAX_SUBCODE_DATA)
            if (CdEcc.hasSync(buffer, sector) && CdEcc.verify(buffer, sector)) {
                dest[destOff + f / 8] = (dest[destOff + f / 8].toInt() or (1 shl (f % 8))).toByte()
                java.util.Arrays.fill(buffer, sector, sector + CdEcc.SYNC_SIZE, 0)
                CdEcc.clear(buffer, sector)
            }
        }
        // MAME: the base fails if it does not fit the base length, and the hunk if it reaches len.
        val baseLimit = minOf(sectorArea, limit - headerBytes)
        val baseLen = base.compress(buffer, 0, sectorArea, dest, destOff + headerBytes, baseLimit)
        if (baseLen < 0 || baseLen >= len) return -1
        if (complenBytes > 2) {
            dest[destOff + eccBytes] = (baseLen ushr 16).toByte()
            dest[destOff + eccBytes + 1] = (baseLen ushr 8).toByte()
            dest[destOff + eccBytes + 2] = baseLen.toByte()
        } else {
            dest[destOff + eccBytes] = (baseLen ushr 8).toByte()
            dest[destOff + eccBytes + 1] = baseLen.toByte()
        }
        val subLen = frames * CdToc.MAX_SUBCODE_DATA
        val used = headerBytes + baseLen
        val subOut = subcode.compress(buffer, sectorArea, subLen, dest, destOff + used, minOf(subLen, limit - used))
        if (subOut < 0) return -1
        return used + subOut
    }

    override fun close() {
        base.close()
        subcode.close()
    }
}

internal fun newCompressor(codec: ChdCodec, hunkBytes: Int): ChdCompressor = when (codec) {
    ChdCodec.ZLIB -> ZlibCompressor()
    ChdCodec.LZMA -> LzmaCompressor(hunkBytes)
    ChdCodec.CD_ZLIB -> CdCompressor(hunkBytes, ZlibCompressor())
    ChdCodec.CD_LZMA -> CdCompressor(hunkBytes, LzmaCompressor(hunkBytes / CdToc.FRAME_SIZE * CdToc.MAX_SECTOR_DATA))
}

// ------------------------------------------------------------------------------------ decompressors

class ChdFormatException(message: String) : IOException(message)

internal abstract class ChdDecompressor {
    abstract fun decompress(src: ByteArray, off: Int, len: Int, dest: ByteArray, destOff: Int, destLen: Int)
    open fun close() {}
}

internal class ZlibDecompressor : ChdDecompressor() {
    private val inflater = Inflater(true)
    private var scratch = ByteArray(0)

    override fun decompress(src: ByteArray, off: Int, len: Int, dest: ByteArray, destOff: Int, destLen: Int) {
        // Raw inflate wants one spare byte after the stream.
        if (scratch.size < len + 1) scratch = ByteArray(len + 1)
        System.arraycopy(src, off, scratch, 0, len)
        scratch[len] = 0
        inflater.reset()
        inflater.setInput(scratch, 0, len + 1)
        var n = 0
        try {
            while (n < destLen) {
                val r = inflater.inflate(dest, destOff + n, destLen - n)
                if (r == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                n += r
            }
        } catch (e: java.util.zip.DataFormatException) {
            throw ChdFormatException("Corrupt deflate data: ${e.message}")
        }
        if (n != destLen) throw ChdFormatException("Deflate data decoded to $n bytes instead of $destLen")
    }

    override fun close() = inflater.end()
}

internal class LzmaDecompressor(private val hunkBytes: Int) : ChdDecompressor() {
    private val cache: ArrayCache = BasicArrayCache()

    override fun decompress(src: ByteArray, off: Int, len: Int, dest: ByteArray, destOff: Int, destLen: Int) {
        val dict = maxOf(LZMA2Options.DICT_SIZE_MIN, hunkBytes)
        try {
            LZMAInputStream(ByteArrayInputStream(src, off, len), destLen.toLong(), 3, 0, 2, dict, null, cache).use { input ->
                var n = 0
                while (n < destLen) {
                    val r = input.read(dest, destOff + n, destLen - n)
                    if (r < 0) break
                    n += r
                }
                if (n != destLen) throw ChdFormatException("LZMA data decoded to $n bytes instead of $destLen")
            }
        } catch (e: ChdFormatException) {
            throw e
        } catch (e: IOException) {
            throw ChdFormatException("Corrupt LZMA data: ${e.message}")
        }
    }
}

internal class CdDecompressor(hunkBytes: Int, private val base: ChdDecompressor) : ChdDecompressor() {
    private val subcode = ZlibDecompressor()
    private val buffer = ByteArray(hunkBytes)

    override fun decompress(src: ByteArray, off: Int, len: Int, dest: ByteArray, destOff: Int, destLen: Int) {
        val frames = destLen / CdToc.FRAME_SIZE
        val complenBytes = if (destLen < 65536) 2 else 3
        val eccBytes = (frames + 7) / 8
        val headerBytes = eccBytes + complenBytes
        if (len < headerBytes) throw ChdFormatException("CD hunk too short")
        var baseLen = 0
        for (i in 0 until complenBytes) baseLen = (baseLen shl 8) or (src[off + eccBytes + i].toInt() and 0xFF)
        if (headerBytes + baseLen > len) throw ChdFormatException("CD hunk base length out of range")
        val sectorArea = frames * CdToc.MAX_SECTOR_DATA
        base.decompress(src, off + headerBytes, baseLen, buffer, 0, sectorArea)
        subcode.decompress(src, off + headerBytes + baseLen, len - headerBytes - baseLen, buffer, sectorArea,
            frames * CdToc.MAX_SUBCODE_DATA)
        for (f in 0 until frames) {
            val out = destOff + f * CdToc.FRAME_SIZE
            System.arraycopy(buffer, f * CdToc.MAX_SECTOR_DATA, dest, out, CdToc.MAX_SECTOR_DATA)
            System.arraycopy(buffer, sectorArea + f * CdToc.MAX_SUBCODE_DATA, dest, out + CdToc.MAX_SECTOR_DATA,
                CdToc.MAX_SUBCODE_DATA)
            if ((src[off + f / 8].toInt() and (1 shl (f % 8))) != 0) {
                System.arraycopy(CdEcc.SYNC_HEADER, 0, dest, out, CdEcc.SYNC_SIZE)
                CdEcc.generate(dest, out)
            }
        }
    }

    override fun close() {
        base.close()
        subcode.close()
    }
}

internal fun newDecompressor(codec: ChdCodec, hunkBytes: Int): ChdDecompressor = when (codec) {
    ChdCodec.ZLIB -> ZlibDecompressor()
    ChdCodec.LZMA -> LzmaDecompressor(hunkBytes)
    ChdCodec.CD_ZLIB -> CdDecompressor(hunkBytes, ZlibDecompressor())
    ChdCodec.CD_LZMA -> CdDecompressor(hunkBytes, LzmaDecompressor(hunkBytes / CdToc.FRAME_SIZE * CdToc.MAX_SECTOR_DATA))
}
