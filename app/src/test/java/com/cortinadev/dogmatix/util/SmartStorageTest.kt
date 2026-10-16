package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.SmartStorage.Console
import com.cortinadev.dogmatix.util.SmartStorage.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartStorageTest {

    private val gb = 1024L * 1024 * 1024

    private val consoles = listOf(
        Console("ps2", Place.MAIN, 40 * gb),
        Console("psx", Place.MAIN, 12 * gb),
        Console("gba", Place.MAIN, 1 * gb),
        Console("nes", Place.MAIN, 100L * 1024 * 1024),
        Console("gc", Place.SECOND, 30 * gb)
    )

    @Test
    fun `nothing is suggested while the internal volume has room`() {
        assertTrue(SmartStorage.suggest(consoles, Place.MAIN, fromFree = 40 * gb, fromTotal = 128 * gb, toFree = 200 * gb).isEmpty())
    }

    @Test
    fun `big consoles go first until the target is met`() {
        // 128 GB → target 19.2 GB free; 2 GB free now: moving PS2 alone is enough.
        val out = SmartStorage.suggest(consoles, Place.MAIN, fromFree = 2 * gb, fromTotal = 128 * gb, toFree = 200 * gb)
        assertEquals(listOf("ps2"), out.map { it.consoleId })
        assertEquals(Place.SECOND, out.single().to)
    }

    @Test
    fun `a console that does not fit on the other side is skipped`() {
        val out = SmartStorage.suggest(consoles, Place.MAIN, fromFree = 2 * gb, fromTotal = 128 * gb, toFree = 20 * gb)
        assertEquals(listOf("psx", "gba"), out.map { it.consoleId })
    }

    @Test
    fun `tiny consoles and unknown space never suggest`() {
        val tiny = listOf(Console("nes", Place.MAIN, 100L * 1024 * 1024))
        assertTrue(SmartStorage.suggest(tiny, Place.MAIN, 1 * gb, 64 * gb, 100 * gb).isEmpty())
        assertTrue(SmartStorage.suggest(consoles, Place.MAIN, null, 64 * gb, 100 * gb).isEmpty())
        assertTrue(SmartStorage.suggest(consoles, Place.MAIN, 1 * gb, 64 * gb, null).isEmpty())
    }

    @Test
    fun `downloads move to the other volume only when theirs is full and the other has room`() {
        assertEquals(Place.MAIN, SmartStorage.downloadPlace(4 * gb, Place.MAIN, ownFree = 10 * gb, otherFree = 100 * gb))
        assertEquals(Place.SECOND, SmartStorage.downloadPlace(4 * gb, Place.MAIN, ownFree = 1 * gb, otherFree = 100 * gb))
        assertEquals(Place.MAIN, SmartStorage.downloadPlace(4 * gb, Place.MAIN, ownFree = 1 * gb, otherFree = 2 * gb))
        assertEquals(Place.MAIN, SmartStorage.downloadPlace(0, Place.MAIN, ownFree = 0, otherFree = 100 * gb))
        assertEquals(Place.SECOND, SmartStorage.downloadPlace(4 * gb, Place.SECOND, ownFree = null, otherFree = 100 * gb))
    }

    @Test
    fun `move rules check sizes and overlaps`() {
        assertTrue(LibraryMoveRules.mayDeleteSource(100, 100))
        assertFalse(LibraryMoveRules.mayDeleteSource(100, 99))
        assertFalse(LibraryMoveRules.mayDeleteSource(100, null))
        assertEquals(LibraryMoveRules.Existing.SAME, LibraryMoveRules.existing(100, 100))
        assertEquals(LibraryMoveRules.Existing.CONFLICT, LibraryMoveRules.existing(100, 50))
        assertTrue(LibraryMoveRules.overlaps("primary:ROMs", "primary:ROMs/new"))
        assertTrue(LibraryMoveRules.overlaps("primary:ROMs/snes", "primary:ROMs"))
        assertFalse(LibraryMoveRules.overlaps("primary:ROMs", "6A3B-BB88:ROMS"))
        assertTrue(LibraryMoveRules.isPart(LibraryMoveRules.partName("Game.iso")))
        assertTrue(LibraryMoveRules.fits(1 * gb, null))
        assertFalse(LibraryMoveRules.fits(1 * gb, 1 * gb))
    }
}
