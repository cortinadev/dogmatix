package com.cortinadev.dogmatix.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cortinadev.dogmatix.data.local.entity.DownloadCheckEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadResumeEntity
import com.cortinadev.dogmatix.data.local.entity.FavouriteEntity
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.testing.LibraryQueries.gamePage
import com.cortinadev.dogmatix.testing.TestData
import com.cortinadev.dogmatix.testing.TestData.rows
import com.cortinadev.dogmatix.util.SearchNormalizer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The DAOs on a real Room database (in memory, foreign keys on like the app): how a rescan
 * replaces one source's rows, and the bookkeeping of the Downloads list, the resume records and
 * the favourites.
 */
@RunWith(AndroidJUnit4::class)
class DaoTest {

    private lateinit var db: DogmatixDatabase
    private val scans get() = db.sourceScanDao()
    private val files get() = db.downloadableFileDao()
    private val sql get() = db.openHelper.writableDatabase

    @Before
    fun setUp() = runBlocking {
        db = TestData.inMemoryDatabase()
        db.manufacturerDao().insertManufacturer(TestData.manufacturer())
        db.consoleDao().insertConsoles(listOf(TestData.console(SNES, "Super Nintendo"), TestData.console(NES, "NES")))
    }

    @After
    fun tearDown() = db.close()

    private fun rom(name: String, fileName: String = "$name (USA).sfc", console: String = SNES) = TestData.webFile(console, name, fileName)

    private fun tags(vararg perFile: List<String>) = perFile.toList()

    private suspend fun namesOf(source: String, console: String = SNES): List<String> =
        scans.sourceNames(console, source).map { it.fileName }.sorted()

    private fun ftsMatches(token: String): List<String> =
        sql.rows(
            "SELECT df.fileName FROM downloadable_files_fts JOIN downloadable_files df ON df.id = downloadable_files_fts.rowid " +
                "WHERE downloadable_files_fts MATCH ? ORDER BY df.fileName",
            token
        )
            .map { it["fileName"] as String }

    // ---- SourceScanDao.replaceSource -----------------------------------------------------------

    @Test
    fun rescanningASourceReplacesItsRowsWithoutDuplicates() = runBlocking {
        val listing = listOf(rom("Chrono Trigger"), rom("EarthBound"))
        val first = scans.replaceSource(SNES, WEB, listing, tags(listOf("USA", "EN"), listOf("USA")))
        // A source's first scan counts nothing as new (there was nothing to compare with).
        assertEquals(0, first.newFiles)
        assertEquals(3, first.tags)
        val second = scans.replaceSource(SNES, WEB, listing + rom("Secret of Mana"), tags(listOf("USA", "EN"), listOf("USA"), listOf("USA", "USA")))
        assertEquals(listOf("Chrono Trigger (USA).sfc", "EarthBound (USA).sfc", "Secret of Mana (USA).sfc"), namesOf(WEB))
        assertEquals(1, second.newFiles)
        assertEquals(1, second.newGames)
        // Duplicate tags of one file are written once.
        assertEquals(4, second.tags)
        assertEquals(3, scans.countRows(SNES, WEB))
        assertEquals(4L, sql.rows("SELECT COUNT(*) AS n FROM downloadable_file_tags").single()["n"])
        // Every row is stamped with the source and its console, whatever the listing said.
        assertTrue(sql.rows("SELECT sourceUrl, consoleId FROM downloadable_files").all { it["sourceUrl"] == WEB && it["consoleId"] == SNES })
    }

