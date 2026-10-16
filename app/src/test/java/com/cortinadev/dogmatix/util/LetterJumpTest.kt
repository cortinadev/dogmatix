package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LetterJumpTest {

    @Test
    fun bucketsFollowTheByteOrderOfTheFirstCharacter() {
        assertEquals("0", LetterJump.bucketOf("007 - Agent Under Fire"))
        assertEquals("0", LetterJump.bucketOf("'96 Zenkoku Koukou Soccer"))
        assertEquals("0", LetterJump.bucketOf(""))
        assertEquals("A", LetterJump.bucketOf("Aladdin"))
        assertEquals("Z", LetterJump.bucketOf("Zelda"))
        assertEquals("1", LetterJump.bucketOf("[BIOS] Something"))
        assertEquals("d", LetterJump.bucketOf("de Blob"))
        assertEquals("2", LetterJump.bucketOf("Ōkami"))
        assertEquals('#', LetterJump.letterOf("007"))
        assertEquals('D', LetterJump.letterOf("de Blob"))
        assertEquals('#', LetterJump.letterOf("Éxito"))
    }

    private val counts = mapOf("0" to 3, "A" to 5, "B" to 2, "Z" to 1, "d" to 2, "2" to 1)

    @Test
    fun runsOfAnAscendingList() {
        val index = LetterJump.Index(counts, descending = false)
        assertEquals(
            listOf(
                LetterJump.Run('#', 0, 3), LetterJump.Run('A', 3, 5), LetterJump.Run('B', 8, 2),
                LetterJump.Run('Z', 10, 1), LetterJump.Run('D', 11, 2), LetterJump.Run('#', 13, 1)
            ),
            index.runs
        )
        assertEquals(14, index.size)
        assertEquals(listOf('#', 'A', 'B', 'D', 'Z'), index.letters)
        assertEquals(3, index.firstOf('A'))
        assertEquals(11, index.firstOf('D'))
        assertEquals(0, index.firstOf('#'))
        assertNull(index.firstOf('Q'))
    }

    @Test
    fun nextAndPreviousOverTheWholeList() {
        val index = LetterJump.Index(counts, descending = false)
        assertEquals(3, index.next(0))
        assertEquals(8, index.next(5))
        assertEquals(13, index.next(12))
        assertNull(index.next(13))
        // Back: to the start of the run, then to the previous run.
        assertEquals(3, index.previous(6))
        assertEquals(0, index.previous(3))
        assertNull(index.previous(0))
        assertEquals(0, index.previous(2))
    }

    @Test
    fun descendingReversesTheRuns() {
        val index = LetterJump.Index(mapOf("A" to 2, "B" to 3, "0" to 1), descending = true)
        assertEquals(listOf(LetterJump.Run('B', 0, 3), LetterJump.Run('A', 3, 2), LetterJump.Run('#', 5, 1)), index.runs)
    }

    @Test
    fun hashBucketsWithNothingBetweenMerge() {
        val index = LetterJump.Index(mapOf("0" to 2, "2" to 3), descending = false)
        assertEquals(listOf(LetterJump.Run('#', 0, 5)), index.runs)
        assertEquals(listOf('#'), index.letters)
    }

    @Test
    fun movesOverTheLoadedRows() {
        val letters = listOf('#', '#', 'A', 'A', 'A', 'C', 'D', 'D')
        assertEquals(2, LetterJump.next(letters, 0))
        assertEquals(5, LetterJump.next(letters, 3))
        assertNull(LetterJump.next(letters, 6))
        assertNull(LetterJump.next(letters, 99))
        assertEquals(2, LetterJump.previous(letters, 4))
        assertEquals(0, LetterJump.previous(letters, 2))
        assertEquals(2, LetterJump.previous(letters, 5))
        assertEquals(5, LetterJump.previous(letters, 6))
        assertNull(LetterJump.previous(letters, 0))
        assertEquals(0, LetterJump.previous(letters, 1))
    }
}
