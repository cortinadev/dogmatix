package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.PreferredRegion
import com.cortinadev.dogmatix.data.model.VersionPrefs
import com.cortinadev.dogmatix.util.CollectionFixtures.PSX
import com.cortinadev.dogmatix.util.CollectionFixtures.file
import com.cortinadev.dogmatix.util.CollectionFixtures.folder
import com.cortinadev.dogmatix.util.CollectionFixtures.snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the confirmations of Duplicates, Repair and Free up space announce, from the files on disk
 * to [DiscSetCleanup.plan], the way the screens get there: grouped into games, then the copies or
 * findings the user deletes. A game already holds its sheet's tracks and its own playlist, so what
 * a plan takes along or rewrites is only what lies outside the game being deleted.
 */
class CollectionDeletionPlanTest {

    private val spain = VersionPrefs(PreferredRegion.SPAIN, setOf("ES"))
    private val segaCd = "sega_cd"

    private fun psx(path: String, size: Long, hash: String? = null, refs: List<String>? = null, top: String = "psx", console: String = PSX) =
        file(path, size, console = console, top = top, hash = hash, refs = refs)

    /** Duplicates → "Delete suggested": every copy but the kept one, as the screen asks the plan. */
    private fun duplicatesPlan(all: List<DiskFile>): DiscSetCleanup.Plan {
        val groups = CollectionDuplicates.find(CollectionGames.group(all), spain)
        val games = groups.flatMap { it.removable() }
        return DiscSetCleanup.plan(games.flatMap { it.files }, all)
    }

    /** Free up space with every duplicate ticked. */
    private fun freeSpacePlan(all: List<DiskFile>): DiscSetCleanup.Plan {
        val groups = CollectionDuplicates.find(CollectionGames.group(all), spain)
        val candidates = CollectionReclaim.candidates(groups, { it.keep?.id }, emptyList(), null, null)
        return DiscSetCleanup.plan(CollectionReclaim.files(candidates), all)
    }

    /** Repair with every fix ticked (as it opens). */
    private fun repairPlan(all: List<DiskFile>): DiscSetCleanup.Plan {
        val findings = CollectionHealth.check(HealthInput(snapshot(all), consoleIds = listOf(PSX), now = 0))
        return DiscSetCleanup.plan(CollectionRepair.deletions(CollectionRepair.plan(findings)), all)
    }

    @Test
    fun `a set copied to another console goes with its tracks, so nothing is taken along`() {
        // The K56 case: a sheet and two tracks copied from psx/ to segacd/.
        fun set(top: String, console: String) = listOf(
            psx("Juego (USA).cue", 120, refs = listOf("Juego (USA) (Track 1).bin", "Juego (USA) (Track 2).bin"), top = top, console = console),
            psx("Juego (USA) (Track 1).bin", 900_000, hash = "t1", top = top, console = console),
            psx("Juego (USA) (Track 2).bin", 600_000, hash = "t2", top = top, console = console)
        )
        val all = set("segacd", segaCd) + set("psx", PSX)
        val copy = CollectionDuplicates.find(CollectionGames.group(all), spain).single().removable().single()
        // The game is the three files: the confirmation names it with its "3 files".
        assertEquals(3, copy.files.size)
        val plan = duplicatesPlan(all)
        assertEquals(3, plan.delete.size)
        assertEquals(0, plan.extra)
        assertTrue(plan.rewrites.isEmpty())
        // The same with plain track names (`track01.bin`): the sheet still holds them.
        fun plain(top: String, console: String) = listOf(
            psx("Juego (USA).cue", 120, refs = listOf("track01.bin", "track02.bin"), top = top, console = console),
            psx("track01.bin", 900_000, hash = "t1", top = top, console = console),
            psx("track02.bin", 600_000, hash = "t2", top = top, console = console)
        )
        val plainPlan = duplicatesPlan(plain("segacd", segaCd) + plain("psx", PSX))
        assertEquals(3, plainPlan.delete.size)
        assertEquals(0, plainPlan.extra)
    }

