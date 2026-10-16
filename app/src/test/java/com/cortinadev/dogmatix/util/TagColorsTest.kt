package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tag colour groups and console families: what the library chips are tinted with. */
class TagColorsTest {

    @Test
    fun regionsLanguagesRevisionsAndReleases() {
        assertEquals(TagColorKind.REGION, TagColorKind.of("USA"))
        assertEquals(TagColorKind.REGION, TagColorKind.of("Europe"))
        assertEquals(TagColorKind.LANGUAGE, TagColorKind.of("En"))
        assertEquals(TagColorKind.LANGUAGE, TagColorKind.of("es"))
        assertEquals(TagColorKind.REVISION, TagColorKind.of("Rev 1"))
        assertEquals(TagColorKind.REVISION, TagColorKind.of("Rev A"))
        assertEquals(TagColorKind.REVISION, TagColorKind.of("v1.1"))
        assertEquals(TagColorKind.REVISION, TagColorKind.of("Version 2.0"))
        assertEquals(TagColorKind.RELEASE, TagColorKind.of("Beta"))
        assertEquals(TagColorKind.RELEASE, TagColorKind.of("Proto 2"))
        assertEquals(TagColorKind.RELEASE, TagColorKind.of("Prototype"))
        assertEquals(TagColorKind.RELEASE, TagColorKind.of("Demo"))
        assertEquals(TagColorKind.RELEASE, TagColorKind.of("Unl"))
    }

    @Test
    fun neutralTags() {
        assertNull(TagColorKind.of("PAL"))
        assertNull(TagColorKind.of("NTSC-J"))
        assertNull(TagColorKind.of("Alt"))
        assertNull(TagColorKind.of("Bonus Disc"))
        assertNull(TagColorKind.of(""))
    }

    @Test
    fun consoleFamilies() {
        assertEquals(ConsoleFamily.NINTENDO, ConsoleFamily.of("nintendo_snes"))
        assertEquals(ConsoleFamily.NINTENDO, ConsoleFamily.of("super_nintendo_entertainment_system"))
        assertEquals(ConsoleFamily.NINTENDO_HANDHELD, ConsoleFamily.of("nintendo_gameboy_advance"))
        assertEquals(ConsoleFamily.NINTENDO_HANDHELD, ConsoleFamily.of("nintendo_game_boy"))
        assertEquals(ConsoleFamily.NINTENDO_HANDHELD, ConsoleFamily.of("nintendo_3ds"))
        assertEquals(ConsoleFamily.PLAYSTATION, ConsoleFamily.of("sony_psp"))
        assertEquals(ConsoleFamily.SEGA, ConsoleFamily.of("sega_game_gear"))
        assertEquals(ConsoleFamily.XBOX, ConsoleFamily.of("microsoft_xbox_360"))
        assertEquals(ConsoleFamily.COMPUTER, ConsoleFamily.of("microsoft_msx"))
        assertEquals(ConsoleFamily.ARCADE, ConsoleFamily.of("snk_neo_geo_mame"))
        assertEquals(ConsoleFamily.SNK, ConsoleFamily.of("snk_neo_geo_pocket_color"))
        assertEquals(ConsoleFamily.OTHER, ConsoleFamily.of("panasonic_3do"))
    }

    @Test
    fun wordsNeverMatchInsideAPart() {
        // "ds" must not match "dsi_ware"-like parts or "nds" inside "ndsi".
        assertEquals(ConsoleFamily.OTHER, ConsoleFamily.of("acme_dsx"))
        assertEquals(ConsoleFamily.OTHER, ConsoleFamily.of("acme_genesisx"))
    }
}
