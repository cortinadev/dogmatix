package com.cortinadev.dogmatix.util.rvz

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * On-disk structures of RVZ (WIA's successor in Dolphin), as described in Dolphin's
 * `docs/WiaAndRvz.md` (CC0-1.0). All integers are big endian.
 */
object RvzFormat {
    val MAGIC = byteArrayOf('R'.code.toByte(), 'V'.code.toByte(), 'Z'.code.toByte(), 1)
    const val VERSION = 0x01000000
    const val VERSION_WRITE_COMPATIBLE = 0x00030000
    const val VERSION_READ_COMPATIBLE = 0x00030000

    const val HEADER_SIZE = 0x48
    const val DISC_SIZE = 0xDC
    const val PART_SIZE = 0x30
    const val RAW_DATA_SIZE = 0x18
    const val GROUP_SIZE = 0x0C
    const val DISC_HEAD_SIZE = 0x80

    const val DISC_TYPE_GC = 1
    const val DISC_TYPE_WII = 2
    const val COMPRESSION_NONE = 0
    const val COMPRESSION_ZSTD = 5

    /** Dolphin's defaults for `dolphin-tool convert -f rvz`: zstd level 5, 128 KiB chunks. */
    const val DEFAULT_LEVEL = 5
    const val DEFAULT_CHUNK = 0x20000

    const val COMPRESSED_BIT = 0x80000000.toInt()
    const val EXCEPTION_SIZE = 22 // u16 offset + 20-byte SHA-1

    fun sha1(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray =
        MessageDigest.getInstance("SHA-1").apply { update(data, offset, length) }.digest()

    /** Valid RVZ chunk sizes: powers of two from 32 KiB up to 2 MiB (this engine's range). */
    fun isValidChunk(size: Int): Boolean = size in 0x8000..0x200000 && (size and (size - 1)) == 0

    data class Header(
        val version: Int,
        val versionCompatible: Int,
        val discStructSize: Int,
        val discHash: ByteArray,
        val isoSize: Long,
        val fileSize: Long,
        val headerHash: ByteArray,
    ) {
        fun toBytes(): ByteArray {
            val b = ByteBuffer.allocate(HEADER_SIZE)
            b.put(MAGIC).putInt(version).putInt(versionCompatible).putInt(discStructSize)
            b.put(discHash).putLong(isoSize).putLong(fileSize)
            val bytes = b.array()
            System.arraycopy(sha1(bytes, 0, HEADER_SIZE - 20), 0, bytes, HEADER_SIZE - 20, 20)
            return bytes
        }

        companion object {
            fun parse(b: ByteArray): Header {
                val bb = ByteBuffer.wrap(b)
                val magic = ByteArray(4).also { bb.get(it) }
                if (!magic.contentEquals(MAGIC)) throw RvzFormatException("Not an RVZ file")
                val h = Header(
                    version = bb.int, versionCompatible = bb.int, discStructSize = bb.int,
                    discHash = ByteArray(20).also { bb.get(it) }, isoSize = bb.long, fileSize = bb.long,
                    headerHash = ByteArray(20).also { bb.get(it) },
                )
                if (!sha1(b, 0, HEADER_SIZE - 20).contentEquals(h.headerHash)) throw RvzFormatException("Header hash mismatch")
                return h
            }
        }
    }

    class Disc(
        var discType: Int = 0,
        var compression: Int = COMPRESSION_ZSTD,
        var level: Int = DEFAULT_LEVEL,
        var chunkSize: Int = DEFAULT_CHUNK,
        var discHead: ByteArray = ByteArray(DISC_HEAD_SIZE),
        var numPartitions: Int = 0,
        var partTypeSize: Int = PART_SIZE,
        var partOffset: Long = 0,
        var partHash: ByteArray = ByteArray(20),
        var numRawData: Int = 0,
        var rawDataOffset: Long = 0,
        var rawDataSize: Int = 0,
        var numGroups: Int = 0,
        var groupOffset: Long = 0,
        var groupSize: Int = 0,
    ) {
        fun toBytes(): ByteArray {
            val b = ByteBuffer.allocate(DISC_SIZE)
            b.putInt(discType).putInt(compression).putInt(level).putInt(chunkSize).put(discHead)
            b.putInt(numPartitions).putInt(partTypeSize).putLong(partOffset).put(partHash)
            b.putInt(numRawData).putLong(rawDataOffset).putInt(rawDataSize)
            b.putInt(numGroups).putLong(groupOffset).putInt(groupSize)
            // compr_data_len = 0 and 7 bytes of compressor data: none for zstd.
            return b.array()
        }

        companion object {
            fun parse(b: ByteArray): Disc {
                val bb = ByteBuffer.wrap(b)
                return Disc(
                    discType = bb.int, compression = bb.int, level = bb.int, chunkSize = bb.int,
                    discHead = ByteArray(DISC_HEAD_SIZE).also { bb.get(it) },
                    numPartitions = bb.int, partTypeSize = bb.int, partOffset = bb.long,
                    partHash = ByteArray(20).also { bb.get(it) },
                    numRawData = bb.int, rawDataOffset = bb.long, rawDataSize = bb.int,
                    numGroups = bb.int, groupOffset = bb.long, groupSize = bb.int,
                )
            }
        }
    }

    /** `wia_part_data_t`: a run of partition sectors (32 KiB on disc, 31 KiB stored). */
    data class PartData(val firstSector: Int, val numSectors: Int, val groupIndex: Int, val numGroups: Int)

    class Partition(val key: ByteArray, val data: Array<PartData>) {
        fun put(b: ByteBuffer) {
            b.put(key)
            for (pd in data) b.putInt(pd.firstSector).putInt(pd.numSectors).putInt(pd.groupIndex).putInt(pd.numGroups)
        }

        companion object {
            fun get(b: ByteBuffer): Partition {
                val key = ByteArray(16).also { b.get(it) }
                return Partition(key, Array(2) { PartData(b.int, b.int, b.int, b.int) })
            }
        }
    }

    /** `wia_raw_data_t`: disc bytes stored as they are. */
    data class RawData(val offset: Long, val size: Long, val groupIndex: Int, val numGroups: Int) {
        /** Groups start at the offset rounded down to 32 KiB (the first one covers the disc head too). */
        val startOffset: Long get() = offset and 0x7FFFL.inv()
        val endOffset: Long get() = offset + size
    }

    /** `rvz_group_t`. [dataSize] is the stored size without the compressed flag. */
    data class Group(val dataOffset4: Int, val dataSize: Int, val compressed: Boolean, val packedSize: Int) {
        val fileOffset: Long get() = (dataOffset4.toLong() and 0xFFFFFFFFL) * 4
    }

    fun rawDataBytes(list: List<RawData>): ByteArray {
        val b = ByteBuffer.allocate(list.size * RAW_DATA_SIZE)
        for (r in list) b.putLong(r.offset).putLong(r.size).putInt(r.groupIndex).putInt(r.numGroups)
        return b.array()
    }

    fun groupBytes(list: Array<Group>): ByteArray {
        val b = ByteBuffer.allocate(list.size * GROUP_SIZE)
        for (g in list) {
            b.putInt(g.dataOffset4).putInt(g.dataSize or (if (g.compressed) COMPRESSED_BIT else 0)).putInt(g.packedSize)
        }
        return b.array()
    }

    fun parseRawData(b: ByteArray, count: Int): List<RawData> {
        if (b.size < count.toLong() * RAW_DATA_SIZE) throw RvzFormatException("Raw data table too short")
        val bb = ByteBuffer.wrap(b)
        return List(count) { RawData(bb.long, bb.long, bb.int, bb.int) }
    }

    fun parseGroups(b: ByteArray, count: Int): Array<Group> {
        if (b.size < count.toLong() * GROUP_SIZE) throw RvzFormatException("Group table too short")
        val bb = ByteBuffer.wrap(b)
        return Array(count) {
            val off = bb.int
            val size = bb.int
            Group(off, size and COMPRESSED_BIT.inv(), (size and COMPRESSED_BIT) != 0, bb.int)
        }
    }
}

class RvzFormatException(message: String) : Exception(message)
