package com.cortinadev.dogmatix.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.testing.LibraryQueries.gamePage
import com.cortinadev.dogmatix.testing.TestData
import com.cortinadev.dogmatix.testing.TestData.rows
import com.cortinadev.dogmatix.testing.TestData.single
import com.cortinadev.dogmatix.util.SearchNormalizer
import com.cortinadev.dogmatix.util.VersionGrouping
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Upgrades from the databases old releases left behind (v9: 1.1 – 1.2, v6: 1.0) to the current
 * version, through [DogmatixDatabase.ALL_MIGRATIONS]: Room validates the result against today's
 * entities when it opens (a migration that leaves the schema off fails the test), and the data
 * must come through: sources, library rows with their new columns worked out, the Downloads list,
 * favourites and cached metadata. The target is whatever version DogmatixDatabase declares, so a
 * new migration is covered as soon as it is in ALL_MIGRATIONS.
 *
 * Works on its own database file in the sandbox (`migration_test.db`), deleted after each test.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context = TestData.context
    private val opened = mutableListOf<DogmatixDatabase>()

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        SQLiteDatabase.deleteDatabase(context.getDatabasePath(NAME))
    }

    // ---- The migration list ---------------------------------------------------------------------

    @Test
    fun everyMigrationIsListedAndTheChainHasNoGaps() {
        val all = DogmatixDatabase.ALL_MIGRATIONS.toList()
        val target = currentVersion()
        val steps = all.map { it.startVersion to it.endVersion }
        assertEquals("one step per version", (1 until target).map { it to it + 1 }, steps.sortedBy { it.first })
        // A migration declared in DogmatixDatabase but left out of ALL_MIGRATIONS (the app would not run it).
        val declared = DogmatixDatabase.Companion::class.java.declaredMethods
            .filter { it.parameterCount == 0 && it.name.startsWith("getMIGRATION_") && Migration::class.java.isAssignableFrom(it.returnType) }
            .map { it.invoke(DogmatixDatabase.Companion) as Migration }
        assertTrue("no MIGRATION_x_y found by reflection", declared.isNotEmpty())
        declared.forEach { m ->
            assertTrue("MIGRATION_${m.startVersion}_${m.endVersion} is not in ALL_MIGRATIONS", all.any { it === m })
        }
    }

    // ---- From 1.1 – 1.2 (v9) --------------------------------------------------------------------

    @Test
    fun upgradesFromVersion9KeepingTheData() = runBlocking {
        createLegacy(9)
        val db = open()
        val sql = db.openHelper.writableDatabase
        assertHealthy(sql)
        assertSourcesAndLibrary(db)

        // Downloads list: statuses, order of the queue (insertion order), no hold, debrid ids kept.
        val history = sql.rows("SELECT * FROM download_history ORDER BY rowid")
        assertEquals(HISTORY.map { it.fileName }, history.map { it["fileName"] })
        assertEquals(HISTORY.map { it.status.name }, history.map { it["status"] })
        assertTrue(history.all { it["failureReason"] == null && it["queueHeld"] == 0L })
        val orders = history.map { it["queueOrder"] as Long }
        assertEquals("queueOrder follows the old insertion order, unique", orders.sorted().distinct(), orders)
        val debrid = history.single { it["fileName"] == "Super Mario World (Europe) (Rev 1).sfc" }
        assertEquals("TORBOX", debrid["debridProvider"])
        assertEquals("12345", debrid["debridTorrentId"])
        assertEquals(2L, debrid["debridFileId"])

        // Through the DAO as the app reads it after the upgrade.
        val entities = db.downloadHistoryDao().getAll()
        assertEquals(HISTORY.size, entities.size)
        entities.forEach { it.toItem() } // every stored status still parses
        assertEquals(
            setOf("Final Fantasy VII (Europe) (Disc 1).chd", "Super Mario World (Europe) (Rev 1).sfc"),
            entities.filter { it.wasInFlight }.map { it.fileName }.toSet()
        )

        // Favourites and cached metadata as they were.
        assertEquals(
            listOf("nintendo_snes|Super Mario World (USA).sfc|5000", "sony_playstation|Final Fantasy VII (Europe) (Disc 1).chd|6000"),
            sql.rows("SELECT * FROM favourites ORDER BY addedAt").map { "${it["consoleId"]}|${it["fileName"]}|${it["addedAt"]}" }
        )
        assertEquals(setOf("nintendo_snes", "sony_playstation"), db.favouriteDao().getAll().map { it.consoleId }.toSet())
        assertEquals("Super Mario World", db.gameMetadataDao().get("nintendo_snes|super mario world")?.title)
        assertEquals("", db.gameMetadataDao().get("sony_playstation|final fantasy vii")?.source)
    }

    @Test
    fun aMigratedDatabaseOpensAgainUnchanged() = runBlocking {
        createLegacy(9)
        val first = open()
        val before = snapshot(first.openHelper.writableDatabase)
        val identity = first.openHelper.writableDatabase.single("SELECT identity_hash FROM room_master_table WHERE id = 42")
        first.close()

        val second = open()
        val sql = second.openHelper.writableDatabase
        assertEquals(currentVersion(), sql.version)
        assertEquals(identity, sql.single("SELECT identity_hash FROM room_master_table WHERE id = 42"))
        assertEquals(before, snapshot(sql))
        assertHealthy(sql)
        // Written after the reopen: the FTS triggers are in place.
        second.downloadableFileDao().insertAll(listOf(TestData.webFile("nintendo_nes", "Metroid", "Metroid (USA).nes")))
        assertEquals(1, sql.rows("SELECT rowid FROM downloadable_files_fts WHERE downloadable_files_fts MATCH 'metroid'").size)
    }

    // ---- From 1.0 (v6) --------------------------------------------------------------------------

    @Test
    fun upgradesFromVersion6KeepingTheData() = runBlocking {
        createLegacy(6)
        val db = open()
        val sql = db.openHelper.writableDatabase
        assertHealthy(sql)
        assertSourcesAndLibrary(db)
        val history = sql.rows("SELECT * FROM download_history ORDER BY rowid")
        assertEquals(HISTORY_V6.map { it.fileName }, history.map { it["fileName"] })
        assertEquals(HISTORY_V6.map { it.status.name }, history.map { it["status"] })
        // Rebuilt by MIGRATION_8_9 with no TorBox ids: no debrid service on any row.
        assertTrue(history.all { it["debridProvider"] == null && it["debridTorrentId"] == null && it["debridFileId"] == null })
        assertEquals(0L, sql.single("SELECT COUNT(*) FROM favourites"))
        assertEquals(HISTORY_V6.size, db.downloadHistoryDao().getAll().size)
    }

    // ---- Checks ---------------------------------------------------------------------------------

    private suspend fun assertSourcesAndLibrary(db: DogmatixDatabase) {
        val sql = db.openHelper.writableDatabase
        // Sources: untouched JSON, aliases and short names; the colour added empty.
        val consoles = sql.rows("SELECT * FROM consoles ORDER BY id")
        assertEquals(CONSOLES.map { it.id }.sorted(), consoles.map { it["id"] })
        CONSOLES.forEach { c ->
            val row = consoles.single { it["id"] == c.id }
            assertEquals(c.urls, row["urls"])
            assertEquals(c.shortName, row["shortName"])
            assertEquals(c.folderAliases, row["folderAliases"])
            assertEquals("", row["color"])
        }
        assertEquals(2L, sql.single("SELECT COUNT(*) FROM manufacturers"))

        // Library rows: groupKey worked out by MIGRATION_11_12, torrent rows claimed by their magnet (12_13).
        val files = sql.rows("SELECT * FROM downloadable_files ORDER BY id")
        assertEquals(FILES.size, files.size)
        files.forEach { row ->
            val name = row["name"] as String
            assertEquals("groupKey of $name", VersionGrouping.groupKey(name), row["groupKey"])
            assertEquals("sourceUrl of $name", row["torrentMagnet"] ?: "", row["sourceUrl"])
        }
        assertEquals("dragon quest", files.single { (it["fileName"] as String).startsWith("Dragon Quest") }["groupKey"])
        assertEquals(2, files.count { it["sourceUrl"] == SNES_MAGNET })
        assertEquals(TAGS.size.toLong(), sql.single("SELECT COUNT(*) FROM downloadable_file_tags"))

        // Tables that came after the old version start empty.
        val legacyTables = setOf("manufacturers", "consoles", "downloadable_files", "downloadable_file_tags", "download_history", "game_metadata", "favourites")
        sql.rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'downloadable_files_fts%' AND name NOT IN ('room_master_table', 'android_metadata')")
            .map { it["name"] as String }
            .filter { it !in legacyTables }
            .forEach { assertEquals("new table $it", 0L, sql.single("SELECT COUNT(*) FROM `$it`")) }

        // The full-text index still matches the old rows.
        assertEquals(listOf(1L, 2L), sql.rows("SELECT rowid FROM downloadable_files_fts WHERE downloadable_files_fts MATCH 'mario' ORDER BY rowid").map { it["rowid"] })

        // The library reads one game per groupKey: both Mario versions and both FF VII discs together.
        val dao = db.downloadableFileDao()
        val games = dao.gamePage(limit = 100).map { it.consoleId to it.groupKey }
        assertEquals(
            listOf(
                "nintendo_snes" to "chrono trigger", "nintendo_nes" to "dragon quest", "sony_playstation" to "final fantasy vii",
                "nintendo_nes" to "pokémon puzzle league", "nintendo_snes" to "super mario world"
            ),
            games
        )
        // The search key the old version never filled is filled at startup (DogmatixApplication).
        assertEquals("", sql.single("SELECT searchKey FROM downloadable_files WHERE fileName = 'Chrono Trigger (USA).sfc'"))
        dao.backfillSearchKeys()
        assertEquals(SearchNormalizer.key("Chrono Trigger"), sql.single("SELECT searchKey FROM downloadable_files WHERE fileName = 'Chrono Trigger (USA).sfc'"))
        assertEquals(1, dao.gamePage(limit = 10, query = SearchNormalizer.likePattern("chrono")).size)
    }

    private fun assertHealthy(sql: SupportSQLiteDatabase) {
        assertEquals(currentVersion(), sql.version)
        assertEquals(currentVersion().toLong(), sql.single("PRAGMA user_version"))
        assertEquals("ok", sql.single("PRAGMA integrity_check"))
        assertTrue("foreign key violations", sql.rows("PRAGMA foreign_key_check").isEmpty())
        // FTS4 checks its index against downloadable_files: SQLITE_CORRUPT when they disagree.
        try {
            sql.execSQL("INSERT INTO downloadable_files_fts(downloadable_files_fts) VALUES('integrity-check')")
        } catch (e: SQLiteDatabaseCorruptException) {
            throw AssertionError("The full-text index is out of step with downloadable_files", e)
        } catch (e: SQLiteException) {
            // An SQLite without the command: the MATCH checks below still cover the index.
            Log.w("MigrationTest", "FTS integrity-check not available: ${e.message}")
        }
    }

    /** Every row of the tables an upgrade must keep, as text. */
    private fun snapshot(sql: SupportSQLiteDatabase): String =
        listOf("consoles", "downloadable_files", "downloadable_file_tags", "download_history", "favourites", "game_metadata")
            .joinToString("\n") { table -> table + ": " + sql.rows("SELECT * FROM `$table` ORDER BY rowid").joinToString(";") }

    // ---- Building the old database --------------------------------------------------------------

    private fun open(): DogmatixDatabase = TestData.fileDatabase(NAME).also { opened += it }

    /** The version a fresh database gets: DogmatixDatabase's own (Room creates it, no migration). */
    private fun currentVersion(): Int {
        val fresh = TestData.inMemoryDatabase()
        return try { fresh.openHelper.writableDatabase.version } finally { fresh.close() }
    }

    private fun createLegacy(version: Int) {
        val file = context.getDatabasePath(NAME)
        SQLiteDatabase.deleteDatabase(file)
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.beginTransaction()
            LegacySchemas.create(db, version)
            db.insertOrThrow("manufacturers", null, values("id" to "nintendo", "name" to "Nintendo"))
            db.insertOrThrow("manufacturers", null, values("id" to "sony", "name" to "Sony"))
            CONSOLES.forEach { c ->
                db.insertOrThrow("consoles", null, values("id" to c.id, "name" to c.name, "manufacturerId" to c.manufacturerId, "urls" to c.urls, "shortName" to c.shortName, "folderAliases" to c.folderAliases))
            }
            FILES.forEachIndexed { i, f ->
                db.insertOrThrow("downloadable_files", null, values(
                    "id" to (i + 1L), "name" to f.name, "fileName" to f.fileName, "consoleId" to f.consoleId,
                    "downloadUrl" to (f.magnet ?: "https://roms.example.org/${f.consoleId}/${f.fileName}"),
                    "fileSize" to 1_048_576L * (i + 1), "fileExtension" to f.fileName.substringAfterLast('.'),
                    "torrentFileIndex" to f.magnet?.let { i.toLong() }, "torrentMagnet" to f.magnet,
                    // Rows indexed before the search key existed carry '' until the startup backfill.
                    "searchKey" to if (f.fileName.startsWith("Chrono")) "" else SearchNormalizer.key(f.name)
                ))
            }
            TAGS.forEach { (fileId, tag) -> db.insertOrThrow("downloadable_file_tags", null, values("fileId" to fileId, "tag" to tag)) }
            (if (version >= 9) HISTORY else HISTORY_V6).forEach { h ->
                val row = values(
                    "fileName" to h.fileName, "name" to h.fileName.substringBefore(" ("), "consoleId" to h.consoleId,
                    "downloadUrl" to "https://roms.example.org/${h.consoleId}/${h.fileName}", "fileSize" to 4_000_000L,
                    "fileExtension" to h.fileName.substringAfterLast('.'), "torrentFileIndex" to null, "torrentMagnet" to null,
                    "status" to h.status.name, "startedAt" to h.startedAt, "finishedAt" to h.finishedAt
                )
                if (version >= 9) {
                    row.put("debridProvider", h.debridProvider)
                    row.put("debridTorrentId", h.debridTorrentId)
                    if (h.debridFileId != null) row.put("debridFileId", h.debridFileId) else row.putNull("debridFileId")
                }
                db.insertOrThrow("download_history", null, row)
            }
            if (version >= 7) {
                db.insertOrThrow("favourites", null, values("consoleId" to "nintendo_snes", "fileName" to "Super Mario World (USA).sfc", "addedAt" to 5000L))
                db.insertOrThrow("favourites", null, values("consoleId" to "sony_playstation", "fileName" to "Final Fantasy VII (Europe) (Disc 1).chd", "addedAt" to 6000L))
            }
            db.insertOrThrow("game_metadata", null, values(
                "lookupKey" to "nintendo_snes|super mario world", "title" to "Super Mario World", "description" to "Dinosaur Land.",
                "genres" to "Platformer", "released" to "1990-11-21", "developer" to "Nintendo EAD",
                "imageUrl" to "https://media.example.org/smw.jpg", "source" to "rawg", "fetchedAt" to 7000L
            ))
            // A miss (empty source) is cached too.
            db.insertOrThrow("game_metadata", null, values(
                "lookupKey" to "sony_playstation|final fantasy vii", "title" to "", "description" to "", "genres" to "",
                "released" to "", "developer" to "", "imageUrl" to "", "source" to "", "fetchedAt" to 8000L
            ))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    private fun values(vararg pairs: Pair<String, Any?>) = ContentValues().apply {
        pairs.forEach { (key, value) ->
            when (value) {
                null -> putNull(key)
                is String -> put(key, value)
                is Long -> put(key, value)
                is Int -> put(key, value)
                else -> error("Unsupported value for $key: $value")
            }
        }
    }

    private data class LegacyConsole(val id: String, val name: String, val manufacturerId: String, val urls: String, val shortName: String, val folderAliases: String)

    private data class LegacyFile(val consoleId: String, val name: String, val fileName: String, val magnet: String? = null)

    private data class LegacyDownload(
        val fileName: String,
        val consoleId: String,
        val status: DownloadStatus,
        val startedAt: Long,
        val finishedAt: Long? = null,
        val debridProvider: String? = null,
        val debridTorrentId: String? = null,
        val debridFileId: Long? = null
    )

    companion object {
        private const val NAME = "migration_test.db"
        private const val SNES_MAGNET = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=SNES%20Set"

        private val CONSOLES = listOf(
            LegacyConsole(
                "nintendo_snes", "Super Nintendo", "nintendo",
                """[{"url":"$SNES_MAGNET","contentType":"GAME","folders":["Roms"]},{"url":"https://roms.example.org/snes/","contentType":"GAME","folders":[],"enabled":false}]""",
                "SNES", "snes,sfc"
            ),
            LegacyConsole("sony_playstation", "PlayStation", "sony", """[{"url":"https://roms.example.org/psx/","contentType":"GAME","folders":[]}]""", "", ""),
            LegacyConsole("nintendo_nes", "Nintendo Entertainment System", "nintendo", "[]", "NES", "")
        )

        private val FILES = listOf(
            LegacyFile("nintendo_snes", "Super Mario World", "Super Mario World (USA).sfc", SNES_MAGNET),
            LegacyFile("nintendo_snes", "Super Mario World", "Super Mario World (Europe) (Rev 1).sfc", SNES_MAGNET),
            LegacyFile("nintendo_snes", "Chrono Trigger", "Chrono Trigger (USA).sfc"),
            LegacyFile("sony_playstation", "Final Fantasy VII", "Final Fantasy VII (Europe) (Disc 1).chd"),
            LegacyFile("sony_playstation", "Final Fantasy VII", "Final Fantasy VII (Europe) (Disc 2).chd"),
            LegacyFile("nintendo_nes", "Dragon Quest [T-En by DvD]", "Dragon Quest (Japan) [T-En by DvD].nes"),
            LegacyFile("nintendo_nes", "Pokémon Puzzle League", "Pokémon Puzzle League (USA).nes")
        )

        private val TAGS = listOf(
            1L to "USA", 1L to "EN", 2L to "Europe", 2L to "EN", 2L to "Rev 1", 3L to "USA", 3L to "EN",
            4L to "Europe", 4L to "Disc 1", 5L to "Europe", 5L to "Disc 2", 6L to "Japan", 6L to "T-En",
            7L to "USA", 7L to "EN"
        )

        private val HISTORY = listOf(
            LegacyDownload("Super Mario World (USA).sfc", "nintendo_snes", DownloadStatus.COMPLETED, 1000, 2000),
            LegacyDownload("Chrono Trigger (USA).sfc", "nintendo_snes", DownloadStatus.FAILED, 1100, 1200),
            LegacyDownload("Final Fantasy VII (Europe) (Disc 1).chd", "sony_playstation", DownloadStatus.DOWNLOADING, 1300),
            LegacyDownload("Final Fantasy VII (Europe) (Disc 2).chd", "sony_playstation", DownloadStatus.PAUSED, 1400),
            LegacyDownload("Dragon Quest (Japan) [T-En by DvD].nes", "nintendo_nes", DownloadStatus.STOPPED, 1500, 1600),
            LegacyDownload("Super Mario World (Europe) (Rev 1).sfc", "nintendo_snes", DownloadStatus.QUEUED, 1700, null, "TORBOX", "12345", 2)
        )

        /** 1.0 had neither QUEUED nor PAUSED. */
        private val HISTORY_V6 = listOf(
            LegacyDownload("Super Mario World (USA).sfc", "nintendo_snes", DownloadStatus.COMPLETED, 1000, 2000),
            LegacyDownload("Chrono Trigger (USA).sfc", "nintendo_snes", DownloadStatus.FAILED, 1100, 1200),
            LegacyDownload("Final Fantasy VII (Europe) (Disc 1).chd", "sony_playstation", DownloadStatus.DOWNLOADING, 1300),
            LegacyDownload("Dragon Quest (Japan) [T-En by DvD].nes", "nintendo_nes", DownloadStatus.STOPPED, 1500, 1600)
        )
    }
}
