package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.PreferredRegion
import com.cortinadev.dogmatix.data.model.VersionPrefs
import com.cortinadev.dogmatix.util.CollectionFixtures.GBA
import com.cortinadev.dogmatix.util.CollectionFixtures.SNES
import com.cortinadev.dogmatix.util.CollectionFixtures.file
import com.cortinadev.dogmatix.util.CollectionFixtures.folder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionRepairTest {

    private val empty = file("Empty (USA).gba", 0)
    private val wrong = file("Super Metroid (USA).sfc", 3_000)
    private val zip = file("Golden Sun (USA).zip", 5_000)

    private val findings = listOf(
        HealthFinding("zero:a", HealthKind.ZERO_BYTES, empty.name, GBA, files = listOf(empty)),
        HealthFinding("wrong:a", HealthKind.WRONG_CONSOLE, wrong.name, GBA, files = listOf(wrong), targetConsoleId = SNES),
        HealthFinding("wrong:b", HealthKind.WRONG_CONSOLE, "Nowhere.nes", GBA, files = listOf(file("Nowhere.nes"))),
        HealthFinding("zip:a", HealthKind.EXTRACTED_ARCHIVE, zip.name, GBA, files = listOf(zip), bytes = zip.size),
        HealthFinding("empty:a", HealthKind.EMPTY_FOLDER, "Old", GBA, folder = folder("Old", empty = true)),
        HealthFinding(
            "m3u:a", HealthKind.MISSING_PLAYLIST, "Riven (USA)", GBA,
            playlist = DiscPlaylist("Riven (USA).m3u", listOf("a.cue", "b.cue"), emptyList(), null), playlistParentUri = "root/gba"
        ),
        HealthFinding("ext:a", HealthKind.FOREIGN_EXTENSION, "Setup.exe", GBA),
        HealthFinding("sources", HealthKind.STALE_SOURCES, "", count = 40)
    )

    @Test
    fun `fixable findings become repairs, playlists and moves first`() {
        val actions = CollectionRepair.plan(findings)
        assertEquals(
            listOf(RepairKind.CREATE_PLAYLIST, RepairKind.MOVE_TO_CONSOLE, RepairKind.DELETE_EMPTY_FILE, RepairKind.DELETE_EXTRACTED_ARCHIVE, RepairKind.DELETE_EMPTY_FOLDER),
            actions.map { it.kind }
        )
        // A misplaced file without a console to take it stays a report.
        assertTrue(actions.none { it.id == "wrong:b" })
        assertEquals(5_000, actions.sumOf { it.bytes })
        assertEquals(setOf("wrong:a"), CollectionRepair.idsFor(actions, HealthKind.WRONG_CONSOLE))
        assertEquals(RepairKind.DELETE_EMPTY_FILE to 1, CollectionRepair.counts(actions)[2])
    }

    @Test
    fun `free up space lists every file once, biggest first`() {
        val prefs = VersionPrefs(PreferredRegion.USA, emptySet())
        val games = CollectionGames.group(listOf(file("Big (USA).gba", 9_000), file("Big (Japan).gba", 8_000), file("Tie (Japan).gba", 50), file("Tie (Korea).gba", 50)))
        val duplicates = CollectionDuplicates.find(games, prefs)
        val partial = file("Part (USA).gba.part", 700)
        val healthFindings = listOf(
            HealthFinding("zip:a", HealthKind.EXTRACTED_ARCHIVE, zip.name, GBA, files = listOf(zip)),
            HealthFinding("part:a", HealthKind.PARTIAL_DOWNLOAD, partial.name, GBA, files = listOf(partial)),
            HealthFinding("zero:a", HealthKind.ZERO_BYTES, empty.name, GBA, files = listOf(empty))
        )
        val candidates = CollectionReclaim.candidates(duplicates, { it.keep?.id }, healthFindings, torrentCacheBytes = 20_000, imageCacheBytes = 0)
        assertEquals(
            listOf(ReclaimKind.TORRENT_CACHE, ReclaimKind.DUPLICATE, ReclaimKind.EXTRACTED_ARCHIVE, ReclaimKind.PARTIAL_DOWNLOAD),
            candidates.map { it.kind }
        )
        assertEquals("Big (Japan)", candidates[1].title)
        assertEquals("Big (USA)", candidates[1].kept!!.title)
        assertEquals(28_000, CollectionReclaim.total(candidates, setOf(CollectionReclaim.TORRENT_CACHE_ID, candidates[1].id)))

        // The user's pick in a tie offers the other copy; a torrent in use is not offered.
        val tie = duplicates.first { it.keep == null }
        val picked = CollectionReclaim.candidates(listOf(tie), { tie.games.last().id }, emptyList(), torrentCacheBytes = null, imageCacheBytes = 4_000)
        assertEquals(listOf(ReclaimKind.IMAGE_CACHE, ReclaimKind.DUPLICATE), picked.map { it.kind })
    }
}
