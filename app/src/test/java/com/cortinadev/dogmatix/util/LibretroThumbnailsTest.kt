package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibretroThumbnailsTest {

    private fun repo(consoleId: String) = LibretroThumbnails.systemFor(consoleId)?.repo

    @Test
    fun longestWordPicksTheSystem() {
        assertEquals("Nintendo_-_Game_Boy_Advance", repo("nintendo_gameboy_advance"))
        assertEquals("Nintendo_-_Game_Boy", repo("nintendo_game_boy"))
        assertEquals("Nintendo_-_Game_Boy_Color", repo("nintendo_gbc"))
        assertEquals("Nintendo_-_Super_Nintendo_Entertainment_System", repo("nintendo_super_nintendo_entertainment_system"))
        assertEquals("Nintendo_-_Nintendo_Entertainment_System", repo("nintendo_nes"))
        assertEquals("Sony_-_PlayStation_2", repo("sony_playstation_2"))
        assertEquals("Sony_-_PlayStation_Portable", repo("sony_psp"))
        assertEquals("Sony_-_PlayStation", repo("sony_playstation"))
        assertEquals("NEC_-_PC_Engine_CD_-_TurboGrafx-CD", repo("nec_pc_engine_cd"))
        assertEquals("Sega_-_Mega_Drive_-_Genesis", repo("sega_genesis"))
    }

    @Test
    fun unknownOrExcludedConsolesHaveNoSystem() {
        assertNull(repo("nintendo_switch"))
        assertNull(repo("nintendo_wii_u"))
        assertNull(repo("microsoft_xbox"))
        assertEquals("Nintendo_-_Wii", repo("nintendo_wii"))
    }

    @Test
    fun candidatesGoFromExactToTitleAndRegion() {
        assertEquals(
            listOf("Final Fantasy VII (USA) (Disc 1) (Rev 1)", "Final Fantasy VII (USA) (Rev 1)", "Final Fantasy VII (USA)"),
            LibretroThumbnails.candidates("Final Fantasy VII (USA) (Disc 1) (Rev 1).chd")
        )
        assertEquals(listOf("Tetris (World)"), LibretroThumbnails.candidates("Tetris (World).gb"))
        assertTrue(LibretroThumbnails.candidates("   ").isEmpty())
    }

    @Test
    fun lookupNameIsTheDecodedFileNameNotTheDisplayName() {
        // A web listing keeps the name URL-encoded; the display name has lost the region.
        assertEquals("Castlevania (USA).nes", LibretroThumbnails.lookupName("Castlevania%20%28USA%29.nes", "Castlevania .nes"))
        assertEquals(listOf("Castlevania (USA)"), LibretroThumbnails.candidates(LibretroThumbnails.lookupName("Castlevania%20%28USA%29.nes", "Castlevania .nes")))
        // Torrent rows are already plain: a literal "+" stays, folders go.
        assertEquals("Game + Bonus (USA).zip", LibretroThumbnails.lookupName("Game + Bonus (USA).zip", "Game + Bonus .zip"))
        assertEquals("Halo 2 (USA).zip", LibretroThumbnails.lookupName("Microsoft - Xbox/Halo 2 (USA).zip", "Halo 2 .zip"))
        assertEquals("100% Hits (Europe).zip", LibretroThumbnails.lookupName("100% Hits (Europe).zip", "100% Hits .zip"))
        assertEquals("Tetris .gb", LibretroThumbnails.lookupName("", "Tetris .gb"))
    }

    @Test
    fun forbiddenCharactersBecomeUnderscores() {
        assertEquals("Mario _ Luigi_ Superstar Saga (USA)", LibretroThumbnails.thumbnailName("Mario & Luigi: Superstar Saga (USA)"))
        assertEquals("a_b_c_d", LibretroThumbnails.thumbnailName("a?b*c\"d"))
    }

    @Test
    fun boxartUrlIsEncodedWithSpacesAsPercent20() {
        val gba = LibretroThumbnails.systemFor("nintendo_gba")!!
        assertEquals(
            "https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Game_Boy_Advance/master/Named_Boxarts/Advance%20Wars%20%28USA%29.png",
            LibretroThumbnails.boxartUrl(gba, "Advance Wars (USA)")
        )
    }

    @Test
    fun symlinkStubsAreRecognised() {
        assertEquals("Pokemon - Red Version (USA, Europe)", LibretroThumbnails.symlinkTarget("Pokemon - Red Version (USA, Europe).png".toByteArray()))
        assertEquals("Game (USA)", LibretroThumbnails.symlinkTarget("../Named_Boxarts/Game (USA).png\n".toByteArray()))
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10)
        assertNull(LibretroThumbnails.symlinkTarget(png))
        assertNull(LibretroThumbnails.symlinkTarget("<html>Not found</html>".toByteArray()))
        assertNull(LibretroThumbnails.symlinkTarget(ByteArray(0)))
    }

    @Test
    fun coverPolicyTrustsHitsLongerThanMisses() {
        val now = 100L * 24 * 60 * 60 * 1000
        val fourDays = 4L * 24 * 60 * 60 * 1000
        assertTrue(CoverPolicy.isFresh(found = true, fetchedAt = now - fourDays, now = now))
        assertFalse(CoverPolicy.isFresh(found = false, fetchedAt = now - fourDays, now = now))
        assertFalse(CoverPolicy.isFresh(found = true, fetchedAt = now - CoverPolicy.HIT_TTL_MS, now = now))
        assertFalse(CoverPolicy.isFresh(found = true, fetchedAt = now + 1, now = now))
        assertEquals("sony_psp|Game.iso", CoverPolicy.key("sony_psp", "Game.iso"))
    }

    @Test
    fun otherVersionsSkipRepeatedLookupNamesAndStopAtTheCap() {
        val lookupName = { file: String -> LibretroThumbnails.lookupStem(file, file.substringBefore(" (")) }
        val versions = listOf(
            "Dante's Inferno (Spain).chd",    // the preferred one's name again: not looked up twice
            "Dante's Inferno (Europe).chd",
            "Dante's Inferno (Europe).iso",   // another dump of the same release: same lookup name
            "Dante's Inferno (France).chd",
            "Dante's Inferno (Germany).chd",
            "Dante's Inferno (Italy).chd"     // past the cap
        )
        val picked = CoverPolicy.otherVersions(lookupName("Dante's Inferno (Spain).chd"), versions, max = 3, lookupName = lookupName)
        assertEquals(listOf("Dante's Inferno (Europe).chd", "Dante's Inferno (France).chd", "Dante's Inferno (Germany).chd"), picked)
    }
}
