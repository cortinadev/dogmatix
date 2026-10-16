package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibretroDatsTest {

    private val base = "https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/"

    @Test
    fun `cartridges from no-intro, discs from redump, psp from both`() {
        assertEquals(
            listOf(LibretroDats.Collection.NO_INTRO to base + "no-intro/Nintendo%20-%20Game%20Boy%20Advance.dat"),
            LibretroDats.sourcesFor("nintendo_gba")
        )
        assertEquals(
            listOf(LibretroDats.Collection.REDUMP to base + "redump/Sega%20-%20Mega-CD%20-%20Sega%20CD.dat"),
            LibretroDats.sourcesFor("sega_cd")
        )
        assertEquals(listOf(LibretroDats.Collection.REDUMP, LibretroDats.Collection.NO_INTRO), LibretroDats.sourcesFor("sony_psp").map { it.first })
        assertTrue(LibretroDats.sourcesFor("nintendo_switch").isEmpty())
    }

    @Test
    fun `an imported dat finds its console by its header name`() {
        assertEquals("Nintendo_-_Game_Boy_Advance", LibretroDats.systemForDatName("Nintendo - Game Boy Advance (Parent-Clone)")?.repo)
        assertEquals("Sony_-_PlayStation", LibretroDats.systemForDatName("Sony - PlayStation")?.repo)
        assertNull(LibretroDats.systemForDatName("Some homebrew pack"))
        val consoles = listOf("nintendo_gb", "nintendo_gba", "sony_playstation")
        assertEquals("nintendo_gba", LibretroDats.guessConsole("Nintendo - Game Boy Advance", consoles))
        assertNull(LibretroDats.guessConsole("Nintendo - Game Boy Advance", listOf("sony_playstation")))
    }
}
