package com.cortinadev.dogmatix.util.rvz

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.CRC32

data class RvzOptions(
    val chunkSize: Int = RvzFormat.DEFAULT_CHUNK,
    val level: Int = RvzFormat.DEFAULT_LEVEL,
    /** Compression threads; memory in flight is about 6 MiB per thread plus ~10 MiB. */
    val threads: Int = defaultThreads(),
) {
    companion object {
        fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }
}

/** Digests of the plain ISO the RVZ represents, plus the sizes. */
data class DiscDigest(val size: Long, val crc32: Long, val sha1: ByteArray) {
    val sha1Hex: String get() = sha1.joinToString("") { "%02x".format(it) }
    fun sameAs(other: DiscDigest) = size == other.size && crc32 == other.crc32 && sha1.contentEquals(other.sha1)
    override fun equals(other: Any?) = other is DiscDigest && sameAs(other)
    override fun hashCode() = sha1.contentHashCode()
}

/** Hashes ISO bytes in order on its own thread, so hashing overlaps reading and compression. */
internal class DigestStream : AutoCloseable {
    private val crc = CRC32()
    private val sha1 = MessageDigest.getInstance("SHA-1")
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "rvz-digest").apply { isDaemon = true } }
    private var size = 0L
    private val pending = ArrayDeque<java.util.concurrent.Future<*>>()

    /** [data] must not change afterwards. Blocks while 4 buffers are still waiting to be hashed. */
    fun update(data: ByteArray, off: Int, len: Int) {
        size += len
        while (pending.size >= 4) pending.removeFirst().get()
        pending.addLast(exec.submit {
            crc.update(data, off, len)
            sha1.update(data, off, len)
        })
    }

    fun finish(): DiscDigest {
        while (pending.isNotEmpty()) pending.removeFirst().get()
        return DiscDigest(size, crc.value, sha1.digest())
    }

    override fun close() {
        exec.shutdownNow()
    }
}

/**
 * Writes an RVZ for a GameCube or Wii disc, the way `dolphin-tool convert -f rvz` lays it out:
 * header and tables first (reserved, filled in at the end), group data after them. [out] must
 * be seekable; it is truncated to the final size.
 */
