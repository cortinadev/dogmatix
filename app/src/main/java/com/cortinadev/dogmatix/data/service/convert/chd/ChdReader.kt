// Port of MAME's CHD/CD-ROM code (src/lib/util: chd.cpp, chdcodec.cpp, cdrom.cpp, huffman.cpp, bitstream.h),
// BSD-3-Clause, copyright-holders Aaron Giles, R. Belmont. Binary redistribution must reproduce the BSD-3 notice.
package com.cortinadev.dogmatix.data.service.convert.chd

import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory

/** One metadata entry: 4-character tag, flags and data (CD track strings end in a NUL). */
class ChdMetadata(val tag: String, val flags: Int, val data: ByteArray) {
    /** The data as text, without the trailing NUL. */
    val text: String get() = String(data, Charsets.ISO_8859_1).trimEnd('\u0000')
}

/**
 * Reads a CHD v5 with the codecs Dogmatix writes (zlib, lzma, cdzl, cdlz and uncompressed hunks).
 * Enough to verify a conversion and to extract one in tests; CHDs from chdman that use FLAC, Huffman
 * or zstd hunks open fine but those hunks fail with [ChdFormatException].
 */
class ChdReader(private val input: ChdInput) {
    val logicalBytes: Long
    val hunkBytes: Int
    val unitBytes: Int
    val hunkCount: Int
    val codecIds: IntArray
    val rawSha1: ByteArray
    val sha1: ByteArray
    val metadata: List<ChdMetadata>
    internal val map: ChdMap.Entries

    init {
        if (input.size < ChdWriter.HEADER_SIZE) throw ChdFormatException("Not a CHD: too short")
        val h = ByteArray(ChdWriter.HEADER_SIZE)
        input.readFully(0, h, 0, h.size)
        if (String(h, 0, 8, Charsets.ISO_8859_1) != "MComprHD") throw ChdFormatException("Not a CHD")
        val version = ChdMap.getInt(h, 12)
        if (version != 5) throw ChdFormatException("Unsupported CHD version $version")
        codecIds = IntArray(4) { ChdMap.getInt(h, 16 + 4 * it) }
        logicalBytes = getLong(h, 32)
        val mapOffset = getLong(h, 40)
        var metaOffset = getLong(h, 48)
        hunkBytes = ChdMap.getInt(h, 56)
        unitBytes = ChdMap.getInt(h, 60)
        rawSha1 = h.copyOfRange(64, 84)
        sha1 = h.copyOfRange(84, 104)
        if (h.copyOfRange(104, 124).any { it.toInt() != 0 }) throw ChdFormatException("CHDs with a parent are not supported")
        if (hunkBytes <= 0 || unitBytes <= 0 || logicalBytes <= 0) throw ChdFormatException("Bad CHD header")
        val count = (logicalBytes + hunkBytes - 1) / hunkBytes
        if (count > Int.MAX_VALUE) throw ChdFormatException("Too many hunks")
        hunkCount = count.toInt()
        if (codecIds[0] == 0) throw ChdFormatException("Uncompressed CHDs are not supported")
        if (mapOffset <= 0 || mapOffset + 16 > input.size) throw ChdFormatException("Map offset out of range")

        val mapHeader = ByteArray(16)
        input.readFully(mapOffset, mapHeader, 0, 16)
        val mapBytes = ChdMap.getInt(mapHeader, 0).toLong() and 0xFFFFFFFFL
        if (mapOffset + 16 + mapBytes > input.size) throw ChdFormatException("Map truncated")
        val mapData = ByteArray((16 + mapBytes).toInt())
        input.readFully(mapOffset, mapData, 0, mapData.size)
        map = ChdMap.decode(mapData, hunkCount, hunkBytes)

        val metas = mutableListOf<ChdMetadata>()
        var guard = 0
        while (metaOffset != 0L) {
            if (++guard > 10_000 || metaOffset + ChdWriter.METADATA_HEADER_SIZE > input.size) throw ChdFormatException("Bad metadata chain")
            val mh = ByteArray(ChdWriter.METADATA_HEADER_SIZE)
            input.readFully(metaOffset, mh, 0, mh.size)
            val tag = ChdCodec.intToTag(ChdMap.getInt(mh, 0))
            val flags = mh[4].toInt() and 0xFF
            val length = ((mh[5].toInt() and 0xFF) shl 16) or ((mh[6].toInt() and 0xFF) shl 8) or (mh[7].toInt() and 0xFF)
            val data = ByteArray(length)
            if (metaOffset + 16 + length > input.size) throw ChdFormatException("Metadata truncated")
            input.readFully(metaOffset + 16, data, 0, length)
            metas += ChdMetadata(tag, flags, data)
            metaOffset = getLong(mh, 8)
        }
        metadata = metas
    }

