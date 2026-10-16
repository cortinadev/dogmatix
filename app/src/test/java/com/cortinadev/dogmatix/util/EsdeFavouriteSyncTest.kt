package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.EsdeFavouriteSync.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EsdeFavouriteSyncTest {

    private fun gamelist(vararg games: String) =
        "<?xml version=\"1.0\"?>\n<gameList>\n" + games.joinToString("") { "\t<game>\n$it\t</game>\n" } + "</gameList>\n"

    private fun game(path: String, favourite: Boolean? = null, extra: String = "") =
        "\t\t<path>$path</path>\n\t\t<name>${path.removePrefix("./").substringBeforeLast('.')}</name>\n" + extra +
            (favourite?.let { "\t\t<favorite>$it</favorite>\n" } ?: "")

    private fun row(fileName: String, starred: Boolean = false, group: String = VersionGrouping.groupKey(fileName.substringBeforeLast('.'))) =
        Row("nintendo_gba", fileName, group, starred)

    // ---- Reading and editing gamelists ---------------------------------------------------------

    @Test
    fun readsEveryGameWithItsFavourite() {
        val xml = gamelist(
            game("./Advance Wars (USA).gba", favourite = true),
            game("./Golden Sun (USA).gba"),
            game("./Mario &amp; Luigi (USA).gba", favourite = false)
        ).replace("<game>\n\t\t<path>./Golden", "<game id=\"7\">\n\t\t<path>./Golden")
        val favourites = EsdeFavouriteSync.favourites(xml)
        assertEquals(mapOf("advance wars (usa).gba" to true, "golden sun (usa).gba" to false, "mario & luigi (usa).gba" to false), favourites)
    }

    @Test
    fun setsAndRemovesTheTagInPlaceAndKeepsTheRest() {
        val xml = gamelist(
            game("./Advance Wars (USA).gba", extra = "\t\t<desc>Turn-based</desc>\n"),
            game("./Golden Sun (USA).gba", favourite = true),
            game("./Metroid Fusion (USA).gba", favourite = false)
        )
        val out = EsdeFavouriteSync.withFavourites(
            xml,
            mapOf("Advance Wars (USA).gba" to true, "./golden sun (usa).gba" to false, "Metroid Fusion (USA).gba" to true)
        )!!
        val favourites = EsdeFavouriteSync.favourites(out)
        assertEquals(true, favourites["advance wars (usa).gba"])
        assertEquals(false, favourites["golden sun (usa).gba"])
        assertEquals(true, favourites["metroid fusion (usa).gba"])
        // Added on a line of its own, like the entry's other children; the removed one leaves no blank line.
        assertTrue(out.contains("\t\t<desc>Turn-based</desc>\n\t\t<favorite>true</favorite>\n\t</game>"))
        assertFalse(out.contains("<favorite>false</favorite>"))
        assertTrue(out.contains("<name>Golden Sun (USA)</name>\n\t</game>"))
        assertTrue(out.contains("<desc>Turn-based</desc>"))
    }

    @Test
    fun createsMissingEntriesEscapedAndNothingWhenThereIsNothingToChange() {
        val xml = gamelist(game("./Advance Wars (USA).gba", favourite = true))
        assertNull(EsdeFavouriteSync.withFavourites(xml, mapOf("Advance Wars (USA).gba" to true)))
        // Un-marking what is not there is no change either.
        assertNull(EsdeFavouriteSync.withFavourites(xml, mapOf("Golden Sun (USA).gba" to false)))

        val out = EsdeFavouriteSync.withFavourites(xml, mapOf("Mario & Luigi (USA).gba" to true))!!
        assertTrue(out.contains("<path>./Mario &amp; Luigi (USA).gba</path>"))
        assertEquals(true, EsdeFavouriteSync.favourites(out)["mario & luigi (usa).gba"])
        assertTrue(out.trimEnd().endsWith("</gameList>"))

        val fresh = EsdeFavouriteSync.withFavourites(null, mapOf("Golden Sun (USA).gba" to true))!!
        assertTrue(fresh.startsWith("<?xml"))
        assertEquals(mapOf("golden sun (usa).gba" to true), EsdeFavouriteSync.favourites(fresh))
        assertNull(EsdeFavouriteSync.withFavourites(null, mapOf("Golden Sun (USA).gba" to false)))

        val empty = EsdeFavouriteSync.withFavourites("<?xml version=\"1.0\"?>\n<gameList />\n", mapOf("Golden Sun (USA).gba" to true))!!
        assertEquals(mapOf("golden sun (usa).gba" to true), EsdeFavouriteSync.favourites(empty))
    }

    @Test
    fun listsOnlyWhatEsdeShowsAsAGame() {
        val extensions = listOf(".gba", ".GBA", ".zip", ".ZIP", ".m3u", ".dgmtx", ".DGMTX")
        assertTrue(EsdeFavouriteSync.listsAsGame("Advance Wars (USA).gba", extensions))
        assertTrue(EsdeFavouriteSync.listsAsGame("Final Fantasy IX (USA).m3u", extensions))
        assertFalse(EsdeFavouriteSync.listsAsGame("Advance Wars (USA).Gba", extensions))
        assertFalse(EsdeFavouriteSync.listsAsGame("Advance Wars (USA).sav", extensions))
        assertFalse(EsdeFavouriteSync.listsAsGame(DgmtxFile.SHORTCUT_FILE, extensions))
        assertFalse(EsdeFavouriteSync.listsAsGame(".hidden.gba", extensions))
        assertEquals(listOf(".gba", ".GBA"), EsdeFavouriteSync.systemExtensions("<system><extension>.gba .GBA</extension></system>"))
    }

    // ---- Two-way sync --------------------------------------------------------------------------

    @Test
    fun firstSyncTakesBothSidesAndOnlyConfirmedOnesCountAsSynced() {
        val xml = gamelist(game("./Advance Wars (USA).gba", favourite = true))
        val rows = listOf(row("Advance Wars (USA).zip"), row("Golden Sun (USA).zip", starred = true))
        val plan = EsdeFavouriteSync.reconcile(xml, listOf("Advance Wars (USA).gba", "Golden Sun (USA).gba"), rows, emptySet())

        assertEquals(listOf("Advance Wars (USA).zip"), plan.star.map { it.fileName })
        assertTrue(plan.unstar.isEmpty())
        assertEquals(1, plan.marked)
        assertEquals(true, EsdeFavouriteSync.favourites(plan.gamelist!!)["golden sun (usa).gba"])
        // Golden Sun only joins once a later run reads it back from ES-DE.
        assertEquals(setOf("advance wars (usa).gba"), plan.synced)
    }

    @Test
    fun theStarIsPerGameWhicheverVersionIsOnDisk() {
        val rows = listOf(
            row("Golden Sun (USA).zip", starred = true, group = "golden sun"),
            row("Golden Sun (Europe).zip", group = "golden sun")
        )
        val plan = EsdeFavouriteSync.reconcile(gamelist(), listOf("Golden Sun (Europe).gba"), rows, emptySet())
        assertEquals(true, EsdeFavouriteSync.favourites(plan.gamelist!!)["golden sun (europe).gba"])
    }

    @Test
    fun aStarRemovedHereIsRemovedInEsde() {
        val xml = gamelist(game("./Advance Wars (USA).gba", favourite = true), game("./Golden Sun (USA).gba", favourite = true))
        val rows = listOf(row("Advance Wars (USA).zip"), row("Golden Sun (USA).zip", starred = true))
        val synced = setOf("advance wars (usa).gba", "golden sun (usa).gba")
        val plan = EsdeFavouriteSync.reconcile(xml, listOf("Advance Wars (USA).gba", "Golden Sun (USA).gba"), rows, synced)

        assertTrue(plan.star.isEmpty())
        assertEquals(1, plan.unmarked)
        assertEquals(false, EsdeFavouriteSync.favourites(plan.gamelist!!)["advance wars (usa).gba"])
        assertEquals(setOf("golden sun (usa).gba"), plan.synced)
    }

    @Test
    fun aFavouriteRemovedInEsdeIsUnstarredHereEvenWhenEsdeDroppedTheEntry() {
        // Scraped game: the entry stays without <favorite>. Unscraped one: ES-DE drops the whole entry.
        val xml = gamelist(game("./Advance Wars (USA).gba", extra = "\t\t<desc>…</desc>\n"), game("./Metroid Fusion (USA).gba", favourite = true))
        val rows = listOf(
            row("Advance Wars (USA).zip", starred = true),
            row("Golden Sun (USA).zip", starred = true),
            row("Golden Sun (Europe).zip", starred = true, group = VersionGrouping.groupKey("Golden Sun (USA)")),
            row("Metroid Fusion (USA).zip", starred = true)
        )
        val synced = setOf("advance wars (usa).gba", "golden sun (usa).gba", "metroid fusion (usa).gba")
        val plan = EsdeFavouriteSync.reconcile(xml, listOf("Advance Wars (USA).gba", "Golden Sun (USA).gba", "Metroid Fusion (USA).gba"), rows, synced)

        // Every starred version of those games goes.
        assertEquals(setOf("Advance Wars (USA).zip", "Golden Sun (USA).zip", "Golden Sun (Europe).zip"), plan.unstar.map { it.fileName }.toSet())
        assertNull(plan.gamelist)
        assertEquals(setOf("metroid fusion (usa).gba"), plan.synced)
    }

    @Test
    fun aMissingEntryOfAFileNotOnDiskRemovesNothing() {
        val xml = gamelist(game("./Metroid Fusion (USA).gba", favourite = true))
        val rows = listOf(row("Advance Wars (USA).zip", starred = true), row("Metroid Fusion (USA).zip", starred = true))
        val plan = EsdeFavouriteSync.reconcile(xml, listOf("Metroid Fusion (USA).gba"), rows, setOf("advance wars (usa).gba", "metroid fusion (usa).gba"))
        assertTrue(plan.unstar.isEmpty())
        assertNull(plan.gamelist)
    }

    @Test
    fun aSideThatLostEverythingIsTakenAsResetNotAsRemovals() {
        val rows = listOf(row("Advance Wars (USA).zip", starred = true), row("Golden Sun (USA).zip", starred = true))
        val onDisk = listOf("Advance Wars (USA).gba", "Golden Sun (USA).gba")
        val synced = setOf("advance wars (usa).gba", "golden sun (usa).gba")

        // Gamelist gone, or rewritten without any of them: marked again instead of un-starred.
        for (xml in listOf(null, gamelist(game("./Advance Wars (USA).gba"), game("./Golden Sun (USA).gba")))) {
            val plan = EsdeFavouriteSync.reconcile(xml, onDisk, rows, synced)
            assertTrue(plan.unstar.isEmpty())
            assertEquals(2, plan.marked)
        }

        // Every star gone here (sources imported under other ids…): starred again from ES-DE.
        val esde = gamelist(game("./Advance Wars (USA).gba", favourite = true), game("./Golden Sun (USA).gba", favourite = true))
        val plan = EsdeFavouriteSync.reconcile(esde, onDisk, rows.map { it.copy(starred = false) }, synced)
        assertEquals(0, plan.unmarked)
        assertEquals(2, plan.star.size)
    }

    @Test
    fun favouritesWithoutALibraryRowStayAsTheyAre() {
        val xml = gamelist(game("./Homebrew Demo.gba", favourite = true))
        val plan = EsdeFavouriteSync.reconcile(xml, listOf("Homebrew Demo.gba"), emptyList(), setOf("homebrew demo.gba"))
        assertNull(plan.gamelist)
        assertTrue(plan.star.isEmpty())
        assertEquals(setOf("homebrew demo.gba"), plan.synced)
    }

    @Test
    fun aMultiDiscFolderStandsForItsDiscs() {
        val xml = gamelist(game("./Final Fantasy IX (USA).m3u", favourite = true))
        val rows = (1..4).map { Row("sony_psx", "Final Fantasy IX (USA) (Disc $it).chd", "final fantasy ix", false) }
        val plan = EsdeFavouriteSync.reconcile(xml, listOf("Final Fantasy IX (USA).m3u"), rows, emptySet())
        assertEquals(4, plan.star.size)
    }

    @Test
    fun deeperCopiesAreNeverMarked() {
        val xml = gamelist(game("./Hacks/Advance Wars (USA).gba"))
        val plan = EsdeFavouriteSync.reconcile(xml, emptyList(), listOf(row("Advance Wars (USA).zip", starred = true)), emptySet())
        assertNull(plan.gamelist)
        assertTrue(plan.unstar.isEmpty())
    }

    // ---- Push ----------------------------------------------------------------------------------

    @Test
    fun pushOnlyTouchesTheGamesThatChanged() {
        val xml = gamelist(game("./Advance Wars (USA).gba", favourite = true), game("./Metroid Fusion (USA).gba", favourite = true))
        val rows = listOf(
            row("Advance Wars (USA).zip"),
            row("Golden Sun (USA).zip", starred = true),
            row("Metroid Fusion (USA).zip")   // not starred here, but it did not change: left to the next sync
        )
        val onDisk = listOf("Advance Wars (USA).gba", "Golden Sun (USA).gba", "Metroid Fusion (USA).gba")
        val plan = EsdeFavouriteSync.push(xml, onDisk, rows, setOf("nintendo_gba|Advance Wars (USA).zip", "nintendo_gba|Golden Sun (USA).zip"))

        val favourites = EsdeFavouriteSync.favourites(plan.gamelist!!)
        assertEquals(false, favourites["advance wars (usa).gba"])
        assertEquals(true, favourites["golden sun (usa).gba"])
        assertEquals(true, favourites["metroid fusion (usa).gba"])
        assertEquals(1, plan.marked)
        assertEquals(1, plan.unmarked)

        assertNull(EsdeFavouriteSync.push(xml, onDisk, rows, setOf("snes|Chrono Trigger (USA).zip")).gamelist)
        assertNotNull(EsdeFavouriteSync.push(null, onDisk, rows, setOf("nintendo_gba|Golden Sun (USA).zip")).gamelist)
    }
}
