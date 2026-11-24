// Port of MAME's CHD/CD-ROM code (src/lib/util: chd.cpp, chdcodec.cpp, cdrom.cpp, huffman.cpp, bitstream.h),
// BSD-3-Clause, copyright-holders Aaron Giles, R. Belmont. Binary redistribution must reproduce the BSD-3 notice.
package com.cortinadev.dogmatix.data.service.convert.chd

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory

/** Raised when [ChdWriter.isCancelled] answers true; the output is left truncated to zero. */
class ChdCancelledException : Exception("Cancelled")

/** What the writer produced. SHA-1s as lowercase hex, like `chdman info` prints them. */
data class ChdWriteSummary(
    val logicalBytes: Long,
    val fileBytes: Long,
    val hunkBytes: Int,
    val hunks: Int,
    val rawSha1: String,
    val sha1: String,
    /** Hunks per kind: codec tag, "none" or "self". */
    val hunksByKind: Map<String, Int>,
)

/**
 * Writes a CHD v5 like chdman's chd_file_compressor: the hunks are read in order, compressed in
 * parallel with every configured codec (the smallest wins; nothing smaller than the raw hunk → stored
 * uncompressed), identical hunks become references to the first copy, and at the end the map,
 * the metadata and the header (with the data and overall SHA-1) are written.
 *
 * Layout: header (124 bytes) · hunks · map · metadata. The output must allow positional writes
 * (a regular file or a SAF document from a local provider opened "rw").
 */
