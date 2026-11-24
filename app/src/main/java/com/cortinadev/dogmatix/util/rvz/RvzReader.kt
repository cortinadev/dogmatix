package com.cortinadev.dogmatix.util.rvz

import java.io.Closeable
import java.nio.channels.FileChannel

/**
 * Decodes a whole RVZ back into the plain ISO it stores (in order, in parallel) to verify it:
 * the result's digest must match the source disc's. Reads RVZ files with zstd or no compression
 * and chunks of 32 KiB to 2 MiB, which covers what this engine and `dolphin-tool` write by
 * default.
 */
class RvzReader(private val channel: FileChannel) : Closeable {
    val header: RvzFormat.Header
    val disc: RvzFormat.Disc
    val partitions: List<RvzFormat.Partition>
    val rawData: List<RvzFormat.RawData>
    val groups: Array<RvzFormat.Group>
    private val codec = RvzCodec()

    init {
        val fileSize = channel.size()
        if (fileSize < RvzFormat.HEADER_SIZE + RvzFormat.DISC_SIZE) throw RvzFormatException("File too short")
        val hb = ByteArray(RvzFormat.HEADER_SIZE).also { channel.readFully(0, it, 0, it.size) }
        header = RvzFormat.Header.parse(hb)
        if (header.versionCompatible > RvzFormat.VERSION || header.version < RvzFormat.VERSION_READ_COMPATIBLE) {
            throw RvzFormatException("Unsupported RVZ version")
        }
        if (header.fileSize != fileSize) throw RvzFormatException("File size ${fileSize} does not match the header (${header.fileSize})")
        val discSize = header.discStructSize
        if (discSize < 0xD4 || discSize > 0x1000) throw RvzFormatException("Bad disc struct size")
        val db = ByteArray(discSize).also { channel.readFully(RvzFormat.HEADER_SIZE.toLong(), it, 0, it.size) }
        if (!RvzFormat.sha1(db).contentEquals(header.discHash)) throw RvzFormatException("Disc struct hash mismatch")
        disc = RvzFormat.Disc.parse(if (db.size < RvzFormat.DISC_SIZE) db.copyOf(RvzFormat.DISC_SIZE) else db)
        if (disc.compression != RvzFormat.COMPRESSION_ZSTD && disc.compression != RvzFormat.COMPRESSION_NONE) {
            throw RvzFormatException("Unsupported compression ${disc.compression}")
        }
        if (!RvzFormat.isValidChunk(disc.chunkSize)) throw RvzFormatException("Unsupported chunk size ${disc.chunkSize}")
        if (disc.numPartitions !in 0..64 || disc.partTypeSize < 0 || disc.partTypeSize > 0x100) {
            throw RvzFormatException("Bad partition table")
        }

        val pb = ByteArray(disc.numPartitions * disc.partTypeSize)
        channel.readFully(disc.partOffset, pb, 0, pb.size)
        if (!RvzFormat.sha1(pb).contentEquals(disc.partHash)) throw RvzFormatException("Partition table hash mismatch")
        partitions = List(disc.numPartitions) { i ->
            val one = ByteArray(RvzFormat.PART_SIZE)
            System.arraycopy(pb, i * disc.partTypeSize, one, 0, minOf(disc.partTypeSize, RvzFormat.PART_SIZE))
            RvzFormat.Partition.get(java.nio.ByteBuffer.wrap(one))
        }
        if (disc.numRawData !in 0..10_000 || disc.numGroups !in 0..10_000_000) throw RvzFormatException("Bad table sizes")
        rawData = RvzFormat.parseRawData(table(disc.rawDataOffset, disc.rawDataSize, disc.numRawData * RvzFormat.RAW_DATA_SIZE), disc.numRawData)
        groups = RvzFormat.parseGroups(table(disc.groupOffset, disc.groupSize, disc.numGroups * RvzFormat.GROUP_SIZE), disc.numGroups)
    }

    private fun table(offset: Long, size: Int, plain: Int): ByteArray {
        if (size < 0 || offset < 0 || offset + size > channel.size()) throw RvzFormatException("Table outside the file")
        val b = ByteArray(size).also { channel.readFully(offset, it, 0, size) }
        return if (disc.compression == RvzFormat.COMPRESSION_NONE) b else codec.decompress(b, 0, size, plain)
    }