    @Test
    fun `a playlist named after the title alone goes with the copy whose discs it lists`() {
        // psx/ holds a multi-disc game, and psx/copia/ the same discs with a playlist that does not
        // share their name (`Juego Cuatro.m3u`): it is no game of its own, so the copy's files do
        // not include it, and deleting the copy empties it.
        val discs = listOf("Juego Cuatro (USA) (Disc 1).chd", "Juego Cuatro (USA) (Disc 2).chd")
        val all = listOf(
            psx(discs[0], 64 * 1024, hash = "d1"),
            psx(discs[1], 64 * 1024, hash = "d2"),
            psx("copia/" + discs[0], 64 * 1024, hash = "d1"),
            psx("copia/" + discs[1], 64 * 1024, hash = "d2"),
            psx("copia/Juego Cuatro.m3u", 60, refs = discs)
        )
        val group = CollectionDuplicates.find(CollectionGames.group(all), spain).single()
        assertEquals(DuplicateKind.SAME_FILE, group.kind)
        assertEquals(listOf("copia/" + discs[0], "copia/" + discs[1]), group.removable().single().files.map { it.path })

        val plan = duplicatesPlan(all)
        assertEquals(listOf("copia/" + discs[0], "copia/" + discs[1], "copia/Juego Cuatro.m3u"), plan.delete.map { it.path })
        // "3 files (including 1 disc tracks and playlists)"
        assertEquals(2, plan.asked)
        assertEquals(1, plan.extra)
        assertTrue(plan.rewrites.isEmpty())
        // Free up space offers the same copy and announces the same.
        val freed = freeSpacePlan(all)
        assertEquals(plan.delete, freed.delete)
        assertEquals(1, freed.extra)
    }

    @Test
    fun `an empty disc of a set is deleted by Repair and its playlist rewritten with the others`() {
        // psx/Juego Tres.m3u/ with disc 2 at 0 bytes: Health finds it, Repair deletes it, and the
        // playlist keeps disc 1 ("1 playlist is rewritten without the deleted discs").
        val discs = listOf("Juego Tres (Disc 1).chd", "Juego Tres (Disc 2).chd")
        val all = listOf(
            psx("Juego Tres.m3u/" + discs[0], 64 * 1024),
            psx("Juego Tres.m3u/" + discs[1], 0),
            psx("Juego Tres.m3u/Juego Tres.m3u", 48, refs = discs)
        )
        val plan = repairPlan(all)
        assertEquals(listOf("Juego Tres.m3u/" + discs[1]), plan.delete.map { it.path })
        assertEquals(0, plan.extra)
        val rewrite = plan.rewrites.single()
        assertEquals("Juego Tres.m3u/Juego Tres.m3u", rewrite.playlist.path)
        assertEquals(discs[0] + "\n", rewrite.content)
    }

    @Test
    fun `a library folder that cannot be read leaves the analysis and shows in Health`() {
        val sd = "sd"
        val all = listOf(
            psx("Juego (USA).chd", 64 * 1024, hash = "h"),
            file("Juego (USA).chd", 64 * 1024, console = PSX, top = "psx", root = sd, hash = "h")
        )
        val roots = listOf(LibraryRoot("root", null, accessible = true, permission = true), LibraryRoot(sd, PSX, accessible = true, permission = true))
        val before = snapshot(all, folders = listOf(folder("", console = PSX, top = "psx", root = sd)), roots = roots)
        assertEquals(1, CollectionDuplicates.find(CollectionGames.group(before.files), spain).size)

        val after = before.withoutRoots(setOf(sd))
        assertEquals(listOf("root"), after.files.map { it.rootUri })
        assertTrue(after.folders.isEmpty())
        assertFalse(after.roots.single { it.uri == sd }.accessible)
        assertTrue(after.roots.single { it.uri == "root" }.accessible)
        // Nothing left to offer from it, and Health names the folder.
        assertTrue(CollectionDuplicates.find(CollectionGames.group(after.files), spain).isEmpty())
        val findings = CollectionHealth.check(HealthInput(after, consoleIds = listOf(PSX), now = 0))
        assertEquals(listOf(sd), findings.filter { it.kind == HealthKind.PERMISSION_LOST }.map { it.subject })

        // A tree the snapshot did not list still reads as one to pick again; none at all changes nothing.
        assertEquals(LibraryRoot("other", null, accessible = false, permission = true), before.withoutRoots(setOf("other")).roots.last())
        assertSame(before, before.withoutRoots(emptySet()))
    }
}
