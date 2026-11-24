package com.cortinadev.dogmatix.util.rvz

/** What a disc image is, read from its first bytes. */
enum class DiscKind { GAMECUBE, WII, WBFS, RVZ, WIA, NKIT, OTHER_CONTAINER, UNKNOWN }

/** Where junk (LFG padding) can be expected and with which seed parameters. */
class JunkInfo(val discId: ByteArray, val discNum: Int, val fileEnds: LongArray) {
    /** First file end in `(after, before)`, or -1. [fileEnds] is sorted. */
    fun nextFileEnd(after: Long, before: Long): Long {
        var lo = 0
        var hi = fileEnds.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (fileEnds[mid] <= after) lo = mid + 1 else hi = mid
        }
        return if (lo < fileEnds.size && fileEnds[lo] < before) fileEnds[lo] else -1
    }
}

/** An encrypted Wii partition the engine stores decrypted. Offsets are disc offsets. */
class WiiPartition(
    val offset: Long,
    val dataOffset: Long,
    val dataEnd: Long,
    val key: ByteArray,
    /** Disc sector where the first `wia_part_data_t` ends (management data aligned to 2 MiB). */
    val managementEndSector: Int,
    val junk: JunkInfo,
)

/** The disc as RVZ will describe it. */
class DiscLayout(
    val isWii: Boolean,
    val size: Long,
    val head: ByteArray,
    val partitions: List<WiiPartition>,
    val discJunk: JunkInfo,
    /** Why the Wii partitions are stored raw (null when they are decrypted). */
    val rawReason: String?,
) {
    companion object {
        const val SECTOR = 0x8000L
        private const val GC_MAGIC = 0xC2339F3D.toInt()
        private const val WII_MAGIC = 0x5D1C9EA3

        fun detect(head: ByteArray): DiscKind {
            if (head.size < 0x220) return DiscKind.UNKNOWN
            fun ascii(at: Int, s: String) = s.indices.all { head[at + it] == s[it].code.toByte() }
            return when {
                ascii(0, "RVZ") && head[3].toInt() == 1 -> DiscKind.RVZ
                ascii(0, "WIA") && head[3].toInt() == 1 -> DiscKind.WIA
                ascii(0, "WBFS") -> DiscKind.WBFS
                // CISO, GCZ, TGC
                ascii(0, "CISO") || Lfg.readIntBE(head, 0) == 0x01C00BB1 || Lfg.readIntBE(head, 0) == 0xAE0F38A2.toInt() ->
                    DiscKind.OTHER_CONTAINER
                ascii(0x200, "NKIT") -> DiscKind.NKIT
                Lfg.readIntBE(head, 0x18) == WII_MAGIC -> DiscKind.WII
                Lfg.readIntBE(head, 0x1C) == GC_MAGIC -> DiscKind.GAMECUBE
                else -> DiscKind.UNKNOWN
            }
        }

        /** Reads the layout. Throws [RvzFormatException] when the source is not a GC/Wii disc. */
        fun analyze(source: DiscSource): DiscLayout {
            val head = ByteArray(0x440)
            source.read(0, head, 0, head.size)
            val kind = detect(head)
            if (kind != DiscKind.GAMECUBE && kind != DiscKind.WII) throw RvzFormatException("Not a GameCube or Wii disc ($kind)")
            val isWii = kind == DiscKind.WII
            val discJunk = junkInfo(head, if (isWii) null else fileEnds(head, isWii = false) { pos, out, len -> source.read(pos, out, 0, len) })
            if (!isWii) return DiscLayout(false, source.size, head.copyOf(RvzFormat.DISC_HEAD_SIZE), emptyList(), discJunk, null)

            val (parts, reason) = try {
                wiiPartitions(source) to null
            } catch (e: RvzFormatException) {
                emptyList<WiiPartition>() to e.message
            }
            return DiscLayout(true, source.size, head.copyOf(RvzFormat.DISC_HEAD_SIZE), parts, discJunk, reason)
        }

        private fun junkInfo(head: ByteArray, ends: LongArray?) =
            JunkInfo(head.copyOfRange(0, 4), head[6].toInt() and 0xFF, ends ?: LongArray(0))

        /**
         * File end offsets from the FST (plus the FST's own end). [read] reads that address space
         * (disc for GameCube, decrypted partition data for Wii). Bad data → no ends (junk is then
         * only looked for at sector starts, which still converts losslessly).
         */
        internal fun fileEnds(boot: ByteArray, isWii: Boolean, read: (Long, ByteArray, Int) -> Unit): LongArray {
            val shift = if (isWii) 2 else 0
            val fstOff = (Lfg.readIntBE(boot, 0x424).toLong() and 0xFFFFFFFFL) shl shift
            val fstSize = (Lfg.readIntBE(boot, 0x428).toLong() and 0xFFFFFFFFL) shl shift
            if (fstOff == 0L || fstSize < 12 || fstSize > 64L * 1024 * 1024) return LongArray(0)
            val fst = ByteArray(fstSize.toInt())
            read(fstOff, fst, fst.size)
            if (fst[0].toInt() != 1) return longArrayOf(fstOff + fstSize)
            val entries = Lfg.readIntBE(fst, 8).toLong() and 0xFFFFFFFFL
            if (entries < 1 || entries * 12 > fstSize) return longArrayOf(fstOff + fstSize)
            val ends = ArrayList<Long>(entries.toInt())
            ends += fstOff + fstSize
            for (i in 1 until entries.toInt()) {
                val p = i * 12
                if (fst[p].toInt() != 0) continue // directory
                val off = (Lfg.readIntBE(fst, p + 4).toLong() and 0xFFFFFFFFL) shl shift
                val len = Lfg.readIntBE(fst, p + 8).toLong() and 0xFFFFFFFFL
                ends += off + len
            }
            return ends.distinct().sorted().toLongArray()
        }

        private fun wiiPartitions(source: DiscSource): List<WiiPartition> {
            if ((source.read(0x61) ?: 0) != 0 || (source.read(0x60) ?: 0) != 0) {
                throw RvzFormatException("Disc encryption or hashing is disabled")
            }
            val table = ByteArray(32)
            source.read(0x40000, table, 0, 32)
            val offsets = ArrayList<Long>()
            for (g in 0 until 4) {
                val count = Lfg.readIntBE(table, g * 8).toLong() and 0xFFFFFFFFL
                val at = (Lfg.readIntBE(table, g * 8 + 4).toLong() and 0xFFFFFFFFL) shl 2
                if (count == 0L) continue
                if (count > 64 || at == 0L) throw RvzFormatException("Bad partition table")
                val entries = ByteArray((count * 8).toInt())
                source.read(at, entries, 0, entries.size)
                for (i in 0 until count.toInt()) offsets += (Lfg.readIntBE(entries, i * 8).toLong() and 0xFFFFFFFFL) shl 2
            }
            if (offsets.isEmpty()) throw RvzFormatException("No partitions")
            offsets.sort()
            val result = ArrayList<WiiPartition>()
            var previousEnd = 0x50000L
            for (offset in offsets) {
                if (offset < previousEnd || offset % SECTOR != 0L) throw RvzFormatException("Unexpected partition position")
                val header = ByteArray(0x2C0)
                source.read(offset, header, 0, header.size)
                val dataOffset = offset + ((Lfg.readIntBE(header, 0x2B8).toLong() and 0xFFFFFFFFL) shl 2)
                val dataSize = (Lfg.readIntBE(header, 0x2BC).toLong() and 0xFFFFFFFFL) shl 2
                val dataEnd = dataOffset + dataSize
                if (dataOffset % SECTOR != 0L || dataSize % SECTOR != 0L || dataSize == 0L || dataEnd > source.size) {
                    throw RvzFormatException("Unexpected partition data area")
                }
                val key = WiiCrypto.titleKey(header) ?: throw RvzFormatException("Unknown common key")
                val crypto = WiiCrypto(key)
                val reader = PartitionReader(source, dataOffset, dataEnd, crypto)
                val boot = ByteArray(0x440)
                reader.read(0, boot, boot.size)
                if (Lfg.readIntBE(boot, 0x18) != WII_MAGIC) throw RvzFormatException("Partition key does not decrypt the partition")
                val ends = fileEnds(boot, isWii = true) { pos, out, len -> reader.read(pos, out, len) }
                val fstOff = (Lfg.readIntBE(boot, 0x424).toLong() and 0xFFFFFFFFL) shl 2
                val fstSize = (Lfg.readIntBE(boot, 0x428).toLong() and 0xFFFFFFFFL) shl 2
                val fstEndRaw = (fstOff + fstSize) / WiiCrypto.DATA_SIZE * SECTOR
                val managementEnd = minOf(dataOffset + alignUp(fstEndRaw, 0x200000), dataEnd)
                result += WiiPartition(
                    offset = offset, dataOffset = dataOffset, dataEnd = dataEnd, key = key,
                    managementEndSector = (managementEnd / SECTOR).toInt(),
                    junk = JunkInfo(boot.copyOfRange(0, 4), boot[6].toInt() and 0xFF, ends),
                )
                previousEnd = dataEnd
            }
            return result
        }

        private fun DiscSource.read(position: Long): Int? {
            val b = ByteArray(1)
            read(position, b, 0, 1)
            return b[0].toInt() and 0xFF
        }

        private fun alignUp(v: Long, a: Long) = (v + a - 1) / a * a
    }
}

/** Reads decrypted, hash-less partition data at arbitrary offsets (for headers and the FST). */
internal class PartitionReader(
    private val source: DiscSource,
    private val dataOffset: Long,
    private val dataEnd: Long,
    private val crypto: WiiCrypto,
) {
    private val enc = ByteArray(WiiCrypto.SECTOR)
    private val dec = ByteArray(WiiCrypto.DATA_SIZE)

    fun read(position: Long, out: ByteArray, len: Int) {
        var pos = position
        var o = 0
        while (o < len) {
            val sector = pos / WiiCrypto.DATA_SIZE
            val inSector = (pos % WiiCrypto.DATA_SIZE).toInt()
            val n = minOf(len - o, WiiCrypto.DATA_SIZE - inSector)
            val at = dataOffset + sector * WiiCrypto.SECTOR
            if (at >= dataEnd) {
                out.fill(0, o, o + n)
            } else {
                source.read(at, enc, 0, enc.size)
                crypto.decryptSector(enc, 0, null, 0, dec, 0)
                System.arraycopy(dec, inSector, out, o, n)
            }
            o += n
            pos += n
        }
    }
}
