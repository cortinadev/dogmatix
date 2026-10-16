package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RawgCatalogTest {

    @Test
    fun theMostSpecificGenreWins() {
        assertEquals("platformer", RawgCatalog.pickGenre(listOf("Action", "Platformer")))
        assertEquals("action", RawgCatalog.pickGenre(listOf("Action", "Horror")))
        assertEquals("role-playing-games-rpg", RawgCatalog.pickGenre(listOf("Role-Playing")))   // TheGamesDB's name
        assertEquals("platformer", RawgCatalog.pickGenre(listOf("platform")))
        assertNull(RawgCatalog.pickGenre(listOf("Horror", "Stealth")))
        assertNull(RawgCatalog.pickGenre(emptyList()))
    }

    @Test
    fun genreNamesCoverBothDatabases() {
        assertEquals(listOf("Platformer", "Platform"), RawgCatalog.genreNames("platformer"))
        assertEquals(emptyList<String>(), RawgCatalog.genreNames("nope"))
    }

    @Test
    fun consolesMapToRawgPlatforms() {
        assertEquals(79, RawgCatalog.platformFor("nintendo_snes"))
        assertEquals(79, RawgCatalog.platformFor("nintendo_super_nintendo_entertainment_system"))
        assertEquals(49, RawgCatalog.platformFor("nintendo_nes"))
        assertEquals(15, RawgCatalog.platformFor("sony_playstation_2"))
        assertEquals(27, RawgCatalog.platformFor("sony_playstation"))
        assertEquals(14, RawgCatalog.platformFor("microsoft_xbox_360"))
        assertEquals(80, RawgCatalog.platformFor("microsoft_xbox"))
        assertEquals(10, RawgCatalog.platformFor("nintendo_wii_u"))
        assertEquals(11, RawgCatalog.platformFor("nintendo_wii"))
        assertEquals(12, RawgCatalog.platformFor("snk_neo_geo"))
        assertNull(RawgCatalog.platformFor("snk_neo_geo_pocket_color"))
        assertNull(RawgCatalog.platformFor("acme_console"))
    }
}