    @Test
    fun anotherSourceOfTheSameConsoleIsLeftAlone() = runBlocking {
        scans.replaceSource(SNES, WEB, listOf(rom("Chrono Trigger")), tags(listOf("USA")))
        scans.replaceSource(SNES, TORRENT, listOf(rom("Chrono Trigger", "Chrono Trigger (Japan).sfc"), rom("Rudra no Hihou", "Rudra no Hihou (Japan).sfc")), tags(listOf("Japan"), listOf("Japan")))
        scans.replaceSource(SNES, WEB, listOf(rom("Chrono Trigger"), rom("Super Metroid")), tags(listOf("USA"), listOf("USA")))
        assertEquals(listOf("Chrono Trigger (Japan).sfc", "Rudra no Hihou (Japan).sfc"), namesOf(TORRENT))
        assertEquals(listOf("Chrono Trigger (USA).sfc", "Super Metroid (USA).sfc"), namesOf(WEB))
        // Its tags too.
        val japan = files.getFileByFileName("Rudra no Hihou (Japan).sfc")!!
        assertEquals(listOf("Japan"), files.getTagsForFile(japan.id))
        // An empty listing empties only that source.
        scans.replaceSource(SNES, WEB, emptyList(), emptyList())
        assertEquals(0, scans.countRows(SNES, WEB))
        assertEquals(2, scans.countRows(SNES, TORRENT))
    }

    @Test
    fun legacyRowsOfTheSameNameAreTakenOverByTheirSource() = runBlocking {
        // Rows indexed before sources were tracked: sourceUrl ''.
        files.insertFilesWithTags(listOf(rom("Chrono Trigger"), rom("Star Fox")), tags(listOf("USA"), listOf("USA")))
        assertTrue(scans.hasLegacyRows(SNES))
        val written = scans.replaceSource(SNES, WEB, listOf(rom("Chrono Trigger"), rom("Super Metroid")), tags(listOf("USA", "EN"), listOf("USA")))
        assertEquals(listOf("Chrono Trigger (USA).sfc", "Super Metroid (USA).sfc"), namesOf(WEB))
        // Unclaimed legacy rows stay until a full scan drops them.
        assertEquals(listOf("Star Fox (USA).sfc"), namesOf(""))
        // Chrono Trigger was known through its legacy row: only Super Metroid is new.
        assertEquals(1, written.newFiles)
        assertEquals(1, written.newGames)
        assertEquals(1, sql.rows("SELECT id FROM downloadable_files WHERE fileName = 'Chrono Trigger (USA).sfc'").size)
        scans.deleteSource(SNES, "")
        assertFalse(scans.hasLegacyRows(SNES))
    }

    @Test
    fun searchAndTheFullTextIndexFollowAReplacement() = runBlocking {
        scans.replaceSource(SNES, WEB, listOf(rom("Chrono Trigger"), rom("Pokémon Stadium")), tags(listOf("USA"), listOf("USA")))
        scans.replaceSource(SNES, WEB, listOf(rom("Super Metroid"), rom("Pokémon Stadium")), tags(listOf("USA"), listOf("USA")))
        assertEquals(listOf("Super Metroid (USA).sfc"), ftsMatches("metroid"))
        assertTrue(ftsMatches("chrono").isEmpty())
        // The library search (lenient LIKE on searchKey): accents and case do not matter.
        assertEquals(1, files.gamePage(limit = 10, query = SearchNormalizer.likePattern("POKEMON")).size)
        assertEquals(1, files.gamePage(limit = 10, query = SearchNormalizer.likePattern("metro")).size)
        assertEquals(0, files.gamePage(limit = 10, query = SearchNormalizer.likePattern("chrono")).size)
    }

    @Test
    fun aReplacementThatFailsHalfwayLeavesThePreviousRows() = runBlocking {
        scans.replaceSource(SNES, WEB, listOf(rom("Chrono Trigger"), rom("EarthBound")), tags(listOf("USA"), listOf("USA")))
        // The second write of the next scan breaks off in the middle (as a full disk or a crash would).
        sql.execSQL(
            "CREATE TRIGGER fail_on_boom BEFORE INSERT ON downloadable_files WHEN NEW.name = 'BOOM' " +
                "BEGIN SELECT RAISE(ABORT, 'forced failure'); END"
        )
        try {
            scans.replaceSource(SNES, WEB, listOf(rom("Secret of Mana"), rom("BOOM")), tags(listOf("USA"), listOf("USA")))
            fail("the forced failure should have reached the caller")
        } catch (e: Exception) {
            assertTrue(e.toString(), e.toString().contains("forced failure"))
        }
        assertEquals(listOf("Chrono Trigger (USA).sfc", "EarthBound (USA).sfc"), namesOf(WEB))
        assertEquals(2L, sql.rows("SELECT COUNT(*) AS n FROM downloadable_file_tags").single()["n"])
        assertEquals(listOf("Chrono Trigger (USA).sfc"), ftsMatches("chrono"))
        assertTrue(ftsMatches("mana").isEmpty())
        sql.execSQL("DROP TRIGGER fail_on_boom")
    }