    val isoSize: Long get() = header.isoSize
    val isWii: Boolean get() = disc.discType == RvzFormat.DISC_TYPE_WII

    private sealed class Segment(val start: Long, val end: Long)
    private class RawSegment(start: Long, end: Long, val group: Int, val groupStart: Long) : Segment(start, end)
    private class WiiSegment(start: Long, end: Long, val partition: RvzFormat.Partition, val pd: RvzFormat.PartData, val firstSector: Int, val sectors: Int) :
        Segment(start, end)
    private class ZeroSegment(start: Long, end: Long) : Segment(start, end)

    private fun segments(): List<Segment> {
        val chunk = disc.chunkSize.toLong()
        val list = ArrayList<Segment>()
        for (r in rawData) {
            for (i in 0 until r.numGroups) {
                val gs = r.startOffset + i * chunk
                val ge = minOf(gs + chunk, r.endOffset)
                val from = if (gs < r.offset && gs == 0L) 0L else maxOf(gs, r.offset)
                if (ge > from) list += RawSegment(from, ge, r.groupIndex + i, gs)
            }
        }
        for (p in partitions) for (pd in p.data) {
            var s = pd.firstSector
            val end = pd.firstSector + pd.numSectors
            while (s < end) {
                val n = minOf(64, end - s)
                list += WiiSegment(s * SECTOR, (s + n) * SECTOR, p, pd, s, n)
                s += n
            }
        }
        list.sortBy { it.start }
        val out = ArrayList<Segment>()
        var cursor = 0L
        for (seg in list) {
            if (seg.start < cursor) throw RvzFormatException("Overlapping data at ${seg.start}")
            if (seg.start > cursor) out += ZeroSegment(cursor, seg.start)
            out += seg
            cursor = seg.end
        }
        if (cursor > isoSize) throw RvzFormatException("Data past the end of the disc")
        if (cursor < isoSize) out += ZeroSegment(cursor, isoSize)
        return out
    }

    /**
     * Decodes everything; [sink] gets the ISO bytes in order (may be null when only the digest
     * matters).
     */
    fun decode(
        threads: Int = RvzOptions.defaultThreads(),
        sink: ((ByteArray, Int, Int) -> Unit)? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): DiscDigest {
        val segs = segments()
        var done = 0L
        DigestStream().use { digest ->
            OrderedPipeline<ByteArray>(threads, threads + 1) { bytes ->
                digest.update(bytes, 0, bytes.size)
                sink?.invoke(bytes, 0, bytes.size)
                done += bytes.size
                onProgress(done, isoSize)
            }.use { pipeline ->
                for (seg in segs) {
                    if (isCancelled()) throw RvzCancelledException()
                    when (seg) {
                        is ZeroSegment -> {
                            var p = seg.start
                            while (p < seg.end) {
                                val n = minOf(0x200000L, seg.end - p).toInt()
                                pipeline.submit { ByteArray(n) }
                                p += n
                            }
                        }
                        else -> pipeline.submit { decodeSegment(seg) }
                    }
                }
                pipeline.drain()
            }
            return digest.finish()
        }
    }

    private fun decodeSegment(seg: Segment): ByteArray = when (seg) {
        is RawSegment -> {
            val size = (seg.end - seg.groupStart).toInt()
            val data = decodeGroup(seg.group, size, seg.groupStart, partitionData = false).first
            if (seg.groupStart == 0L) System.arraycopy(disc.discHead, 0, data, 0, minOf(RvzFormat.DISC_HEAD_SIZE, data.size))
            val skip = (seg.start - seg.groupStart).toInt()
            if (skip == 0) data else data.copyOfRange(skip, data.size)
        }
        is WiiSegment -> decodeWii(seg)
        is ZeroSegment -> ByteArray((seg.end - seg.start).toInt())
    }

