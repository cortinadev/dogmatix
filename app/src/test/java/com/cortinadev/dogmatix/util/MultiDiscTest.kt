package com.cortinadev.dogmatix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiDiscTest {

    private val ff7 = "Final Fantasy VII (USA)"

    @Test
    fun `disc tags in the usual spellings`() {
        assertEquals(DiscRef(ff7, 2, null), MultiDisc.discOf("$ff7 (Disc 2).cue"))
        assertEquals(DiscRef("Policenauts (Japan)", 1, 3), MultiDisc.discOf("Policenauts (Japan) (Disk 1 of 3).chd"))
        assertEquals(DiscRef("Riven (Europe) (Rev 1)", 4, null), MultiDisc.discOf("Riven (Europe) (Disc IV) (Rev 1).zip"))
        assertEquals(DiscRef("Game", 1, null), MultiDisc.discOf("roms/psx/Game [CD1].iso"))
        assertEquals(DiscRef(ff7, 2, null), MultiDisc.discOf("$ff7 (Disc 2) (Track 03).bin"))
        assertNull(MultiDisc.discOf("Tetris (World).zip"))
        assertNull(MultiDisc.discOf("Discworld (Europe).cue"))
    }

    @Test
    fun `extensions are only short suffixes`() {
        assertEquals("Super Mario Bros. 3", MultiDisc.stripExtension("Super Mario Bros. 3"))
        assertEquals("Super Mario Bros. 3 (USA)", MultiDisc.stripExtension("Super Mario Bros. 3 (USA).nes"))
        assertEquals("Game (USA)", MultiDisc.withoutDiscTag("Game (USA) (Disc 2)"))
    }

    @Test
    fun `only systems whose emulators read playlists`() {
        assertTrue(MultiDisc.supportsPlaylists(setOf("playstation", "psx")))
        assertTrue(MultiDisc.supportsPlaylists(setOf("segacd")))
        assertFalse(MultiDisc.supportsPlaylists(setOf("playstation2", "ps2")))
        assertFalse(MultiDisc.supportsPlaylists(setOf("xbox")))
    }

    @Test
    fun `complete redump set moves images and tracks into the set folder`() {
        val loose = listOf(
            "$ff7 (Disc 1).cue", "$ff7 (Disc 1).bin",
            "$ff7 (Disc 2).cue", "$ff7 (Disc 2) (Track 1).bin", "$ff7 (Disc 2) (Track 2).bin",
            "$ff7 (Disc 3).cue", "$ff7 (Disc 3).bin",
            // A stray track of another disc number must not ride along with disc 1 (prefix check).
            "$ff7 (Disc 10) (Track 1).bin",
            "Crash Bandicoot (USA).cue", "★ Search for more games....dgmtx"
        )
        val plan = MultiDisc.plan(ff7, loose, inSet = emptyList(), expectedDiscs = 3)!!
        assertEquals("$ff7.m3u", plan.name)
        assertEquals(listOf("$ff7 (Disc 1).cue", "$ff7 (Disc 2).cue", "$ff7 (Disc 3).cue"), plan.entries)
        assertEquals(
            setOf(
                "$ff7 (Disc 1).cue", "$ff7 (Disc 1).bin",
                "$ff7 (Disc 2).cue", "$ff7 (Disc 2) (Track 1).bin", "$ff7 (Disc 2) (Track 2).bin",
                "$ff7 (Disc 3).cue", "$ff7 (Disc 3).bin"
            ),
            plan.moves.toSet()
        )
        assertEquals("$ff7 (Disc 1).cue\n$ff7 (Disc 2).cue\n$ff7 (Disc 3).cue\n", plan.content)
        assertNull(plan.conflictingFile)
        // A disc 10 image means the catalogue was wrong about three discs: wait for 4 to 9.
        assertNull(MultiDisc.plan(ff7, loose + "$ff7 (Disc 10).cue", emptyList(), expectedDiscs = 3))
    }

    @Test
    fun `waits until every disc is there`() {
        val discs12 = listOf("$ff7 (Disc 1).chd", "$ff7 (Disc 2).chd")
        assertNull(MultiDisc.plan(ff7, discs12, emptyList(), expectedDiscs = 3))
        assertNull(MultiDisc.plan(ff7, listOf("$ff7 (Disc 1).chd"), emptyList(), expectedDiscs = 1))
        assertNull(MultiDisc.plan("Policenauts (Japan)", listOf("Policenauts (Japan) (Disc 1 of 3).chd", "Policenauts (Japan) (Disc 2 of 3).chd"), emptyList(), 0))
        // The third disc arrives later: the first two are already inside the folder.
        val late = MultiDisc.plan(ff7, listOf("$ff7 (Disc 3).chd"), inSet = discs12 + "$ff7.m3u", expectedDiscs = 3)!!
        assertEquals(listOf("$ff7 (Disc 1).chd", "$ff7 (Disc 2).chd", "$ff7 (Disc 3).chd"), late.entries)
        assertEquals(listOf("$ff7 (Disc 3).chd"), late.moves)
    }

    @Test
    fun `sheet references and an older playlist in the way`() {
        val loose = listOf("Game (Disc 1).cue", "track01.bin", "Game (Disc 2).cue", "track02.bin", "Game.m3u")
        val refs = mapOf("Game (Disc 1).cue" to listOf("track01.bin"), "Game (Disc 2).cue" to listOf("sub/track02.bin"))
        val plan = MultiDisc.plan("Game", loose, emptyList(), 2) { refs[it].orEmpty() }!!
        assertTrue(plan.moves.containsAll(listOf("track01.bin", "track02.bin")))
        assertFalse("Game.m3u" in plan.moves)
        assertEquals("Game.m3u", plan.conflictingFile)
    }

    @Test
    fun `cue and gdi sheets`() {
        assertEquals(
            listOf("Game (Disc 1) (Track 1).bin", "Game (Disc 1) (Track 2).bin"),
            MultiDisc.cueFiles("FILE \"Game (Disc 1) (Track 1).bin\" BINARY\n  TRACK 01 MODE2/2352\nFILE \"Game (Disc 1) (Track 2).bin\" BINARY\n")
        )
        assertEquals(listOf("track01.bin", "track 02.raw"), MultiDisc.gdiFiles("2\n1 0 4 2352 track01.bin 0\n2 600 0 2352 \"track 02.raw\" 0\n"))
    }
}