    @Test
    fun sameNameInTwoFoldersKeepsItsOwnTags() = runBlocking {
        val usa = TestData.webFile(SNES, "Tetris", "Tetris.sfc", folder = "https://roms.example.org/snes/usa")
        val jpn = TestData.webFile(SNES, "Tetris", "Tetris.sfc", folder = "https://roms.example.org/snes/japan")
        scans.replaceSource(SNES, WEB, listOf(usa, jpn), tags(listOf("USA"), listOf("Japan")))
        val rows = sql.rows("SELECT id, downloadUrl FROM downloadable_files ORDER BY id")
        assertEquals(2, rows.size)
        assertEquals(listOf("USA"), files.getTagsForFile(rows[0]["id"] as Long))
        assertEquals(listOf("Japan"), files.getTagsForFile(rows[1]["id"] as Long))
    }

    // ---- DownloadHistoryDao --------------------------------------------------------------------

    @Test
    fun theDownloadsListKeepsPlaceHoldAndRestarts() = runBlocking {
        val history = db.downloadHistoryDao()
        history.upsert(TestData.history("b.zip", DownloadStatus.DOWNLOADING, order = 2, startedAt = 2000))
        history.upsert(TestData.history("a.zip", DownloadStatus.FAILED, order = 1, startedAt = 1000))
        assertEquals(listOf("a.zip", "b.zip"), history.getAll().map { it.fileName }) // by startedAt

        history.setQueuePlace("b.zip", order = -5, held = true)
        history.getByFileName("b.zip")!!.let {
            assertEquals(-5L, it.queueOrder)
            assertTrue(it.queueHeld)
        }
        history.setFailure("a.zip", "NOT_FOUND")
        assertEquals("NOT_FOUND", history.getByFileName("a.zip")!!.failureReason)
        history.markRestarted("a.zip", DownloadStatus.DOWNLOADING.name, startedAt = 3000, retryCount = 0)
        history.getByFileName("a.zip")!!.let {
            assertEquals(DownloadStatus.DOWNLOADING.name, it.status)
            assertEquals(3000L, it.startedAt)
            assertNull(it.finishedAt)
            // The restart leaves the place alone; the service clears the reason itself.
            assertEquals(1L, it.queueOrder)
        }
        history.setDebrid("a.zip", "REAL_DEBRID", "ABCDEF", 7)
        assertEquals("ABCDEF", history.getByFileName("a.zip")!!.debridTorrentId)
        // Starting a download again replaces the row: the place is the one given then.
        history.upsert(TestData.history("b.zip", DownloadStatus.DOWNLOADING, order = 9))
        history.getByFileName("b.zip")!!.let {
            assertEquals(9L, it.queueOrder)
            assertFalse(it.queueHeld)
        }
        history.delete("b.zip")
        assertEquals(listOf("a.zip"), history.getAll().map { it.fileName })
    }

