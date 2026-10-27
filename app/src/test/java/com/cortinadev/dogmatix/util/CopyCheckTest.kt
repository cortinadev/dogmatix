package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CopyCheckTest {

    private val block = CopyCheck.BLOCK.toLong()

    /** The sampled blocks of [bytes], read the way SafDocs.samples reads a file. */
    private fun samples(bytes: ByteArray, size: Long = bytes.size.toLong()): List<ByteArray> =
        CopyCheck.offsets(size).map { offset ->
            val end = minOf(bytes.size.toLong(), offset + CopyCheck.lengthAt(size, offset)).toInt()
            if (offset >= bytes.size) ByteArray(0) else bytes.copyOfRange(offset.toInt(), end)
        }

    @Test
    fun `small files are compared whole`() {
        assertTrue(CopyCheck.offsets(0).isEmpty())
        assertEquals(listOf(0L), CopyCheck.offsets(100))
        assertEquals(listOf(0L, block, 2 * block), CopyCheck.offsets(3 * block))
        assertEquals((0 until 8).map { it * block }, CopyCheck.offsets(8 * block))
        assertEquals(10, CopyCheck.lengthAt(2 * block + 10, 2 * block))
    }

    @Test
    fun `big files are sampled at the head, the tail and in between`() {
        val size = 4L * 1024 * 1024 * 1024 + 123
        val offsets = CopyCheck.offsets(size)
        assertEquals(CopyCheck.MIDDLE_BLOCKS + 2, offsets.size)
        assertEquals(0L, offsets.first())
        assertEquals(size - block, offsets.last())
        assertTrue(offsets.zipWithNext().all { (a, b) -> b - a >= block })
        // Just over the whole-file limit: still no overlap.
        val tight = 8 * block + 1
        assertTrue(CopyCheck.offsets(tight).zipWithNext().all { (a, b) -> b - a >= block })
    }

    @Test
    fun `verdicts`() {
        val original = Random(7).nextBytes(20 * CopyCheck.BLOCK + 321)
        val size = original.size.toLong()
        assertEquals(CopyCheck.Verdict.SAME, CopyCheck.verdict(size, size, samples(original), samples(original.copyOf())))

        // A byte flipped inside the tail block.
        val torn = original.copyOf().also { it[it.size - 5] = (it[it.size - 5] + 1).toByte() }
        assertEquals(CopyCheck.Verdict.DIFFERENT, CopyCheck.verdict(size, size, samples(original), samples(torn)))

        // Truncated, or of unknown size: never taken for the original.
        assertEquals(CopyCheck.Verdict.SIZE, CopyCheck.verdict(size, size - 1, samples(original), samples(original.copyOf(original.size - 1))))
        assertEquals(CopyCheck.Verdict.SIZE, CopyCheck.verdict(size, null, samples(original), null))
        assertEquals(CopyCheck.Verdict.SIZE, CopyCheck.verdict(-1, -1, emptyList(), emptyList()))
        assertEquals(CopyCheck.Verdict.UNREADABLE, CopyCheck.verdict(size, size, samples(original), null))
    }
}
