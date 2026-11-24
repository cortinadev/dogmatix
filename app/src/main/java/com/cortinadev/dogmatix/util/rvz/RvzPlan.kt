package com.cortinadev.dogmatix.util.rvz

/**
 * How a disc maps onto RVZ tables and groups, and the units of work in disc order:
 * one raw group, or one 2 MiB Wii hash group (64 sectors) that yields several chunks.
 */
class RvzPlan private constructor(
    val chunkSize: Int,
    val partitions: List<RvzFormat.Partition>,
    val rawData: List<RvzFormat.RawData>,
    val numGroups: Int,
    val items: List<Item>,
) {
    sealed class Item {
        abstract val discOffset: Long
        abstract val discLength: Long
    }

    /** A raw group covering `[discOffset, discOffset + discLength)`. */
    class Raw(val group: Int, override val discOffset: Long, override val discLength: Long, val junk: JunkInfo) : Item()

    /**
     * Up to 64 Wii sectors from [firstSector]; chunk `i` of it is group [firstGroup] + i.
     * [partitionDataOffset] is the hash-less offset of [firstSector] inside the partition.
     */
    class WiiHashGroup(
        val partition: WiiPartition,
        val firstGroup: Int,
        val firstSector: Int,
        val sectors: Int,
        val partitionDataOffset: Long,
    ) : Item() {
        override val discOffset: Long get() = firstSector * SECTOR
        override val discLength: Long get() = sectors * SECTOR
    }

    companion object {
        private const val SECTOR = 0x8000L

        fun build(layout: DiscLayout, chunkSize: Int): RvzPlan {
            require(RvzFormat.isValidChunk(chunkSize)) { "Unsupported chunk size $chunkSize" }
            val sectorsPerChunk = chunkSize / SECTOR.toInt()
            val parts = ArrayList<RvzFormat.Partition>()
            val raw = ArrayList<RvzFormat.RawData>()
            val items = ArrayList<Item>()
            var group = 0

            fun addRaw(offset: Long, end: Long) {
                val r = RvzFormat.RawData(offset, end - offset, group, 0)
                val start = r.startOffset
                val groups = if (end <= start) 0 else ((end - start + chunkSize - 1) / chunkSize).toInt()
                raw += r.copy(numGroups = groups)
                for (i in 0 until groups) {
                    val s = start + i.toLong() * chunkSize
                    items += Raw(group + i, s, minOf(chunkSize.toLong(), end - s), layout.discJunk)
                }
                group += groups
            }

            var cursor = RvzFormat.DISC_HEAD_SIZE.toLong()
            for (p in layout.partitions) {
                addRaw(cursor, p.offset)
                addRaw(p.offset, p.dataOffset)
                val dataStart = (p.dataOffset / SECTOR).toInt()
                val dataEnd = (p.dataEnd / SECTOR).toInt()
                val bounds = intArrayOf(dataStart, p.managementEndSector, dataEnd)
                val pds = Array(2) { i ->
                    val first = bounds[i]
                    val count = bounds[i + 1] - first
                    val groups = (count + sectorsPerChunk - 1) / sectorsPerChunk
                    val pd = RvzFormat.PartData(first, count, group, groups)
                    var s = first
                    while (s < first + count) {
                        val n = minOf(64, first + count - s)
                        items += WiiHashGroup(
                            partition = p,
                            firstGroup = group + (s - first) / sectorsPerChunk,
                            firstSector = s,
                            sectors = n,
                            partitionDataOffset = (s - dataStart).toLong() * WiiCrypto.DATA_SIZE,
                        )
                        s += n
                    }
                    group += groups
                    pd
                }
                parts += RvzFormat.Partition(p.key, pds)
                cursor = p.dataEnd
            }
            addRaw(cursor, layout.size)
            return RvzPlan(chunkSize, parts, raw, group, items)
        }
    }
}