    @Test
    fun inFlightRowsAreTheOnesThatWereRunning() {
        val inFlight = DownloadStatus.entries.filter { TestData.history("x.zip", it).wasInFlight }.toSet()
        assertEquals(setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.COPYING, DownloadStatus.UNZIPPING), inFlight)
        // Back after a restart: running ones as stopped, a paused web download still paused, a paused torrent stopped.
        assertEquals(DownloadStatus.STOPPED, TestData.history("x.zip", DownloadStatus.DOWNLOADING, startedAt = 42).toItem().status)
        assertEquals(42L, TestData.history("x.zip", DownloadStatus.DOWNLOADING, startedAt = 42).toItem().finishedAt)
        assertEquals(DownloadStatus.PAUSED, TestData.history("x.zip", DownloadStatus.PAUSED).toItem().status)
        assertEquals(DownloadStatus.STOPPED, TestData.history("x.zip", DownloadStatus.PAUSED, torrent = true).toItem().status)
        TestData.history("x.zip", DownloadStatus.COMPLETED).toItem().let {
            assertEquals(DownloadStatus.COMPLETED, it.status)
            assertEquals(1f, it.progress)
        }
    }

    // ---- DownloadTrackDao ----------------------------------------------------------------------

    @Test
    fun resumeRecordsAreReplacedDeletedAndPurgedByAge() = runBlocking {
        val track = db.downloadTrackDao()
        track.upsertResume(DownloadResumeEntity("old.zip", "\"etag-1\"", "roms.example.org", 100, updatedAt = 1_000))
        track.upsertResume(DownloadResumeEntity("new.zip", null, "roms.example.org", 200, updatedAt = 9_000))
        track.upsertResume(DownloadResumeEntity("old.zip", "\"etag-2\"", "mirror.example.org", 150, updatedAt = 2_000))
        assertEquals("\"etag-2\"", track.resumeFor("old.zip")!!.validator)
        assertEquals("mirror.example.org", track.resumeFor("old.zip")!!.host)
        track.deleteResumesBefore(5_000)
        assertNull(track.resumeFor("old.zip"))
        assertNotNull(track.resumeFor("new.zip"))
        track.deleteResume("new.zip")
        assertNull(track.resumeFor("new.zip"))
    }

    @Test
    fun checksOfDownloadsNoLongerListedAreDropped() = runBlocking {
        val track = db.downloadTrackDao()
        db.downloadHistoryDao().upsert(TestData.history("kept.zip", DownloadStatus.COMPLETED))
        track.upsertCheck(DownloadCheckEntity("kept.zip", "VERIFIED", 1))
        track.upsertCheck(DownloadCheckEntity("gone.zip", "MISMATCH", 1))
        track.deleteOrphanChecks()
        assertEquals(listOf("kept.zip"), track.allChecks().map { it.fileName })
    }

    // ---- FavouriteDao --------------------------------------------------------------------------

    @Test
    fun favouritesAddOnlyWhatIsMissingAndRemoveOnlyWhatIsGiven() = runBlocking {
        val favourites = db.favouriteDao()
        favourites.upsert(FavouriteEntity(SNES, "Chrono Trigger (USA).sfc", addedAt = 100))
        val ids = favourites.insertMissing(listOf(
            FavouriteEntity(SNES, "Chrono Trigger (USA).sfc", addedAt = 999),
            FavouriteEntity(NES, "Metroid (USA).nes", addedAt = 200)
        ))
        assertEquals(-1L, ids[0]) // already starred: ignored
        assertTrue(ids[1] > 0)
        val all = favourites.getAll().associateBy { it.key }
        assertEquals(100L, all.getValue("$SNES|Chrono Trigger (USA).sfc").addedAt) // keeps its date
        assertEquals(2, all.size)
        favourites.deleteAll(listOf(FavouriteEntity(NES, "Metroid (USA).nes", addedAt = 0), FavouriteEntity(NES, "Never starred.nes")))
        assertEquals(listOf("$SNES|Chrono Trigger (USA).sfc"), favourites.getAll().map { it.key })
        // A favourite outlives the library rows (keyed by console + file name, no foreign key).
        files.clearAll()
        assertEquals(1, favourites.getAll().size)
    }

    companion object {
        private const val SNES = "nintendo_snes"
        private const val NES = "nintendo_nes"
        private const val WEB = "https://roms.example.org/snes/"
        private const val TORRENT = TestData.MAGNET
    }
}