internal class ChdWriter(
    private val out: FileChannel,
    private val logicalBytes: Long,
    private val hunkBytes: Int,
    private val unitBytes: Int,
    private val codecs: List<ChdCodec>,
    /** Tag (4 ASCII chars) and data of each metadata entry, in order; all of them checksummed. */
    private val metadata: List<Pair<String, ByteArray>>,
    private val threads: Int,
    /** Fills the whole hunk; bytes past [logicalBytes] must be zero. Called on one thread, in order. */
    private val readHunk: (index: Int, dst: ByteArray) -> Unit,
    private val onProgress: (Float) -> Unit = {},
    private val isCancelled: () -> Boolean = { false },
) {
    init {
        require(codecs.size in 1..4) { "1 to 4 codecs" }
        require(hunkBytes > 0 && hunkBytes % unitBytes == 0) { "Hunk size must be a multiple of the unit size" }
        require(threads >= 1)
    }

    private val hunkCount: Int = run {
        val n = (logicalBytes + hunkBytes - 1) / hunkBytes
        require(n in 1..Int.MAX_VALUE) { "Unsupported size" }
        n.toInt()
    }

    private class Key(val sha1: ByteArray, val crc: Int) {
        private val hash = java.util.Arrays.hashCode(sha1) * 31 + crc
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is Key && other.crc == crc && sha1.contentEquals(other.sha1)
    }

    private class Done(val key: Key, val codec: Int, val data: ByteArray, val length: Int)

    /** Per-thread codec state: compressors, two output buffers and a SHA-1. */
    private inner class Worker {
        val compressors = codecs.map { newCompressor(it, hunkBytes) }
        val sha1: MessageDigest = MessageDigest.getInstance("SHA-1")
        var best = ByteArray(hunkBytes + 64)
        var test = ByteArray(hunkBytes + 64)

        fun process(data: ByteArray, known: Set<Key>): Done {
            val key = Key(sha1.digest(data), Crc16.of(data))
            if (key in known) return Done(key, SKIPPED, data, 0)
            var bestCodec = -1
            var bestLen = hunkBytes
            for ((i, c) in compressors.withIndex()) {
                val n = c.compress(data, 0, hunkBytes, test, 0, bestLen)
                if (n in 0 until bestLen) {
                    bestCodec = i
                    bestLen = n
                    val swap = best; best = test; test = swap
                }
            }
            return if (bestCodec < 0) Done(key, -1, data, hunkBytes)
            else Done(key, bestCodec, best.copyOf(bestLen), bestLen)
        }
    }

    private val workers = ConcurrentHashMap<Thread, Worker>()
    private val local = ThreadLocal<Worker>()

    private fun worker(): Worker = local.get() ?: Worker().also {
        local.set(it)
        workers[Thread.currentThread()] = it
    }

    // Buffered positional output.
    private val outBuf = ByteBuffer.allocate(1 shl 20)
    private var outPos = 0L

    private fun append(bytes: ByteArray, off: Int = 0, len: Int = bytes.size): Long {
        val at = outPos + outBuf.position()
        var o = off
        var l = len
        while (l > 0) {
            if (!outBuf.hasRemaining()) flush()
            val n = minOf(l, outBuf.remaining())
            outBuf.put(bytes, o, n)
            o += n
            l -= n
        }
        return at
    }

    private fun flush() {
        outBuf.flip()
        while (outBuf.hasRemaining()) outPos += out.write(outBuf, outPos)
        outBuf.clear()
    }

    private fun writeAt(position: Long, bytes: ByteArray) {
        val b = ByteBuffer.wrap(bytes)
        var p = position
        while (b.hasRemaining()) p += out.write(b, p)
    }

    fun write(): ChdWriteSummary {
        out.truncate(0)
        outPos = HEADER_SIZE.toLong()
        val map = ChdMap.Entries(hunkCount)
        val selfMap = ConcurrentHashMap<Key, Int>()
        val rawSha1 = MessageDigest.getInstance("SHA-1")
        val kinds = IntArray(codecs.size + 2) // codecs…, none, self
        val pool: ExecutorService = Executors.newFixedThreadPool(threads, daemonThreads)
        val window = ArrayDeque<Future<Done>>()
        val depth = threads * 4
        var nextWrite = 0
        var lastReport = -1

        fun drainOne() {
            val done = try {
                window.removeFirst().get()
            } catch (e: ExecutionException) {
                throw (e.cause as? Exception) ?: IOException(e.cause)
            }
            val h = nextWrite++
            val ref = selfMap[done.key]
            if (ref != null) {
                map.type[h] = ChdMap.SELF.toByte()
                map.offset[h] = ref.toLong()
                kinds[codecs.size + 1]++
            } else {
                if (done.codec == SKIPPED) throw IllegalStateException("Hunk $h skipped without a match")
                val offset = append(done.data, 0, done.length)
                map.type[h] = (if (done.codec < 0) ChdMap.NONE else ChdMap.TYPE_0 + done.codec).toByte()
                map.length[h] = done.length
                map.offset[h] = offset
                map.crc[h] = done.key.crc
                selfMap[done.key] = h
                kinds[if (done.codec < 0) codecs.size else done.codec]++
            }
            val pct = (nextWrite * 1000L / hunkCount).toInt()
            if (pct != lastReport) {
                lastReport = pct
                onProgress(pct / 1000f)
            }
        }

        try {
            for (h in 0 until hunkCount) {
                if (isCancelled()) throw ChdCancelledException()
                val data = ByteArray(hunkBytes)
                readHunk(h, data)
                val logical = minOf(hunkBytes.toLong(), logicalBytes - h.toLong() * hunkBytes).toInt()
                rawSha1.update(data, 0, logical)
                val known = selfMap.keys
                window.addLast(pool.submit<Done> { worker().process(data, known) })
                if (window.size >= depth) drainOne()
            }
            while (window.isNotEmpty()) {
                if (isCancelled()) throw ChdCancelledException()
                drainOne()
            }

            val mapBytes = ChdMap.encode(map)
            val mapOffset = append(mapBytes)

            val metaOffsets = mutableListOf<Long>()
            var cursor = outPos + outBuf.position()
            for ((_, data) in metadata) {
                metaOffsets += cursor
                cursor += METADATA_HEADER_SIZE + data.size
            }
            val metaHashes = mutableListOf<ByteArray>()
            for ((i, entry) in metadata.withIndex()) {
                val (tag, data) = entry
                require(data.size < (1 shl 24)) { "Metadata too large" }
                val header = ByteArray(METADATA_HEADER_SIZE)
                ChdMap.putInt(header, 0, ChdCodec.tagToInt(tag))
                header[4] = METADATA_CHECKSUM
                header[5] = (data.size ushr 16).toByte()
                header[6] = (data.size ushr 8).toByte()
                header[7] = data.size.toByte()
                val next = metaOffsets.getOrNull(i + 1) ?: 0L
                for (b in 0 until 8) header[8 + b] = (next ushr (56 - 8 * b)).toByte()
                append(header)
                append(data)
                metaHashes += ChdCodec.tagToInt(tag).let { t ->
                    byteArrayOf((t ushr 24).toByte(), (t ushr 16).toByte(), (t ushr 8).toByte(), t.toByte())
                } + MessageDigest.getInstance("SHA-1").digest(data)
            }
            flush()
            val fileBytes = outPos

            val raw = rawSha1.digest()
            val overall = overallSha1(raw, metaHashes)
            writeAt(0, header(mapOffset, metaOffsets.firstOrNull() ?: 0L, raw, overall))
            out.force(true)

            val byKind = buildMap {
                codecs.forEachIndexed { i, c -> if (kinds[i] > 0) put(c.tag, kinds[i]) }
                if (kinds[codecs.size] > 0) put("none", kinds[codecs.size])
                if (kinds[codecs.size + 1] > 0) put("self", kinds[codecs.size + 1])
            }
            return ChdWriteSummary(logicalBytes, fileBytes, hunkBytes, hunkCount, raw.toHex(), overall.toHex(), byKind)
        } catch (e: Throwable) {
            window.forEach { it.cancel(true) }
            runCatching { out.truncate(0) }
            throw e
        } finally {
            pool.shutdownNow()
            workers.values.forEach { w -> w.compressors.forEach { runCatching { it.close() } } }
        }
    }

    private fun header(mapOffset: Long, metaOffset: Long, raw: ByteArray, overall: ByteArray): ByteArray {
        val h = ByteArray(HEADER_SIZE)
        "MComprHD".toByteArray(Charsets.US_ASCII).copyInto(h)
        ChdMap.putInt(h, 8, HEADER_SIZE)
        ChdMap.putInt(h, 12, 5)
        for (i in 0 until 4) ChdMap.putInt(h, 16 + 4 * i, codecs.getOrNull(i)?.id ?: 0)
        putLong(h, 32, logicalBytes)
        putLong(h, 40, mapOffset)
        putLong(h, 48, metaOffset)
        ChdMap.putInt(h, 56, hunkBytes)
        ChdMap.putInt(h, 60, unitBytes)
        raw.copyInto(h, 64)
        overall.copyInto(h, 84)
        return h
    }

    companion object {
        const val HEADER_SIZE = 124
        const val METADATA_HEADER_SIZE = 16
        const val METADATA_CHECKSUM: Byte = 1
        private const val SKIPPED = -2

        /** SHA-1 of the data SHA-1 followed by the sorted (tag, SHA-1) of every checksummed metadata entry. */
        fun overallSha1(raw: ByteArray, metaHashes: List<ByteArray>): ByteArray {
            val md = MessageDigest.getInstance("SHA-1")
            md.update(raw)
            metaHashes.sortedWith { a, b -> compareUnsigned(a, b) }.forEach { md.update(it) }
            return md.digest()
        }

        private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (d != 0) return d
            }
            return a.size - b.size
        }

        internal fun putLong(b: ByteArray, p: Int, v: Long) {
            for (i in 0 until 8) b[p + i] = (v ushr (56 - 8 * i)).toByte()
        }

        private val daemonThreads = ThreadFactory { r ->
            Thread(r, "chd-compress").apply {
                isDaemon = true
                // Below the UI and the download threads (Java 3 ≈ Android's background nice 10).
                priority = Thread.NORM_PRIORITY - 2
            }
        }
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
