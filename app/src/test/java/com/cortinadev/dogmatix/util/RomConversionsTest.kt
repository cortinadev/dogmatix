package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class RomConversionsTest {

    private fun format(consoleId: String?, file: String, folder: String? = null) = RomConversions.formatFor(consoleId, folder, file)

    @Test
    fun `original xbox isos become xiso, the 360 never`() {
        assertEquals(ConversionFormat.XISO, format("microsoft_xbox", "Halo (USA).iso"))
        assertEquals(ConversionFormat.XISO, format("microsoft_xbox", "Halo (USA).ISO"))
        assertEquals(ConversionFormat.XISO, format(null, "Halo (USA).iso", folder = "xbox"))
        assertNull(format("microsoft_xbox_360", "Halo 3 (USA).iso"))
        assertNull(format(null, "Halo 3 (USA).iso", folder = "xbox360"))
        // An Xbox console filed in a 360 folder: unsure, so nothing.
        assertNull(format("microsoft_xbox", "Halo (USA).iso", folder = "Xbox 360"))
        assertNull(format("microsoft_xbox", "Halo (USA).cue"))
        assertNull(format("microsoft_xbox", "Halo (USA).zip"))
    }

    @Test
    fun `disc systems become chd in cd or dvd mode`() {
        assertEquals(ConversionFormat.CHD, format(null, "Game (USA).cue", folder = "psx"))
        assertEquals(ConversionFormat.CHD, format(null, "Game (USA).cue", folder = "psx"))
        assertEquals(ConversionFormat.CHD, format("sega_saturn", "Game (Japan).cue"))
        assertEquals(ConversionFormat.CHD, format("sega_dreamcast", "Game (USA).gdi"))
        assertEquals(ConversionFormat.CHD, format("sega_cd", "Game (USA).cue"))
        assertEquals(ConversionFormat.CHD, format("nec_pc_engine_cd", "Game (Japan).cue"))
        assertEquals(ConversionFormat.CHD, format("sony_playstation_portable", "Game (USA).iso"))
        assertNull(format("sony_playstation", "Game (USA).bin"))
        assertNull(format("nec_pc_engine", "Game (Japan).pce"))

        val ps2 = RomConversions.namesFor("sony_playstation_2")
        // CD mode for PS2 isos: NetherSX2 / AetherSX2 do not open createdvd CHDs.
        assertEquals(ChdMode.CD, RomConversions.chdMode(ps2, "Game (USA).iso"))
        assertEquals(ChdMode.CD, RomConversions.chdMode(ps2, "Game (USA).cue"))
        assertEquals(ChdMode.DVD, RomConversions.chdMode(RomConversions.namesFor("sony_playstation_portable"), "Game.iso"))
        assertEquals(ChdMode.CD, RomConversions.chdMode(RomConversions.namesFor("sony_playstation"), "Game.cue"))
        assertNull(RomConversions.chdMode(RomConversions.namesFor("microsoft_xbox"), "Game.iso"))
    }

    @Test
    fun `gamecube and wii become rvz, the wii u nothing`() {
        assertEquals(ConversionFormat.RVZ, format("nintendo_gamecube", "Game (USA).iso"))
        assertEquals(ConversionFormat.RVZ, format("nintendo_gamecube", "Game (USA).gcm"))
        assertEquals(ConversionFormat.RVZ, format("nintendo_wii", "Game (Europe).wbfs"))
        assertNull(format("nintendo_wii_u", "Game (Europe).wbfs"))
        assertNull(format("nintendo_super_nintendo_entertainment_system", "Game (USA).sfc"))
        assertNull(format(null, "Game (USA).iso"))
    }

    @Test
    fun `an archive row hints at the format of what it unpacks`() {
        val xbox = RomConversions.namesFor("microsoft_xbox")
        assertEquals(ConversionFormat.XISO, RomConversions.likelyFormat(xbox, "Halo (USA).zip"))
        assertEquals(ConversionFormat.XISO, RomConversions.likelyFormat(xbox, "Halo (USA).iso"))
        assertEquals(ConversionFormat.CHD, RomConversions.likelyFormat(RomConversions.namesFor("sony_playstation"), "Game.7z"))
        assertNull(RomConversions.likelyFormat(RomConversions.namesFor("nintendo_gameboy"), "Game.zip"))
        assertNull(RomConversions.likelyFormat(xbox, "Halo (USA).nfo"))
    }

    @Test
    fun `output names`() {
        assertEquals("Halo (USA).iso", RomConversions.outputName(ConversionFormat.XISO, "Halo (USA).iso"))
        assertEquals("Game (USA) (Disc 1).chd", RomConversions.outputName(ConversionFormat.CHD, "Game (USA) (Disc 1).cue"))
        assertEquals("Game (USA).rvz", RomConversions.outputName(ConversionFormat.RVZ, "Game (USA).wbfs"))
    }

    @Test
    fun `saved bytes`() {
        assertEquals(3_000L, RomConversions.savedBytes(10_000, 7_000))
        assertEquals(0L, RomConversions.savedBytes(10_000, 12_000))
        assertNull(RomConversions.savedBytes(10_000, null))
    }

    @Test
    fun `a conversion that saves nothing is not kept`() {
        // cue + bins (or gdi + tracks) count as one input.
        val input = 1_024L + 600_000_000L + 40_000_000L
        // At least 5 % of the input, or the original stays.
        assertTrue(RomConversions.savesSpace(1_000, 950))
        assertFalse(RomConversions.savesSpace(1_000, 951))
        assertTrue(RomConversions.savesSpace(input, input / 2))
        assertFalse(RomConversions.savesSpace(input, input - 1))
        assertFalse(RomConversions.savesSpace(input, input))
        assertFalse(RomConversions.savesSpace(0, 0))
        assertFalse(RomConversions.savesSpace(input, input + 4_096))
        // Size not known: not judged.
        assertTrue(RomConversions.savesSpace(input, -1))
    }

    @Test
    fun `playlists point at the converted disc`() {
        val text = "Game (Disc 1).cue\r\nsub\\Game (Disc 2).CUE\r\n# Game (Disc 1).cue\r\n"
        assertEquals(
            "Game (Disc 1).chd\r\nsub\\Game (Disc 2).CUE\r\n# Game (Disc 1).cue\r\n",
            RomConversions.rewritePlaylist(text, "game (disc 1).cue", "Game (Disc 1).chd")
        )
        assertEquals(
            "Game (Disc 1).cue\nsub\\Game (Disc 2).chd\n",
            RomConversions.rewritePlaylist("Game (Disc 1).cue\nsub\\Game (Disc 2).cue\n", "Game (Disc 2).cue", "Game (Disc 2).chd")
        )
        assertNull(RomConversions.rewritePlaylist("Other.cue\n", "Game.cue", "Game.chd"))
    }

    @Test
    fun `gamecube and wii images become rvz, named with its extension`() {
        assertEquals(ConversionFormat.RVZ, format("nintendo_gamecube", "Metroid Prime (USA).iso"))
        assertEquals(ConversionFormat.RVZ, format(null, "Metroid Prime (USA).gcm", folder = "gc"))
        assertEquals(ConversionFormat.RVZ, format(null, "Mario Kart Wii (Europe).wbfs", folder = "wii"))
        assertEquals("Mario Kart Wii (Europe).rvz", RomConversions.outputName(ConversionFormat.RVZ, "Mario Kart Wii (Europe).wbfs"))
        assertEquals("Metroid Prime (USA) (Disc 1).rvz", RomConversions.outputName(ConversionFormat.RVZ, "Metroid Prime (USA) (Disc 1).iso"))
    }

    @Test
    fun `labels join the formats of a mixed selection once, in order`() {
        assertEquals("RVZ", RomConversions.labels(listOf(ConversionFormat.RVZ, ConversionFormat.RVZ)))
        assertEquals("XISO / RVZ", RomConversions.labels(listOf(ConversionFormat.RVZ, ConversionFormat.XISO)))
        assertEquals("", RomConversions.labels(emptyList()))
    }

    @Test
    fun `an image matches a download by its own title, not by a translated one`() {
        val halo = VersionGrouping.groupKey("Halo")
        assertEquals(true, RomConversions.sameTitle("Halo.iso", halo))
        assertEquals(true, RomConversions.sameTitle("sub/Halo (USA) [!].iso", halo))
        assertEquals(false, RomConversions.sameTitle("Halo 2.iso", halo))
        // A clone list may give both one game key; the title key keeps them apart.
        assertEquals(false, RomConversions.sameTitle("Pokemon Edicion Cristal.iso", VersionGrouping.groupKey("Pokemon Crystal")))
        assertEquals(false, RomConversions.sameTitle("Halo.iso", ""))
    }

    @Test
    fun `only a wbfs loses its redump match`() {
        assertEquals(true, RomConversions.losesDatMatch("Wii Sports (Europe).WBFS"))
        assertEquals(false, RomConversions.losesDatMatch("Wii Sports (Europe).iso"))
    }

    @Test
    fun `small or low-memory devices get two threads, others one per core up to four`() {
        assertEquals(2, RomConversions.threadsFor(cores = 4, lowRam = false))
        assertEquals(2, RomConversions.threadsFor(cores = 2, lowRam = false))
        assertEquals(2, RomConversions.threadsFor(cores = 8, lowRam = true))
        assertEquals(4, RomConversions.threadsFor(cores = 8, lowRam = false))
        assertEquals(4, RomConversions.threadsFor(cores = 6, lowRam = false))
    }

    @Test
    fun `a sheet's tracks are looked for by their leaf name`() {
        val cue = "FILE \".\\\\Game (Track 1).bin\" BINARY\r\n  TRACK 01 MODE2/2352\r\nFILE \"audio/Game (Track 2).bin\" BINARY\r\n"
        assertEquals(setOf("game (track 1).bin", "game (track 2).bin"), RomConversions.trackNames("CUE", cue))
        val gdi = "2\n1 0 4 2352 track01.bin 0\n2 600 0 2352 \"track 02.raw\" 0\n"
        assertEquals(setOf("track01.bin", "track 02.raw"), RomConversions.trackNames("gdi", gdi))
        assertEquals(emptySet<String>(), RomConversions.trackNames("iso", cue))
    }

    @Test
    fun `cd systems convert the sheet or the iso to chd, never a lone track`() {
        assertEquals(ConversionFormat.CHD, format(null, "Game (USA).cue", folder = "psx"))
        assertEquals(ConversionFormat.CHD, format(null, "Game (USA).gdi", folder = "dreamcast"))
        assertNull(format(null, "Game (USA) (Track 1).bin", folder = "psx"))
        assertNull(format(null, "Game (USA).chd", folder = "psx"))
        assertEquals(ChdMode.DVD, RomConversions.chdMode(RomConversions.namesFor(null, "psp"), "Game.iso"))
        assertEquals(ChdMode.CD, RomConversions.chdMode(RomConversions.namesFor(null, "ps2"), "Game.iso"))
    }

    @Test
    fun `a disc is also looked for in the m3u folder of its game`() {
        assertEquals(
            listOf("Beta Quest (USA) (Disc 1).cue", "Beta Quest (USA).m3u/Beta Quest (USA) (Disc 1).cue"),
            RomConversions.placesOf("Beta Quest (USA) (Disc 1).cue")
        )
        // Extracted into a folder of its own: the set folder sits at the console folder, by the leaf.
        assertEquals(
            listOf("Disc 2/Game (Disc 2 of 2).cue", "Game.m3u/Game (Disc 2 of 2).cue"),
            RomConversions.placesOf("Disc 2\\Game (Disc 2 of 2).cue")
        )
        assertEquals(listOf("Halo (USA).iso"), RomConversions.placesOf("Halo (USA).iso"))
        assertEquals(emptyList<String>(), RomConversions.placesOf(""))
        assertEquals("Beta Quest (USA).m3u", RomConversions.discSetFolder("Beta Quest (USA) (Disc 1).zip"))
        assertNull(RomConversions.discSetFolder("Halo (USA).zip"))
    }

    @Test
    fun `a download counts as converted only after it finished`() {
        assertFalse(RomConversions.convertedSince(null, 1_000))
        assertTrue(RomConversions.convertedSince(2_000, 1_000))
        // Downloaded again after the conversion: a fresh, unconverted file.
        assertFalse(RomConversions.convertedSince(1_000, 2_000))
        assertTrue(RomConversions.convertedSince(1_000, null))
    }
}