class RvzWriter(
    private val source: DiscSource,
    private val layout: DiscLayout,
    private val out: FileChannel,
    private val options: RvzOptions = RvzOptions(),
) {
    private class Payload(val bytes: ByteArray, val dataSize: Int, val compressed: Boolean, val packedSize: Int)

    private class ItemResult(val discLength: Long, val groups: List<Pair<Int, Payload>>)

    fun write(onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }, isCancelled: () -> Boolean = { false }): DiscDigest {
        val plan = RvzPlan.build(layout, options.chunkSize)
        RvzCodec(options.level).use { codec ->
            val groups = arrayOfNulls<RvzFormat.Group>(plan.numGroups)
            val rawTable = RvzFormat.rawDataBytes(plan.rawData)
            val partBytes = ByteBuffer.allocate(plan.partitions.size * RvzFormat.PART_SIZE).also { b -> plan.partitions.forEach { it.put(b) } }.array()
            val tablesStart = RvzFormat.HEADER_SIZE + RvzFormat.DISC_SIZE + partBytes.size
            val dataStart = align4(tablesStart.toLong() + codec.bound(rawTable.size) + codec.bound(plan.numGroups * RvzFormat.GROUP_SIZE))
            var position = dataStart
            var done = 0L

            DigestStream().use { digest ->
                OrderedPipeline<ItemResult>(options.threads, options.threads + 1) { result ->
                    for ((index, p) in result.groups) {
                        groups[index] = RvzFormat.Group((position / 4).toInt(), p.dataSize, p.compressed, p.packedSize)
                        if (p.bytes.isNotEmpty()) {
                            write(position, p.bytes)
                            position += p.bytes.size
                        }
                    }
                    done += result.discLength
                    onProgress(done, layout.size)
                }.use { pipeline ->
                    for (item in plan.items) {
                        if (isCancelled()) throw RvzCancelledException()
                        val buf = ByteArray(item.discLength.toInt())
                        source.read(item.discOffset, buf, 0, buf.size)
                        hashIsoBytes(digest, item, buf)
                        pipeline.submit { process(item, buf, codec) }
                    }
                    pipeline.drain()
                }
                if (isCancelled()) throw RvzCancelledException()
                val iso = digest.finish()

                // Tables and header.
                val groupArray = Array(plan.numGroups) { groups[it] ?: error("Group $it was not written") }
                val rawCompressed = codec.compress(rawTable, 0, rawTable.size)
                val groupTable = RvzFormat.groupBytes(groupArray)
                val groupCompressed = codec.compress(groupTable, 0, groupTable.size)
                val disc = RvzFormat.Disc(
                    discType = if (layout.isWii) RvzFormat.DISC_TYPE_WII else RvzFormat.DISC_TYPE_GC,
                    compression = RvzFormat.COMPRESSION_ZSTD,
                    level = options.level,
                    chunkSize = options.chunkSize,
                    discHead = layout.head,
                    numPartitions = plan.partitions.size,
                    partOffset = (RvzFormat.HEADER_SIZE + RvzFormat.DISC_SIZE).toLong(),
                    partHash = RvzFormat.sha1(partBytes),
                    numRawData = plan.rawData.size,
                    rawDataOffset = tablesStart.toLong(),
                    rawDataSize = rawCompressed.size,
                    numGroups = plan.numGroups,
                    groupOffset = tablesStart.toLong() + rawCompressed.size,
                    groupSize = groupCompressed.size,
                )
                check(disc.groupOffset + groupCompressed.size <= dataStart) { "RVZ tables overflow their reservation" }
                val discBytes = disc.toBytes()
                val header = RvzFormat.Header(
                    RvzFormat.VERSION, RvzFormat.VERSION_WRITE_COMPATIBLE, RvzFormat.DISC_SIZE,
                    RvzFormat.sha1(discBytes), layout.size, position, ByteArray(20),
                ).toBytes()
                val head = ByteArray(dataStart.toInt())
                var p = 0
                for (part in listOf(header, discBytes, partBytes, rawCompressed, groupCompressed)) {
                    System.arraycopy(part, 0, head, p, part.size)
                    p += part.size
                }
                write(0, head)
                try {
                    out.truncate(position)
                    out.force(false)
                } catch (e: java.io.IOException) {
                    throw RvzWriteException(e)
                }
                return iso
            }
        }
    }

    private fun write(position: Long, bytes: ByteArray) {
        try {
            out.writeFully(position, bytes)
        } catch (e: java.io.IOException) {
            throw RvzWriteException(e)
        }
    }

    /** First 0x80 bytes come from the disc head as RVZ readers do; hashes see the real disc. */
    private fun hashIsoBytes(digest: DigestStream, item: RvzPlan.Item, buf: ByteArray) {
        // Raw groups may start before the region (the first one starts at 0 for offset 0x80);
        // regions are contiguous and in order, so only bytes past what was hashed count.
        val skip = (hashed - item.discOffset).coerceAtLeast(0).toInt()
        if (skip < buf.size) digest.update(buf, skip, buf.size - skip)
        hashed = maxOf(hashed, item.discOffset + buf.size)
    }

    private var hashed = 0L

    private fun process(item: RvzPlan.Item, buf: ByteArray, codec: RvzCodec): ItemResult = when (item) {
        is RvzPlan.Raw -> ItemResult(item.discLength, listOf(item.group to encode(buf, buf.size, item.discOffset, item.junk, null, codec)))
        is RvzPlan.WiiHashGroup -> processWii(item, buf, codec)
    }

    private fun processWii(item: RvzPlan.WiiHashGroup, buf: ByteArray, codec: RvzCodec): ItemResult {
        val crypto = WiiCrypto(item.partition.key)
        val sectors = item.sectors
        val data = ByteArray(sectors * WiiCrypto.DATA_SIZE)
        val original = ByteArray(sectors * WiiCrypto.HASH_SIZE)
        for (s in 0 until sectors) {
            crypto.decryptSector(buf, s * WiiCrypto.SECTOR, original, s * WiiCrypto.HASH_SIZE, data, s * WiiCrypto.DATA_SIZE)
        }
        val rebuilt = ByteArray(WiiCrypto.GROUP_SECTORS * WiiCrypto.HASH_SIZE)
        crypto.hashGroup(data, sectors, rebuilt)

        val perChunk = options.chunkSize / WiiCrypto.SECTOR
        val out = ArrayList<Pair<Int, Payload>>()
        var s0 = 0
        var chunk = 0
        while (s0 < sectors) {
            val n = minOf(perChunk, sectors - s0)
            val exceptions = exceptionList(original, rebuilt, s0, n)
            val slice = data.copyOfRange(s0 * WiiCrypto.DATA_SIZE, (s0 + n) * WiiCrypto.DATA_SIZE)
            val sectionOffset = item.partitionDataOffset + s0.toLong() * WiiCrypto.DATA_SIZE
            out += (item.firstGroup + chunk) to encode(slice, slice.size, sectionOffset, item.partition.junk, exceptions, codec)
            s0 += n
            chunk++
        }
        return ItemResult(item.discLength, out)
    }

    /** `wia_except_list_t` for sectors `[first, first + n)` of the hash group. */
    private fun exceptionList(original: ByteArray, rebuilt: ByteArray, first: Int, n: Int): ByteArray {
        val entries = ArrayList<Pair<Int, Int>>() // (offset in list, source offset in original)
        for (s in first until first + n) {
            val base = s * WiiCrypto.HASH_SIZE
            for (slot in WiiCrypto.EXCEPTION_SLOTS) {
                var differs = false
                for (k in 0 until 20) if (original[base + slot + k] != rebuilt[base + slot + k]) { differs = true; break }
                if (differs) entries += ((s - first) * WiiCrypto.HASH_SIZE + slot) to (base + slot)
            }
        }
        val b = ByteBuffer.allocate(2 + entries.size * RvzFormat.EXCEPTION_SIZE)
        b.putShort(entries.size.toShort())
        for ((offset, src) in entries) {
            b.putShort(offset.toShort())
            b.put(original, src, 20)
        }
        return b.array()
    }

    private fun encode(data: ByteArray, len: Int, sectionOffset: Long, junk: JunkInfo, exceptions: ByteArray?, codec: RvzCodec): Payload {
        val excLen = exceptions?.size ?: 0
        if (isZero(data, len) && (exceptions == null || isZero(exceptions, excLen))) return EMPTY
        val packed = RvzPacking.pack(data, len, sectionOffset, junk)
        val body = packed ?: data
        val bodyLen = packed?.size ?: len
        val uncompressedSize = align4(excLen.toLong()).toInt() + bodyLen

        val joined = ByteArray(excLen + bodyLen)
        if (exceptions != null) System.arraycopy(exceptions, 0, joined, 0, excLen)
        System.arraycopy(body, 0, joined, excLen, bodyLen)
        val compressed = codec.compress(joined, 0, joined.size)
        if (align4(compressed.size.toLong()) < uncompressedSize) {
            return Payload(compressed.copyOf(align4(compressed.size.toLong()).toInt()), compressed.size, true, packed?.size ?: 0)
        }
        // Stored as is: exception lists, padding to 4, data, padding to 4.
        val stored = ByteArray(align4(uncompressedSize.toLong()).toInt())
        if (exceptions != null) System.arraycopy(exceptions, 0, stored, 0, excLen)
        System.arraycopy(body, 0, stored, align4(excLen.toLong()).toInt(), bodyLen)
        return Payload(stored, uncompressedSize, false, packed?.size ?: 0)
    }

    private companion object {
        val EMPTY = Payload(ByteArray(0), 0, false, 0)

        fun align4(v: Long) = (v + 3) and 3L.inv()

        fun isZero(b: ByteArray, len: Int): Boolean {
            for (i in 0 until len) if (b[i].toInt() != 0) return false
            return true
        }
    }
}

/** An I/O error on the output (as opposed to reading the source). */
class RvzWriteException(cause: java.io.IOException) : java.io.IOException(cause.message, cause)
