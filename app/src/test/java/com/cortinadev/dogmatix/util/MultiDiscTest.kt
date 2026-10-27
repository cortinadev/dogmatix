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

    @Test
    fun `sheets as they come from the wild`() {
        // Unquoted with spaces (everything up to the file type), Windows lines, a BOM, another file type.
        assertEquals(
            listOf("Game Track 1.bin", "Disc 1\\track02.wav", ".\\track03.bin"),
            MultiDisc.cueFiles("\uFEFFFILE Game Track 1.bin BINARY\r\n  TRACK 01 MODE1/2352\r\nfile \"Disc 1\\track02.wav\" WAVE\r\nFILE \".\\track03.bin\" BINARY\r\n")
        )
        assertEquals(listOf("noext"), MultiDisc.cueFiles("FILE noext\n"))
        // Blank lines before the track count, quoted and unquoted names.
        assertEquals(listOf("track01.bin", "track 02.raw", "track03.bin"), MultiDisc.gdiFiles("\uFEFF\n3\r\n\r\n1 0 4 2352 track01.bin 0\r\n2 600 0 2352 \"track 02.raw\" 0\r\n3 45000 4 2352 track03.bin 0\r\n"))

        assertEquals("track01.bin", MultiDisc.sheetReference(".\\track01.bin"))
        assertEquals("Disc 1/track01.bin", MultiDisc.sheetReference("\"Disc 1\\track01.bin\""))
        assertEquals("../track01.bin", MultiDisc.sheetReference("../track01.bin"))
        assertNull(MultiDisc.sheetReference("C:\\rips\\track01.bin"))
        assertNull(MultiDisc.sheetReference("/sdcard/track01.bin"))
    }

    @Test
    fun `sheet names match the files whatever their case, and missing ones are only reported`() {
        val loose = listOf("Game (Disc 1).cue", "track01.bin", "Game (Disc 2).cue", "GAME (DISC 2).BIN")
        val refs = mapOf(
            "Game (Disc 1).cue" to listOf(".\\TRACK01.BIN", "track02.bin"),
            "Game (Disc 2).cue" to listOf("Game (Disc 2).bin")
        )
        val plan = MultiDisc.plan("Game", loose, emptyList(), 2) { refs[it].orEmpty() }!!
        assertEquals(setOf("Game (Disc 1).cue", "track01.bin", "Game (Disc 2).cue", "GAME (DISC 2).BIN"), plan.moves.toSet())
        // Not on disk at all: the playlist still goes ahead, the name is reported.
        assertEquals(listOf("track02.bin"), plan.missing)
        // Already inside the set folder is not missing.
        val inside = MultiDisc.plan("Game", loose, listOf("track02.bin"), 2) { refs[it].orEmpty() }!!
        assertTrue(inside.missing.isEmpty())
    }

    @Test
    fun `tracks in another folder keep the set where it is`() {
        val loose = listOf("Game (Disc 1).cue", "Game (Disc 2).cue")
        assertNull(MultiDisc.plan("Game", loose, emptyList(), 2) { if (it.contains("1")) listOf("Disc 1/track01.bin") else emptyList() })
        assertNull(MultiDisc.plan("Game", loose, emptyList(), 2) { if (it.contains("1")) listOf("../track01.bin") else emptyList() })
        // An absolute path is broken wherever the sheet is: it neither moves nor holds the set back.
        assertEquals(2, MultiDisc.plan("Game", loose, emptyList(), 2) { listOf("C:\\rips\\track.bin") }!!.entries.size)
    }

    @Test
    fun `the playlist names only the discs that made it into the folder`() {
        val plan = MultiDisc.plan(ff7, (1..3).map { "$ff7 (Disc $it).chd" }, emptyList(), expectedDiscs = 3)!!
        // Disc 2 could not go in (another file of its name inside was kept apart, a copy did not match…).
        val partial = plan.inFolder(listOf("$ff7 (disc 3).chd", "$ff7 (Disc 1).chd", "readme.txt"))
        assertEquals(listOf("$ff7 (Disc 1).chd", "$ff7 (disc 3).chd"), partial.entries)
        assertEquals("$ff7 (Disc 1).chd\n$ff7 (disc 3).chd\n", partial.content)
        assertEquals(plan.name, partial.name)
        assertTrue(plan.inFolder(emptyList()).entries.isEmpty())
        // A playlist already there covers the set only when it names every disc.
        assertFalse(plan.coveredBy(partial.entries))
        assertTrue(plan.coveredBy(plan.entries.map { it.uppercase() }))
        assertTrue(plan.coveredBy(listOf(".\\$ff7 (Disc 1).chd", "$ff7 (Disc 2).chd", "sub/$ff7 (Disc 3).chd")))
    }

    @Test
    fun `a set cut short is finished by the next arrival`() {
        // Disc 1 made it into the folder before a crash; 2 and 3 (with their tracks) are still loose, no playlist yet.
        val inSet = listOf("$ff7 (Disc 1).cue", "$ff7 (Disc 1).bin")
        val loose = listOf("$ff7 (Disc 2).cue", "$ff7 (Disc 2).bin", "$ff7 (Disc 3).cue", "$ff7 (Disc 3).bin", "$ff7 (Disc 3).bin.dogmatix-part")
        val plan = MultiDisc.plan(ff7, loose, inSet, expectedDiscs = 3)!!
        assertEquals(listOf("$ff7 (Disc 1).cue", "$ff7 (Disc 2).cue", "$ff7 (Disc 3).cue"), plan.entries)
        // Half-copied parts never ride along.
        assertEquals(listOf("$ff7 (Disc 2).cue", "$ff7 (Disc 2).bin", "$ff7 (Disc 3).cue", "$ff7 (Disc 3).bin"), plan.moves)
        // A disc copied in but whose original stayed (same name on both sides): offered again, the
        // service then finds the same file inside and only deletes the loose one.
        val twice = MultiDisc.plan(ff7, loose + "$ff7 (Disc 1).cue", inSet, expectedDiscs = 3)!!
        assertTrue("$ff7 (Disc 1).cue" in twice.moves)
        assertEquals(3, twice.entries.size)
    }
}
