package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.CollectionFixtures.GBA
import com.cortinadev.dogmatix.util.CollectionFixtures.PS2
import com.cortinadev.dogmatix.util.CollectionFixtures.PSX
import com.cortinadev.dogmatix.util.CollectionFixtures.SNES
import com.cortinadev.dogmatix.util.CollectionFixtures.file
import com.cortinadev.dogmatix.util.CollectionFixtures.folder
import com.cortinadev.dogmatix.util.CollectionFixtures.row
import com.cortinadev.dogmatix.util.CollectionFixtures.snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionHealthTest {

    private val now = 100L * 24 * 60 * 60 * 1000
    private val gib = 1024L * 1024 * 1024

    private fun psx(path: String, size: Long = 1_000, refs: List<String>? = null, catalogId: Long? = null) =
        file(path, size, console = PSX, top = "psx", refs = refs, catalogId = catalogId)

    private fun check(
        files: List<DiskFile>,
        folders: List<DiskFolder> = emptyList(),
        catalog: List<CatalogRow> = emptyList(),
        extensions: Map<String, Set<String>> = emptyMap(),
        downloads: Map<String, String> = emptyMap(),
        multiDisc: Boolean = true
    ): List<HealthFinding> = CollectionHealth.check(
        HealthInput(
            snapshot(files, folders, catalog = catalog, catalogExtensions = extensions),
            consoleIds = listOf(GBA, SNES, PSX, PS2),
            downloads = downloads,
            multiDiscPlaylists = multiDisc,
            now = now
        )
    )

    private fun List<HealthFinding>.of(kind: HealthKind) = filter { it.kind == kind }

    @Test
    fun `lost grants and nearly full volumes`() {
        val roots = listOf(LibraryRoot("tree/a", null, accessible = true, permission = false), LibraryRoot("tree/b", PSX, accessible = true, permission = true))
        assertEquals(listOf("tree/a"), CollectionHealth.permissions(roots).map { it.subject })
        val volumes = listOf(
            VolumeUsage("sd", "SD", true, 64 * gib, gib / 2, hostsLibrary = true),
            VolumeUsage("int", "Internal", false, 128 * gib, 2 * gib, hostsLibrary = true),
            VolumeUsage("usb", "USB", true, 32 * gib, gib / 4, hostsLibrary = false),
            VolumeUsage("big", "Big", true, 1000 * gib, 40 * gib, hostsLibrary = true)
        )
        val low = CollectionHealth.lowSpace(volumes).associate { it.subject to it.severity }
        assertEquals(mapOf("SD" to HealthSeverity.HIGH, "Internal" to HealthSeverity.MEDIUM, "Big" to HealthSeverity.MEDIUM), low)
    }

    @Test
    fun `empty files, unless the storage reports no sizes at all`() {
        val findings = check(listOf(file("Broken (USA).gba", 0), file("Fine (USA).gba", 500), file("Cover.png", 0)))
        assertEquals(listOf("Broken (USA).gba"), findings.of(HealthKind.ZERO_BYTES).map { it.subject })
        val sizeless = (1..12).map { file("Game $it.gba", 0, root = "other") }
        assertTrue(CollectionHealth.zeroBytes(sizeless).isEmpty())
    }

    @Test
    fun `sheets and playlists that point at missing files`() {
        val files = listOf(
            psx("Game (USA).m3u/Game (USA).m3u", 60, refs = listOf("Game (USA) (Disc 1).cue", "Game (USA) (Disc 2).cue")),
            psx("Game (USA).m3u/Game (USA) (Disc 1).cue", 90, refs = listOf("Game (USA) (Disc 1).bin")),
            psx("Game (USA).m3u/Game (USA) (Disc 1).bin", 9_000),
            psx("Other (USA).cue", 90, refs = listOf("C:\\rips\\Other (USA).bin"))
        )
        val broken = CollectionHealth.brokenReferences(files)
        assertEquals(1, broken.size)
        assertEquals("Game (USA).m3u", broken[0].subject)
        assertEquals(listOf("Game (USA) (Disc 2).cue"), broken[0].missing)
        assertEquals("a/b/c.bin", CollectionHealth.resolve("a/b", "./c.bin"))
        assertEquals("a/c.bin", CollectionHealth.resolve("a/b", "..\\c.bin"))
        assertNull(CollectionHealth.resolve("", "../c.bin"))
        assertNull(CollectionHealth.resolve("a/b", "c/d.bin"))
    }

    @Test
    fun `sets missing a disc, by the name or by the catalogue`() {
        val stated = check(listOf(psx("Policenauts (Japan) (Disc 1 of 3).cue"), psx("Policenauts (Japan) (Disc 3 of 3).cue")))
        val set = stated.of(HealthKind.INCOMPLETE_SET).single()
        assertEquals(listOf("2"), set.missing)
        assertEquals(3, set.count)

        val catalog = listOf(
            row(1, "Final Fantasy VII (USA) (Disc 1).zip", PSX), row(2, "Final Fantasy VII (USA) (Disc 2).zip", PSX),
            row(3, "Final Fantasy VII (USA) (Disc 3).zip", PSX), row(4, "Final Fantasy VII (Europe) (Disc 4).zip", PSX)
        )
        val fromCatalog = check(listOf(psx("Final Fantasy VII (USA) (Disc 1).chd"), psx("Final Fantasy VII (USA) (Disc 2).chd")), catalog = catalog)
        assertEquals(listOf("3"), fromCatalog.of(HealthKind.INCOMPLETE_SET).single().missing)
        assertTrue(fromCatalog.of(HealthKind.MISSING_PLAYLIST).isEmpty())
    }

    @Test
    fun `complete sets without a playlist, only where emulators read one`() {
        val discs = listOf(
            psx("Parasite Eve (USA) (Disc 1).cue", 90, refs = listOf("Parasite Eve (USA) (Disc 1).bin")),
            psx("Parasite Eve (USA) (Disc 1).bin", 9_000),
            psx("Parasite Eve (USA) (Disc 2).cue", 90, refs = listOf("Parasite Eve (USA) (Disc 2).bin")),
            psx("Parasite Eve (USA) (Disc 2).bin", 9_000),
            psx("Tekken (USA).cue")
        )
        val finding = check(discs).of(HealthKind.MISSING_PLAYLIST).single()
        assertEquals("Parasite Eve (USA)", finding.subject)
        assertEquals("Parasite Eve (USA).m3u", finding.playlist!!.name)
        assertEquals(listOf("Parasite Eve (USA) (Disc 1).cue", "Parasite Eve (USA) (Disc 2).cue"), finding.playlist!!.entries)
        assertEquals(4, finding.moves.size)
        assertEquals("root/psx", finding.playlistParentUri)
        assertNull(finding.setFolderUri)

        assertTrue(check(discs, multiDisc = false).of(HealthKind.MISSING_PLAYLIST).isEmpty())
        assertTrue(check(discs + psx("Parasite Eve (USA).m3u", 60)).of(HealthKind.MISSING_PLAYLIST).isEmpty())
        val ps2 = discs.map { it.copy(consoleId = PS2, topFolder = "ps2") }
        assertTrue(check(ps2).of(HealthKind.MISSING_PLAYLIST).isEmpty())
    }

    @Test
    fun `a set folder that lost its playlist gets it back in place`() {
        val files = listOf(psx("Parasite Eve (USA).m3u/Parasite Eve (USA) (Disc 1).chd"), psx("Parasite Eve (USA).m3u/Parasite Eve (USA) (Disc 2).chd"))
        val folders = listOf(folder("", PSX, "psx"), folder("Parasite Eve (USA).m3u", PSX, "psx"))
        val finding = check(files, folders).of(HealthKind.MISSING_PLAYLIST).single()
        assertEquals("root/psx/Parasite Eve (USA).m3u", finding.setFolderUri)
        assertTrue(finding.moves.isEmpty())
        assertEquals(2, finding.playlist!!.entries.size)
    }

    @Test
    fun `cartridges in another console's folder, and types a console never lists`() {
        val findings = check(
            listOf(
                file("Super Metroid (USA).sfc", console = GBA),
                file("Pokemon Red (USA).gb", console = GBA),
                file("Setup.exe", console = GBA),
                file("Tetris.gba", console = PSX, top = "psx"),
                file("Readme", console = GBA)
            ),
            extensions = mapOf(GBA to setOf("zip"), PSX to setOf("zip", "chd"))
        )
        val wrong = findings.of(HealthKind.WRONG_CONSOLE).single()
        assertEquals("Super Metroid (USA).sfc", wrong.subject)
        assertEquals(SNES, wrong.targetConsoleId)
        assertEquals(setOf("Setup.exe", "Tetris.gba"), findings.of(HealthKind.FOREIGN_EXTENSION).map { it.subject }.toSet())
        assertEquals(true, CollectionHealth.belongsTo("gb", GBA))
        assertEquals(false, CollectionHealth.belongsTo("gba", SNES))
        assertNull(CollectionHealth.belongsTo("iso", PSX))
    }

    @Test
    fun `what interrupted downloads leave behind`() {
        val hour = 60L * 60 * 1000
        val findings = check(
            listOf(
                file("Old (USA).gba.crdownload", 300, modified = now - 2 * hour),
                file("Fresh (USA).gba.part", 300, modified = now - hour / 2),
                file("Failed (USA).zip", 400, catalogId = 1),
                file("Stopped (USA).zip", 1_000, catalogId = 2),
                file("Paused (USA).zip", 100, catalogId = 3)
            ),
            catalog = listOf(row(1, "Failed (USA).zip", size = 1_000), row(2, "Stopped (USA).zip", size = 1_000), row(3, "Paused (USA).zip", size = 1_000)),
            downloads = mapOf("failed (usa).zip" to "FAILED", "stopped (usa).zip" to "STOPPED", "paused (usa).zip" to "PAUSED")
        )
        assertEquals(setOf("Old (USA).gba.crdownload", "Failed (USA).zip"), findings.of(HealthKind.PARTIAL_DOWNLOAD).map { it.subject }.toSet())
    }

    @Test
    fun `archives next to what they unpacked to`() {
        val files = listOf(
            file("Golden Sun (USA).zip", 5_000),
            file("Golden Sun (USA).gba", 8_000),
            file("Alone (USA).zip"),
            psx("Riven (USA) (Disc 1).zip"),
            psx("Riven (USA) (Disc 2).zip"),
            psx("Riven (USA).m3u/Riven (USA) (Disc 1).chd"),
            psx("Myst (USA).zip"),
            psx("Myst (USA) (Disc 1).cue"),
            file("game.zip", console = null, top = "mame"),
            file("game.chd", console = null, top = "mame")
        )
        val folders = listOf(folder("Riven (USA).m3u", PSX, "psx"))
        val zips = CollectionHealth.extractedArchives(snapshot(files, folders)).map { it.subject }.toSet()
        assertEquals(setOf("Golden Sun (USA).zip", "Riven (USA) (Disc 1).zip", "Myst (USA).zip"), zips)
    }

    @Test
    fun `empty folders inside console folders, and sources not scanned for long`() {
        val folders = listOf(folder("", empty = true), folder("Old", empty = true), folder("Kept", empty = false))
        assertEquals(listOf("Old"), CollectionHealth.emptyFolders(folders).map { it.subject })

        val day = 24L * 60 * 60 * 1000
        val base = HealthInput(snapshot(emptyList()), emptyList(), sourcesConfigured = true, now = now)
        assertEquals(45, CollectionHealth.staleSources(base.copy(lastRescanAt = now - 45 * day))?.count)
        assertNull(CollectionHealth.staleSources(base.copy(lastRescanAt = now - 3 * day)))
        assertEquals(-1, CollectionHealth.staleSources(base)?.count)
        assertNull(CollectionHealth.staleSources(base.copy(snapshot = snapshot(emptyList(), catalogGames = mapOf(GBA to 3)))))
        assertNull(CollectionHealth.staleSources(base.copy(sourcesConfigured = false)))
    }

    @Test
    fun `findings come worst first`() {
        val findings = check(listOf(file("Zero.gba", 0), file("Super Metroid (USA).sfc")), listOf(folder("Empty", empty = true)))
        assertEquals(listOf(HealthKind.ZERO_BYTES, HealthKind.WRONG_CONSOLE, HealthKind.EMPTY_FOLDER), findings.map { it.kind })
    }
}