    private fun decodeWii(seg: WiiSegment): ByteArray {
        val perChunk = disc.chunkSize / WiiCrypto.SECTOR
        val data = ByteArray(seg.sectors * WiiCrypto.DATA_SIZE)
        val hashes = ByteArray(WiiCrypto.GROUP_SECTORS * WiiCrypto.HASH_SIZE)
        val crypto = WiiCrypto(seg.partition.key)
        val firstDataSector = seg.partition.data[0].firstSector
        val lists = ArrayList<Pair<Int, ByteArray>>() // (first sector in hash group, exception list)
        var s0 = 0
        while (s0 < seg.sectors) {
            val n = minOf(perChunk, seg.sectors - s0)
            val group = seg.pd.groupIndex + (seg.firstSector + s0 - seg.pd.firstSector) / perChunk
            val sectionOffset = (seg.firstSector + s0 - firstDataSector).toLong() * WiiCrypto.DATA_SIZE
            val (bytes, exceptions) = decodeGroup(group, n * WiiCrypto.DATA_SIZE, sectionOffset, partitionData = true)
            System.arraycopy(bytes, 0, data, s0 * WiiCrypto.DATA_SIZE, bytes.size)
            if (exceptions != null) lists += s0 to exceptions
            s0 += n
        }
        crypto.hashGroup(data, seg.sectors, hashes)
        for ((first, list) in lists) {
            val count = ((list[0].toInt() and 0xFF) shl 8) or (list[1].toInt() and 0xFF)
            for (i in 0 until count) {
                val p = 2 + i * RvzFormat.EXCEPTION_SIZE
                val offset = ((list[p].toInt() and 0xFF) shl 8) or (list[p + 1].toInt() and 0xFF)
                val target = first * WiiCrypto.HASH_SIZE + offset
                if (offset % WiiCrypto.HASH_SIZE > WiiCrypto.HASH_SIZE - 20 || target + 20 > hashes.size) {
                    throw RvzFormatException("Hash exception out of range")
                }
                System.arraycopy(list, p + 2, hashes, target, 20)
            }
        }
        val encrypted = disc.discHead[0x61].toInt() == 0
        val out = ByteArray(seg.sectors * WiiCrypto.SECTOR)
        for (s in 0 until seg.sectors) {
            val o = s * WiiCrypto.SECTOR
            System.arraycopy(hashes, s * WiiCrypto.HASH_SIZE, out, o, WiiCrypto.HASH_SIZE)
            System.arraycopy(data, s * WiiCrypto.DATA_SIZE, out, o + WiiCrypto.HASH_SIZE, WiiCrypto.DATA_SIZE)
            if (encrypted) crypto.encryptSector(out, o, out, o)
        }
        return out
    }

    /** Returns the group's [size] bytes and, for partition data, its exception list. */
    private fun decodeGroup(index: Int, size: Int, sectionOffset: Long, partitionData: Boolean): Pair<ByteArray, ByteArray?> {
        if (index !in groups.indices) throw RvzFormatException("Missing group $index")
        val g = groups[index]
        if (g.dataSize == 0) return ByteArray(size) to (if (partitionData) ByteArray(2) else null)
        if (g.fileOffset + g.dataSize > channel.size()) throw RvzFormatException("Group $index outside the file")
        val stored = ByteArray(g.dataSize)
        channel.readFully(g.fileOffset, stored, 0, stored.size)

        var buf = stored
        var pos = 0
        var exceptions: ByteArray? = null
        fun readExceptions(align: Boolean) {
            if (buf.size < pos + 2) throw RvzFormatException("Truncated exception list")
            val count = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
            val len = 2 + count * RvzFormat.EXCEPTION_SIZE
            if (buf.size < pos + len) throw RvzFormatException("Truncated exception list")
            exceptions = buf.copyOfRange(pos, pos + len)
            pos += len
            if (align) pos = (pos + 3) and 3.inv()
        }
        val compressed = g.compressed && disc.compression != RvzFormat.COMPRESSION_NONE
        if (partitionData && !compressed) readExceptions(align = true)
        if (compressed) {
            val max = maxOf(size, g.packedSize) + (if (partitionData) 2 + 3328 * RvzFormat.EXCEPTION_SIZE else 0)
            buf = codec.decompress(stored, 0, stored.size, max)
            pos = 0
            if (partitionData) readExceptions(align = false)
        }
        val out = ByteArray(size)
        if (g.packedSize > 0) {
            if (pos + g.packedSize > buf.size) throw RvzFormatException("Truncated packed data")
            RvzPacking.unpack(buf, pos, g.packedSize, out, size, sectionOffset)
        } else {
            if (pos + size > buf.size) throw RvzFormatException("Group $index is shorter than expected")
            System.arraycopy(buf, pos, out, 0, size)
        }
        return out to exceptions
    }

    override fun close() {
        codec.close()
    }

    private companion object {
        const val SECTOR = 0x8000L
    }
}
