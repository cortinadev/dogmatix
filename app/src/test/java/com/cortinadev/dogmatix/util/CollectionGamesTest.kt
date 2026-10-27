package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.CollectionFixtures.PSX
import com.cortinadev.dogmatix.util.CollectionFixtures.file
import com.cortinadev.dogmatix.util.CollectionFixtures.row
import com.cortinadev.dogmatix.util.CollectionFixtures.snapshot
import com.cortinadev.dogmatix.util.CollectionFixtures.GBA
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionGamesTest {

    private fun psx(path: String, size: Long = 1_000) = file(path, size, console = PSX, top = "psx")

    @Test
    fun `a sheet and its tracks are one game named without the track`() {
        val games = CollectionGames.group(listOf(
            psx("Ridge Racer (USA).cue", 100),
            psx("Ridge Racer (USA) (Track 1).bin", 1_000),
            psx("Ridge Racer (USA) (Track 2).bin", 2_000)
        ))
        assertEquals(1, games.size)
        assertEquals("Ridge Racer (USA)", games[0].title)
        assertEquals(3_100, games[0].size)
        assertEquals("ridge racer", games[0].groupKey)
        assertEquals("Ridge Racer (USA) (Track 2).bin", games[0].primary.name)
    }

    @Test
    fun `the tracks a sheet names are its game, whatever they are called`() {
        val dc = { path: String, size: Long, refs: List<String>? -> file(path, size, console = "sega_dreamcast", top = "dc", refs = refs) }
        val games = CollectionGames.group(listOf(
            dc("Shenmue II (Europe)/track01.bin", 1_000, null),
            dc("Shenmue II (Europe)/Shenmue II (Europe).gdi", 90, listOf("track01.bin", "track02.raw", "TRACK03.BIN", "missing.bin")),
            dc("Shenmue II (Europe)/track02.raw", 2_000, null),
            dc("Shenmue II (Europe)/track03.bin", 3_000, null),
            dc("Shenmue II (Europe)/notes.txt", 10, null)
        ))
        val game = games.single()
        assertEquals("Shenmue II (Europe)", game.title)
        assertEquals("shenmue ii", game.groupKey)
        assertEquals(4, game.files.size)
        assertEquals(6_090, game.size)
    }

    @Test
    fun `a track the sheets of two games name stays on its own`() {
        val games = CollectionGames.group(listOf(
            psx("Alpha.cue", 100).copy(references = listOf("Alpha.bin", "shared.bin")),
            psx("Alpha.bin", 1_000),
            psx("Beta.cue", 100).copy(references = listOf("Beta.bin", "shared.bin")),
            psx("Beta.bin", 1_000),
            psx("shared.bin", 500)
        )).sortedBy { it.title }
        assertEquals(listOf("Alpha", "Beta", "shared"), games.map { it.title })
        assertEquals(listOf(2, 2, 1), games.map { it.files.size })
    }

    @Test
    fun `the discs of a release are one game, a set folder another`() {
        val games = CollectionGames.group(listOf(
            psx("Final Fantasy VII (USA) (Disc 1).cue"),
            psx("Final Fantasy VII (USA) (Disc 2).cue"),
            psx("Parasite Eve (USA).m3u/Parasite Eve (USA) (Disc 1).chd"),
            psx("Parasite Eve (USA).m3u/Parasite Eve (USA) (Disc 2).chd"),
            psx("Parasite Eve (USA).m3u/Parasite Eve (USA).m3u", 80)
        )).sortedBy { it.title }
        assertEquals(listOf("Final Fantasy VII (USA)", "Parasite Eve (USA)"), games.map { it.title })
        assertEquals(2, games[0].files.size)
        assertEquals(null, games[0].setFolderUri)
        assertEquals(3, games[1].files.size)
        assertEquals("root/psx/Parasite Eve (USA).m3u", games[1].setFolderUri)
        assertEquals("parasite eve", games[1].groupKey)
    }

    @Test
    fun `artwork, saves, shortcuts and lone playlists are not games`() {
        val games = CollectionGames.group(listOf(
            file("Metroid Fusion (USA).gba"),
            file("Metroid Fusion (USA).png"),
            file("Metroid Fusion (USA).sav"),
            file("Metroid Fusion (USA).state1"),
            file("★ Search for more games....dgmtx"),
            file("Old list.m3u")
        ))
        assertEquals(1, games.size)
        assertEquals(1, games[0].files.size)
        assertFalse(CollectionGames.isGameFile(".hidden.gba"))
        assertTrue(CollectionGames.isGameFile("README"))
    }

    @Test
    fun `an archive next to what it unpacked to is part of the same game`() {
        val games = CollectionGames.group(listOf(file("Golden Sun (USA).zip", 5_000), file("Golden Sun (USA).gba", 8_000)))
        assertEquals(1, games.size)
        assertEquals(13_000, games[0].size)
    }

    @Test
    fun `stats count games, files, bytes and catalogue coverage per console`() {
        val files = listOf(
            file("Golden Sun (USA).gba", 8_000),
            file("Golden Sun (Europe).gba", 8_000),
            file("Metroid Fusion (USA).gba", 4_000),
            file("Metroid Fusion (USA).png", 10),
            psx("Ridge Racer (USA).cue", 100),
            psx("Ridge Racer (USA).bin", 30_000),
            file("Loose.zip", 50, console = null, top = "")
        )
        val snap = snapshot(
            files,
            catalog = listOf(row(1, "Golden Sun (USA).zip"), row(2, "Metroid Fusion (USA).zip")),
            catalogGames = mapOf(GBA to 10, PSX to 5)
        )
        val games = CollectionGames.group(files)
        val usage = CollectionStats.byConsole(snap, games)
        assertEquals(listOf(PSX, GBA, null), usage.map { it.consoleId })
        val gba = usage[1]
        assertEquals(2, gba.games)
        assertEquals(3, gba.files) // the artwork is not counted
        assertEquals(20_000, gba.bytes)
        assertEquals(10, gba.catalogGames)
        assertEquals(2, gba.ownedCatalogGames)
        assertEquals(0, usage[0].ownedCatalogGames)
        assertEquals(4, CollectionStats.gameCount(games))
        assertEquals("Ridge Racer (USA)", CollectionStats.biggest(games, 1).single().title)
    }

    @Test
    fun `regions and languages, implied by the region when none is stated`() {
        val games = CollectionGames.group(listOf(
            file("A (USA).gba"), file("B (Europe) (En,Fr,De).gba"), file("C (Japan).gba"), file("D (USA, Europe).gba"), file("E.gba")
        ))
        val regions = CollectionStats.regions(games).associate { it.tag to it.games }
        assertEquals(mapOf("USA" to 2, "EUROPE" to 2, "JAPAN" to 1), regions)
        val languages = CollectionStats.languages(games).associate { it.tag to it.games }
        assertEquals(3, languages["EN"])
        assertEquals(1, languages["FR"])
        assertEquals(1, languages["JA"])
        assertEquals(0.25f, CollectionStats.fraction(1, 4))
        assertEquals(0f, CollectionStats.fraction(1, 0))
    }
}
