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
}
