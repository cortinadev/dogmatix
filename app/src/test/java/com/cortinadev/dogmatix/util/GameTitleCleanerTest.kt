package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTitleCleanerTest {

    @Test
    fun stripsExtensionBracketsAndDiscNumbers() {
        assertEquals("Pokemon Emerald Version", GameTitleCleaner.clean("Pokemon Emerald Version.zip"))
        assertEquals("Final Fantasy VII", GameTitleCleaner.clean("Final Fantasy VII (USA) (Disc 1).7z"))
        assertEquals("Super Mario World", GameTitleCleaner.clean("Super Mario World [!].sfc"))
        assertEquals("Metroid Fusion", GameTitleCleaner.clean("Metroid Fusion v1.1"))
    }

    @Test
    fun movesTrailingArticleToTheFront() {
        assertEquals("The Legend of Zelda", GameTitleCleaner.clean("Legend of Zelda, The.nes"))
        assertEquals("A Boy and His Blob", GameTitleCleaner.clean("Boy and His Blob, A"))
    }

    @Test
    fun keepsPlainNames() {
        assertEquals("Sonic the Hedgehog 2", GameTitleCleaner.clean("Sonic the Hedgehog 2"))
        assertEquals("Mega Man X", GameTitleCleaner.clean("  Mega Man   X  "))
    }

    @Test
    fun matchesAcceptsCloseTitlesAndRejectsUnrelatedOnes() {
        assertTrue(GameTitleCleaner.matches("Pokemon Emerald Version", "Pokémon Ruby, Sapphire, Emerald"))
        assertTrue(GameTitleCleaner.matches("Legend of Zelda, The", "The Legend of Zelda"))
        assertTrue(GameTitleCleaner.matches("Sonic the Hedgehog 2", "Sonic The Hedgehog 2 (Genesis)"))
        assertFalse(GameTitleCleaner.matches("'93 Chaoji Hun", "Cheman"))
        assertFalse(GameTitleCleaner.matches("Metroid Fusion", "Metroid Prime"))
    }

    @Test
    fun entryNumbersMustAgree() {
        assertFalse(GameTitleCleaner.matches("Naruto Clash of Ninja 2", "Naruto: Clash of Ninja"))
        assertTrue(GameTitleCleaner.matches("Naruto Clash of Ninja 2", "Naruto: Clash of Ninja 2"))
        assertFalse(GameTitleCleaner.matches("Zelda", "Zelda II: The Adventure of Link"))
        assertTrue(GameTitleCleaner.matches("Final Fantasy VII", "Final Fantasy 7"))
        assertFalse(GameTitleCleaner.matches("Final Fantasy VII", "Final Fantasy VIII"))
        assertTrue(GameTitleCleaner.matches("James Bond 007 Everything or Nothing", "007: Everything or Nothing"))
        // A database's disambiguation in brackets does not count as an entry number.
        assertTrue(GameTitleCleaner.matches("Doom", "DOOM (1993)"))
        assertTrue(GameTitleCleaner.matches("Mega Man X", "Mega Man X"))
    }

    @Test
    fun bestPicksTheClosestHit() {
        val hits = listOf("Naruto: Clash of Ninja", "Naruto: Clash of Ninja 2 Special", "Naruto: Clash of Ninja 2")
        assertEquals("Naruto: Clash of Ninja 2", GameTitleCleaner.best("Naruto Clash of Ninja 2", hits) { it })
        assertEquals(null, GameTitleCleaner.best("Naruto Clash of Ninja 3", hits) { it })
    }
}
