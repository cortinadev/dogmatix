package com.cortinadev.dogmatix.data.service.convert

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.cortinadev.dogmatix.util.rvz.DiscDigest
import com.cortinadev.dogmatix.util.rvz.DiscKind
import com.cortinadev.dogmatix.util.rvz.DiscLayout
import com.cortinadev.dogmatix.util.rvz.DiscSource
import com.cortinadev.dogmatix.util.rvz.IsoSource
import com.cortinadev.dogmatix.util.rvz.RvzCancelledException
import com.cortinadev.dogmatix.util.rvz.RvzFormatException
import com.cortinadev.dogmatix.util.rvz.RvzOptions
import com.cortinadev.dogmatix.util.rvz.RvzReader
import com.cortinadev.dogmatix.util.rvz.RvzWriteException
import com.cortinadev.dogmatix.util.rvz.RvzWriter
import com.cortinadev.dogmatix.util.rvz.WbfsSource
import java.io.EOFException
import java.io.FileDescriptor
import java.io.IOException
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/** Why a conversion or a verification did not succeed. */
enum class RvzFailure {
    /** Not a GameCube/Wii image this engine reads (ISO, GCM or single-file WBFS). */
    NOT_A_DISC,

    /** Already RVZ/WIA/CISO/GCZ/TGC: convert it with Dolphin instead. */
    ALREADY_COMPRESSED,

    /** NKit images are not lossless discs; RVZ would keep the NKit damage. */
    NKIT,

    /** The input ends before the disc does (incomplete download or split WBFS). */
    TRUNCATED,

    /** The output descriptor cannot seek (pipe or cloud provider): write to a real file. */
    OUTPUT_NOT_SEEKABLE,
    NO_SPACE,
    READ_ERROR,
    WRITE_ERROR,

    /** The RVZ is not readable (verification). */
    CORRUPT,

    /** The RVZ decodes, but not to the expected disc (verification). */
    MISMATCH,

    /** A bug or an unexpected library error; the message says which. */
    INTERNAL,
}

sealed class RvzResult {
    /**
     * [digest] is the plain ISO's (size, CRC32, SHA-1): after [RvzEngine.convert], the source
     * disc's; after [RvzEngine.verify], what the RVZ decodes to. [partitionsDecrypted] is false
     * for a Wii disc whose partitions had to be stored encrypted (lossless, but barely smaller).
     */
    data class Success(
        val digest: DiscDigest,
        val outputBytes: Long,
        val isWii: Boolean,
        val partitionsDecrypted: Boolean,
        val millis: Long,
    ) : RvzResult()

    data class Failure(val reason: RvzFailure, val detail: String) : RvzResult()

    data object Cancelled : RvzResult()
}

/** What [RvzEngine.probe] found. */
data class RvzProbe(val kind: DiscKind, val discSize: Long, val gameId: String?)

/**
 * GameCube/Wii → RVZ, equivalent to `dolphin-tool convert -f rvz` with its defaults (zstd level
 * 5, 128 KiB chunks, junk stored as seeds, Wii partitions decrypted and without hashes). Pure
 * Kotlin on top of `util/rvz`; zstd comes from zstd-jni.
 *
 * Every call blocks: run it on `Dispatchers.IO`. The descriptors stay owned by the caller (the
 * engine works on duplicates). On failure or cancel the output holds garbage and the caller
 * deletes it; the source is never written.
 *
 * Output: RVZ writes its header and tables last at the start of the file, so [convert] needs a
 * seekable descriptor (`"rw"` on a document of the external-storage provider, internal or SD, is
 * a real file and works). Space: the RVZ lands next to the source (typically 30–70 % of the ISO;
 * scrubbed or junk-heavy discs much less); nothing else is written.
 */
@Singleton
class RvzEngine @Inject constructor() {

    fun probe(input: ParcelFileDescriptor): RvzProbe = inChannel(input).use { ch ->
        val head = ByteArray(0x440)
        val n = minOf(ch.size(), head.size.toLong()).toInt()
        readAt(ch, head, n)
        val kind = DiscLayout.detect(head)
        when (kind) {
            DiscKind.WBFS -> runCatching { WbfsSource.open(ch) }.getOrNull()?.let { src ->
                val h = ByteArray(0x440)
                src.read(0, h, 0, h.size)
                RvzProbe(kind, src.size, gameId(h))
            } ?: RvzProbe(kind, 0, null)
            DiscKind.GAMECUBE, DiscKind.WII -> RvzProbe(kind, ch.size(), gameId(head))
            else -> RvzProbe(kind, ch.size(), null)
        }
    }

    fun convert(
        input: ParcelFileDescriptor,
        output: ParcelFileDescriptor,
        options: RvzOptions = RvzOptions(),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): RvzResult {
        if (!isRegularFile(output.fileDescriptor)) {
            return RvzResult.Failure(RvzFailure.OUTPUT_NOT_SEEKABLE, "The destination is not a regular file")
        }
        return inChannel(input).use { inCh -> outChannel(output).use { outCh -> convert(inCh, outCh, options, onProgress, isCancelled) } }
    }

    /** [convert] on channels (e.g. a temporary file); [output] must be writable and seekable. */
    fun convert(
        input: FileChannel,
        output: FileChannel,
        options: RvzOptions = RvzOptions(),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): RvzResult {
        val started = System.currentTimeMillis()
        return guard(writing = true) {
            val source = openSource(input)
            val layout = DiscLayout.analyze(source)
            if (layout.rawReason != null) Log.w(TAG, "Wii partitions stored as they are: ${layout.rawReason}")
            val digest = RvzWriter(source, layout, output, options).write(
                onProgress = { done, total -> onProgress(if (total > 0) done.toFloat() / total else 0f) },
                isCancelled = isCancelled,
            )
            RvzResult.Success(digest, output.size(), layout.isWii, layout.isWii && layout.partitions.isNotEmpty(), System.currentTimeMillis() - started)
        }
    }

