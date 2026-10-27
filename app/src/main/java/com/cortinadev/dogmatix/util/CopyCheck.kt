package com.cortinadev.dogmatix.util

/**
 * How a copy is checked before its original is deleted (moves, the trash, multi-disc folders, an
 * unpacked file that may already be there): the same size, and the same bytes in a few sampled
 * blocks (the head, the tail and evenly spaced ones in between), so a torn, shifted or truncated
 * copy is caught without reading both files whole. Small files are compared whole. Pure JVM:
 * reading the blocks is the caller's (see `SafDocs.samples`).
 */
object CopyCheck {
    const val BLOCK = 64 * 1024
    /** Blocks between the head and the tail. */
    const val MIDDLE_BLOCKS = 6

    /** Start of each sampled block of a file of [size] bytes: ascending, never overlapping. */
    fun offsets(size: Long, block: Int = BLOCK, middle: Int = MIDDLE_BLOCKS): List<Long> {
        if (size <= 0) return emptyList()
        val step = block.toLong()
        // Up to this size the whole file is cheaper than any sampling.
        if (size <= step * (middle + 2)) return (0 until size step step).toList()
        val last = size - step
        // Spacing is over a block wide here, so the blocks never overlap.
        val inner = (1..middle).map { i -> last * i / (middle + 1) }
        return (listOf(0L) + inner + last).distinct()
    }

    /** Bytes of the block starting at [offset] in a file of [size] (the last one may be short). */
    fun lengthAt(size: Long, offset: Long, block: Int = BLOCK): Int = minOf(block.toLong(), size - offset).coerceAtLeast(0).toInt()

    enum class Verdict {
        /** Same size, same sampled bytes: the copy stands for the original. */
        SAME,
        /** Not the same size (or a size is unknown): incomplete, or another file. */
        SIZE,
        /** Same size, different bytes somewhere in the samples. */
        DIFFERENT,
        /** The samples of one side could not be read: nothing can be said, so nothing is deleted. */
        UNREADABLE
    }

    /** [source] and [copy] are the blocks read at [offsets] of each (null when they could not be read). */
    fun verdict(sourceSize: Long, copySize: Long?, source: List<ByteArray>?, copy: List<ByteArray>?): Verdict = when {
        sourceSize < 0 || copySize != sourceSize -> Verdict.SIZE
        source == null || copy == null -> Verdict.UNREADABLE
        source.size != copy.size || source.indices.any { !source[it].contentEquals(copy[it]) } -> Verdict.DIFFERENT
        else -> Verdict.SAME
    }
}