    /** chdman's own overall SHA-1 rule, from the stored data SHA-1 and the metadata. */
    fun computedOverallSha1(raw: ByteArray = rawSha1): ByteArray =
        ChdWriter.overallSha1(raw, metadata.filter { it.flags and 1 != 0 }.map { m ->
            val t = ChdCodec.tagToInt(m.tag)
            byteArrayOf((t ushr 24).toByte(), (t ushr 16).toByte(), (t ushr 8).toByte(), t.toByte()) +
                MessageDigest.getInstance("SHA-1").digest(m.data)
        })

    /** Decompresses hunks; one per thread. */
    inner class HunkDecoder : AutoCloseable {
        private val decoders = arrayOfNulls<ChdDecompressor>(4)
        private var comp = ByteArray(0)

        private fun decoder(slot: Int): ChdDecompressor {
            decoders[slot]?.let { return it }
            val codec = ChdCodec.byId(codecIds[slot])
                ?: throw ChdFormatException("Unsupported codec '${ChdCodec.intToTag(codecIds[slot])}'")
            return newDecompressor(codec, hunkBytes).also { decoders[slot] = it }
        }

        /** Fills [dst] (hunkBytes) with hunk [index], following copies; checks the stored CRC-16. */
        fun read(index: Int, dst: ByteArray) {
            var h = index
            var hops = 0
            while (map.type[h].toInt() == ChdMap.SELF) {
                val ref = map.offset[h]
                if (ref < 0 || ref >= h || ++hops > hunkCount) throw ChdFormatException("Bad self reference in hunk $h")
                h = ref.toInt()
            }
            val type = map.type[h].toInt()
            val length = map.length[h]
            val offset = map.offset[h]
            if (offset < 0 || offset + length > input.size) throw ChdFormatException("Hunk $h out of range")
            when (type) {
                ChdMap.NONE -> input.readFully(offset, dst, 0, hunkBytes)
                in 0..3 -> {
                    if (comp.size < length) comp = ByteArray(length)
                    input.readFully(offset, comp, 0, length)
                    decoder(type).decompress(comp, 0, length, dst, 0, hunkBytes)
                }
                ChdMap.PARENT -> throw ChdFormatException("Hunk $h needs a parent CHD")
                else -> throw ChdFormatException("Bad map type $type in hunk $h")
            }
            if (Crc16.of(dst, 0, hunkBytes) != map.crc[h]) throw ChdFormatException("CRC mismatch in hunk $h")
        }

        override fun close() {
            decoders.forEach { runCatching { it?.close() } }
        }
    }

    /**
     * Decompresses every hunk (in parallel), checks each CRC-16, and recomputes the data SHA-1 and
     * the overall SHA-1. Returns the recomputed data SHA-1; throws [ChdFormatException] on any error.
     */
    fun verify(
        threads: Int = 1,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        sink: ((index: Int, data: ByteArray, logical: Int) -> Unit)? = null,
    ): ByteArray {
        val pool = Executors.newFixedThreadPool(threads, daemonThreads)
        val decoders = java.util.concurrent.ConcurrentLinkedQueue<HunkDecoder>()
        val local = ThreadLocal<HunkDecoder>()
        val sha = MessageDigest.getInstance("SHA-1")
        val window = ArrayDeque<Future<ByteArray>>()
        var next = 0
        var lastReport = -1
        fun drain() {
            val data = try { window.removeFirst().get() } catch (e: ExecutionException) { throw e.cause ?: e }
            val h = next++
            val logical = minOf(hunkBytes.toLong(), logicalBytes - h.toLong() * hunkBytes).toInt()
            sha.update(data, 0, logical)
            sink?.invoke(h, data, logical)
            val pct = (next * 1000L / hunkCount).toInt()
            if (pct != lastReport) { lastReport = pct; onProgress(pct / 1000f) }
        }
        try {
            for (h in 0 until hunkCount) {
                if (isCancelled()) throw ChdCancelledException()
                window.addLast(pool.submit<ByteArray> {
                    val d = local.get() ?: HunkDecoder().also { local.set(it); decoders += it }
                    ByteArray(hunkBytes).also { d.read(h, it) }
                })
                if (window.size >= threads * 4) drain()
            }
            while (window.isNotEmpty()) drain()
        } finally {
            window.forEach { it.cancel(true) }
            pool.shutdownNow()
            decoders.forEach { it.close() }
        }
        val raw = sha.digest()
        if (!raw.contentEquals(rawSha1)) throw ChdFormatException("Data SHA-1 mismatch: ${raw.toHex()} ≠ ${rawSha1.toHex()}")
        if (!computedOverallSha1(raw).contentEquals(sha1)) throw ChdFormatException("Overall SHA-1 mismatch")
        return raw
    }

    companion object {
        internal fun getLong(b: ByteArray, p: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[p + i].toLong() and 0xFF)
            return v
        }

        private val daemonThreads = ThreadFactory { r ->
            Thread(r, "chd-verify").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 2 }
        }
    }
}