    /**
     * Decodes [rvz] entirely and compares it with [expected] (the digest [convert] returned). With
     * `expected == null` it only checks that the file decodes, and returns its digest (useful for
     * a DAT lookup). Only delete the original after a [RvzResult.Success] here.
     */
    fun verify(
        rvz: ParcelFileDescriptor,
        expected: DiscDigest?,
        threads: Int = RvzOptions.defaultThreads(),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): RvzResult = inChannel(rvz).use { verify(it, expected, threads, onProgress, isCancelled) }

    fun verify(
        rvz: FileChannel,
        expected: DiscDigest?,
        threads: Int = RvzOptions.defaultThreads(),
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): RvzResult {
        val started = System.currentTimeMillis()
        return guard(writing = false) {
            RvzReader(rvz).use { reader ->
                val digest = reader.decode(
                    threads = threads,
                    onProgress = { done, total -> onProgress(if (total > 0) done.toFloat() / total else 0f) },
                    isCancelled = isCancelled,
                )
                if (expected != null && !digest.sameAs(expected)) {
                    RvzResult.Failure(RvzFailure.MISMATCH, "Decoded ${digest.size} bytes, SHA-1 ${digest.sha1Hex}; expected ${expected.size} bytes, SHA-1 ${expected.sha1Hex}")
                } else {
                    RvzResult.Success(digest, rvz.size(), reader.isWii, reader.partitions.isNotEmpty(), System.currentTimeMillis() - started)
                }
            }
        }
    }

    private fun openSource(ch: FileChannel): DiscSource {
        val head = ByteArray(0x440)
        readAt(ch, head, minOf(ch.size(), head.size.toLong()).toInt())
        return when (DiscLayout.detect(head)) {
            DiscKind.GAMECUBE, DiscKind.WII -> IsoSource(ch)
            DiscKind.WBFS -> WbfsSource.open(ch)
            DiscKind.NKIT -> throw Reject(RvzFailure.NKIT, "NKit image")
            DiscKind.RVZ, DiscKind.WIA, DiscKind.OTHER_CONTAINER -> throw Reject(RvzFailure.ALREADY_COMPRESSED, "Already a compressed disc image")
            DiscKind.UNKNOWN -> throw Reject(RvzFailure.NOT_A_DISC, "Not a GameCube or Wii disc")
        }
    }

    private class Reject(val reason: RvzFailure, message: String) : Exception(message)

    private inline fun guard(writing: Boolean, block: () -> RvzResult): RvzResult = try {
        block()
    } catch (e: RvzCancelledException) {
        RvzResult.Cancelled
    } catch (e: Reject) {
        RvzResult.Failure(e.reason, e.message.orEmpty())
    } catch (e: RvzFormatException) {
        RvzResult.Failure(if (writing) RvzFailure.NOT_A_DISC else RvzFailure.CORRUPT, e.message.orEmpty())
    } catch (e: EOFException) {
        RvzResult.Failure(if (writing) RvzFailure.TRUNCATED else RvzFailure.CORRUPT, e.message.orEmpty())
    } catch (e: IOException) {
        Log.w(TAG, "RVZ ${if (writing) "conversion" else "verification"} failed", e)
        val reason = when {
            isNoSpace(e) -> RvzFailure.NO_SPACE
            e is RvzWriteException -> RvzFailure.WRITE_ERROR
            else -> RvzFailure.READ_ERROR
        }
        RvzResult.Failure(reason, e.message.orEmpty())
    } catch (e: OutOfMemoryError) {
        RvzResult.Failure(RvzFailure.READ_ERROR, "Out of memory (use fewer threads)")
    } catch (e: com.github.luben.zstd.ZstdException) {
        RvzResult.Failure(if (writing) RvzFailure.INTERNAL else RvzFailure.CORRUPT, e.message.orEmpty())
    } catch (e: InterruptedException) {
        RvzResult.Cancelled
    } catch (e: RuntimeException) {
        Log.e(TAG, "RVZ engine error", e)
        RvzResult.Failure(RvzFailure.INTERNAL, e.toString())
    }

    private fun isNoSpace(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is ErrnoException && t.errno == OsConstants.ENOSPC) return true
            if (t.message?.contains("ENOSPC") == true || t.message?.contains("No space left") == true) return true
            t = t.cause
        }
        return false
    }

    private fun isRegularFile(fd: FileDescriptor): Boolean =
        runCatching { OsConstants.S_ISREG(Os.fstat(fd).st_mode) }.getOrDefault(false)

    private fun inChannel(pfd: ParcelFileDescriptor): FileChannel =
        ParcelFileDescriptor.AutoCloseInputStream(pfd.dup()).channel

    private fun outChannel(pfd: ParcelFileDescriptor): FileChannel =
        ParcelFileDescriptor.AutoCloseOutputStream(pfd.dup()).channel

    private fun readAt(ch: FileChannel, buf: ByteArray, len: Int) {
        val bb = java.nio.ByteBuffer.wrap(buf, 0, len)
        var p = 0L
        while (bb.hasRemaining()) {
            val n = ch.read(bb, p)
            if (n < 0) break
            p += n
        }
    }

    private fun gameId(head: ByteArray): String? =
        head.copyOfRange(0, 6).takeIf { b -> b.all { it in 0x20..0x7E } }?.toString(Charsets.US_ASCII)

    private companion object {
        const val TAG = "RvzEngine"
    }
}
