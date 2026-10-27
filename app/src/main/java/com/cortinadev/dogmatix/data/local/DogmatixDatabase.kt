package com.cortinadev.dogmatix.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cortinadev.dogmatix.data.local.dao.ConsoleDao
import com.cortinadev.dogmatix.data.local.dao.CoverDao
import com.cortinadev.dogmatix.data.local.dao.DownloadHistoryDao
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.dao.DownloadTrackDao
import com.cortinadev.dogmatix.data.local.dao.FavouriteDao
import com.cortinadev.dogmatix.data.local.dao.GameTraitDao
import com.cortinadev.dogmatix.data.local.dao.GameMetadataDao
import com.cortinadev.dogmatix.data.local.dao.ManufacturerDao
import com.cortinadev.dogmatix.data.local.dao.SourceScanDao
import com.cortinadev.dogmatix.data.local.dao.RommUploadDao
import com.cortinadev.dogmatix.data.local.dao.RommDao
import com.cortinadev.dogmatix.data.local.dao.ActionHistoryDao
import com.cortinadev.dogmatix.data.local.dao.CollectionDao
import com.cortinadev.dogmatix.data.local.dao.StorageRedirectDao
import com.cortinadev.dogmatix.data.local.dao.TrashDao
import com.cortinadev.dogmatix.data.local.entity.ConsoleEntity
import com.cortinadev.dogmatix.data.local.entity.CoverEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadHistoryEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadCheckEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadResumeEntity
import com.cortinadev.dogmatix.data.local.entity.SourceStatEntity
import com.cortinadev.dogmatix.data.local.entity.FavouriteEntity
import com.cortinadev.dogmatix.data.local.entity.FileTagEntity
import com.cortinadev.dogmatix.data.local.entity.GameMetadataEntity
import com.cortinadev.dogmatix.data.local.entity.GameTraitEntity
import com.cortinadev.dogmatix.data.local.entity.GameTraitSyncEntity
import com.cortinadev.dogmatix.data.local.entity.ManufacturerEntity
import com.cortinadev.dogmatix.data.local.entity.SourceScanEntity
import com.cortinadev.dogmatix.data.local.entity.RommUploadEntity
import com.cortinadev.dogmatix.data.local.entity.RommFavouriteBaseEntity
import com.cortinadev.dogmatix.data.local.entity.RommGameEntity
import com.cortinadev.dogmatix.data.local.entity.ActionHistoryEntity
import com.cortinadev.dogmatix.data.local.entity.CollectionEntity
import com.cortinadev.dogmatix.data.local.entity.CollectionGameEntity
import com.cortinadev.dogmatix.data.local.entity.StorageRedirectEntity
import com.cortinadev.dogmatix.data.local.entity.TrashItemEntity
import com.cortinadev.dogmatix.data.local.queries.DownloadableFileFts
import com.cortinadev.dogmatix.util.VersionGrouping

@Database(
    entities = [ManufacturerEntity::class, ConsoleEntity::class, DownloadableFileEntity::class, FileTagEntity::class, DownloadableFileFts::class, DownloadHistoryEntity::class, GameMetadataEntity::class, FavouriteEntity::class, CoverEntity::class, SourceScanEntity::class, SourceStatEntity::class, DownloadResumeEntity::class, DownloadCheckEntity::class, RommUploadEntity::class, GameTraitEntity::class, GameTraitSyncEntity::class, RommGameEntity::class, RommFavouriteBaseEntity::class,
        // coleccion-gestion
        ActionHistoryEntity::class, TrashItemEntity::class, CollectionEntity::class, CollectionGameEntity::class, StorageRedirectEntity::class],
    version = 20,
    exportSchema = false
)
abstract class DogmatixDatabase : RoomDatabase() {
    abstract fun downloadableFileDao(): DownloadableFileDao
    abstract fun consoleDao(): ConsoleDao
    abstract fun manufacturerDao(): ManufacturerDao
    abstract fun downloadHistoryDao(): DownloadHistoryDao
    abstract fun gameMetadataDao(): GameMetadataDao
    abstract fun favouriteDao(): FavouriteDao
    abstract fun coverDao(): CoverDao
    abstract fun sourceScanDao(): SourceScanDao
    abstract fun downloadTrackDao(): DownloadTrackDao
    abstract fun rommUploadDao(): RommUploadDao
    abstract fun gameTraitDao(): GameTraitDao
    abstract fun rommDao(): RommDao
    // coleccion-gestion
    abstract fun actionHistoryDao(): ActionHistoryDao
    abstract fun trashDao(): TrashDao
    abstract fun collectionDao(): CollectionDao
    abstract fun storageRedirectDao(): StorageRedirectDao

    companion object {
        /** Per-console chip colour and the cover lookup cache of the library list. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE consoles ADD COLUMN color TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS covers (" +
                        "lookupKey TEXT NOT NULL PRIMARY KEY, " +
                        "url TEXT NOT NULL, " +
                        "source TEXT NOT NULL, " +
                        "fetchedAt INTEGER NOT NULL)"
                )
            }
        }

        /** Index on downloadable_files.fileName: finished downloads and the Downloads list look rows up by it. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_downloadable_files_fileName ON downloadable_files (fileName)")
            }
        }

        /**
         * `groupKey` gathers the versions of a game in the library (computed
         * here for what is already indexed, so the list never shows a half-grouped state), and
         * `failureReason` keeps why a download failed across restarts.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloadable_files ADD COLUMN groupKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_downloadable_files_consoleId_groupKey ON downloadable_files (consoleId, groupKey)")
                db.execSQL("ALTER TABLE download_history ADD COLUMN failureReason TEXT DEFAULT NULL")
                val update = db.compileStatement("UPDATE downloadable_files SET groupKey = ? WHERE id = ?")
                db.query("SELECT id, name FROM downloadable_files").use { cursor ->
                    while (cursor.moveToNext()) {
                        update.bindString(1, VersionGrouping.groupKey(cursor.getString(1)))
                        update.bindLong(2, cursor.getLong(0))
                        update.executeUpdateDelete()
                        update.clearBindings()
                    }
                }
            }
        }

        /**
         * escaneo: rows remember their source (a rescan replaces one source at a time) and the
         * last scan of every source is kept (incremental rescans and the scan report). Torrent
         * rows already name their source; web and RomM rows are claimed by their next scan.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloadable_files ADD COLUMN sourceUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE downloadable_files SET sourceUrl = torrentMagnet WHERE torrentMagnet IS NOT NULL")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_downloadable_files_consoleId_sourceUrl ON downloadable_files (consoleId, sourceUrl)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS source_scans (" +
                        "consoleId TEXT NOT NULL, " +
                        "sourceUrl TEXT NOT NULL, " +
                        "fingerprint TEXT NOT NULL, " +
                        "etag TEXT, " +
                        "lastModified TEXT, " +
                        "servedBy TEXT, " +
                        "contentHash TEXT, " +
                        "fileCount INTEGER NOT NULL, " +
                        "newFiles INTEGER NOT NULL, " +
                        "status TEXT NOT NULL, " +
                        "failure TEXT, " +
                        "httpCode INTEGER, " +
                        "detail TEXT, " +
                        "scannedAt INTEGER NOT NULL, " +
                        "runId INTEGER NOT NULL, " +
                        "runKind TEXT NOT NULL, " +
                        "PRIMARY KEY(consoleId, sourceUrl))"
                )
            }
        }

        // dl-motor
        /**
         * Track record per download origin, the validator that lets an interrupted web
         * transfer resume with If-Range, and the checksum / DAT verdict of each finished download.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS source_stats (" +
                        "origin TEXT NOT NULL PRIMARY KEY, " +
                        "successes REAL NOT NULL, " +
                        "failures REAL NOT NULL, " +
                        "hardFailures REAL NOT NULL, " +
                        "bytesPerSecond REAL NOT NULL, " +
                        "totalBytes INTEGER NOT NULL, " +
                        "lastFailure TEXT, " +
                        "lastFailureAt INTEGER NOT NULL, " +
                        "lastSuccessAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS download_resume (" +
                        "fileName TEXT NOT NULL PRIMARY KEY, " +
                        "validator TEXT, " +
                        "host TEXT, " +
                        "totalBytes INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS download_checks (" +
                        "fileName TEXT NOT NULL PRIMARY KEY, " +
                        "result TEXT NOT NULL, " +
                        "checkedAt INTEGER NOT NULL)"
                )
            }
        }

        // dl-cola
        /**
         * Place and hold of each row in the download queue. Existing rows get their insertion
         * order (unique), so moves made after the upgrade always swap two distinct places.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_history ADD COLUMN queueOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_history ADD COLUMN queueHeld INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE download_history SET queueOrder = rowid")
            }
        }

        // romm-transporte
        /** Uploads to RomM that have not finished, so they carry on from the last acknowledged chunk. */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS romm_uploads (" +
                        "uploadKey TEXT NOT NULL PRIMARY KEY, " +
                        "downloadFileName TEXT NOT NULL, " +
                        "consoleId TEXT NOT NULL, " +
                        "serverUrl TEXT NOT NULL, " +
                        "platformId INTEGER NOT NULL, " +
                        "serverName TEXT NOT NULL, " +
                        "folder TEXT NOT NULL, " +
                        "sources TEXT NOT NULL, " +
                        "archive INTEGER NOT NULL, " +
                        "totalSize INTEGER NOT NULL, " +
                        "chunkSize INTEGER NOT NULL, " +
                        "totalChunks INTEGER NOT NULL, " +
                        "uploadId TEXT, " +
                        "confirmedChunks INTEGER NOT NULL, " +
                        "sessionStartedAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL)"
                )
            }
        }

        // biblioteca
        /** Genre and release year per library game (Genre / Decade filters, recommendations) and when each console's were worked out. */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `game_traits` (" +
                        "`consoleId` TEXT NOT NULL, " +
                        "`groupKey` TEXT NOT NULL, " +
                        "`genres` INTEGER NOT NULL, " +
                        "`year` INTEGER, " +
                        "`source` TEXT NOT NULL, " +
                        "PRIMARY KEY(`consoleId`, `groupKey`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `game_traits_sync` (" +
                        "`consoleId` TEXT NOT NULL, " +
                        "`fetchedAt` INTEGER NOT NULL, " +
                        "`fileCount` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`consoleId`))"
                )
            }
        }

        // romm-catalogo
        /** The RomM server's catalogue (marks, covers, uploads) and the base of the favourites sync. */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS romm_games (" +
                        "consoleId TEXT NOT NULL, " +
                        "romId INTEGER NOT NULL, " +
                        "platformId INTEGER NOT NULL, " +
                        "fsName TEXT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "groupKey TEXT NOT NULL, " +
                        "files TEXT NOT NULL, " +
                        "hashes TEXT NOT NULL, " +
                        "coverPath TEXT NOT NULL, " +
                        "coverLargePath TEXT NOT NULL, " +
                        "userStatus TEXT NOT NULL, " +
                        "fetchedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(consoleId, romId))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_romm_games_romId ON romm_games (romId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_romm_games_consoleId_groupKey ON romm_games (consoleId, groupKey)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS romm_favourite_base (" +
                        "romId INTEGER NOT NULL, " +
                        "collectionId INTEGER NOT NULL, " +
                        "server TEXT NOT NULL, " +
                        "PRIMARY KEY(romId))"
                )
            }
        }

        // coleccion-gestion
        /**
         * Action history with undo, the trash, collections (manual and by
         * rules, games keyed by console + version group) and downloads redirected to the other
         * volume by smart storage.
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS action_history (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "type TEXT NOT NULL, " +
                        "subject TEXT NOT NULL, " +
                        "itemCount INTEGER NOT NULL, " +
                        "bytes INTEGER NOT NULL, " +
                        "undoKind TEXT, " +
                        "undoData TEXT, " +
                        "state TEXT NOT NULL, " +
                        "stateAt INTEGER)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_action_history_createdAt ON action_history (createdAt)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS trash_items (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "batchId TEXT NOT NULL, " +
                        "treeUri TEXT NOT NULL, " +
                        "originalParentUri TEXT NOT NULL, " +
                        "relativePath TEXT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "trashedUri TEXT NOT NULL, " +
                        "sizeBytes INTEGER NOT NULL, " +
                        "isDirectory INTEGER NOT NULL, " +
                        "trashedAt INTEGER NOT NULL, " +
                        "state TEXT NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trash_items_batchId ON trash_items (batchId)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS collections (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "rules TEXT, " +
                        "createdAt INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS collection_games (" +
                        "collectionId INTEGER NOT NULL, " +
                        "consoleId TEXT NOT NULL, " +
                        "groupKey TEXT NOT NULL, " +
                        "addedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(collectionId, consoleId, groupKey))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_collection_games_consoleId_groupKey ON collection_games (consoleId, groupKey)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS storage_redirects (" +
                        "fileName TEXT NOT NULL PRIMARY KEY, " +
                        "consoleId TEXT NOT NULL, " +
                        "rootUri TEXT NOT NULL, " +
                        "subPath TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL)"
                )
            }
        }

        // dl-cola 1.4
        /** Pending automatic retries survive a restart: when the next one is due and how many the chain spent. */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_history ADD COLUMN retryAt INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE download_history ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Generalises the TorBox resume ids to any debrid service (string ids); SQLite < 3.35 can't drop columns, so rebuild. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS download_history_new (" +
                        "fileName TEXT NOT NULL PRIMARY KEY, " +
                        "name TEXT NOT NULL, " +
                        "consoleId TEXT NOT NULL, " +
                        "downloadUrl TEXT NOT NULL, " +
                        "fileSize INTEGER NOT NULL, " +
                        "fileExtension TEXT NOT NULL, " +
                        "torrentFileIndex INTEGER, " +
                        "torrentMagnet TEXT, " +
                        "status TEXT NOT NULL, " +
                        "startedAt INTEGER NOT NULL, " +
                        "finishedAt INTEGER, " +
                        "debridProvider TEXT DEFAULT NULL, " +
                        "debridTorrentId TEXT DEFAULT NULL, " +
                        "debridFileId INTEGER DEFAULT NULL)"
                )
                db.execSQL(
                    "INSERT INTO download_history_new (fileName, name, consoleId, downloadUrl, fileSize, fileExtension, " +
                        "torrentFileIndex, torrentMagnet, status, startedAt, finishedAt, debridProvider, debridTorrentId, debridFileId) " +
                        "SELECT fileName, name, consoleId, downloadUrl, fileSize, fileExtension, torrentFileIndex, torrentMagnet, " +
                        "status, startedAt, finishedAt, " +
                        "CASE WHEN torboxTorrentId IS NULL THEN NULL ELSE 'TORBOX' END, " +
                        "CAST(torboxTorrentId AS TEXT), torboxFileId FROM download_history"
                )
                db.execSQL("DROP TABLE download_history")
                db.execSQL("ALTER TABLE download_history_new RENAME TO download_history")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_history ADD COLUMN torboxTorrentId INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE download_history ADD COLUMN torboxFileId INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS favourites (" +
                        "consoleId TEXT NOT NULL, " +
                        "fileName TEXT NOT NULL, " +
                        "addedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(consoleId, fileName))"
                )
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloadable_files ADD COLUMN torrentFileIndex INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE downloadable_files ADD COLUMN torrentMagnet TEXT DEFAULT NULL")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Existing rows get their key from DownloadableFileDao.backfillSearchKeys() at app start.
                db.execSQL("ALTER TABLE downloadable_files ADD COLUMN searchKey TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS download_history (" +
                        "fileName TEXT NOT NULL PRIMARY KEY, " +
                        "name TEXT NOT NULL, " +
                        "consoleId TEXT NOT NULL, " +
                        "downloadUrl TEXT NOT NULL, " +
                        "fileSize INTEGER NOT NULL, " +
                        "fileExtension TEXT NOT NULL, " +
                        "torrentFileIndex INTEGER, " +
                        "torrentMagnet TEXT, " +
                        "status TEXT NOT NULL, " +
                        "startedAt INTEGER NOT NULL, " +
                        "finishedAt INTEGER)"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE consoles ADD COLUMN shortName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE consoles ADD COLUMN folderAliases TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS game_metadata (" +
                        "lookupKey TEXT NOT NULL PRIMARY KEY, " +
                        "title TEXT NOT NULL, " +
                        "description TEXT NOT NULL, " +
                        "genres TEXT NOT NULL, " +
                        "released TEXT NOT NULL, " +
                        "developer TEXT NOT NULL, " +
                        "imageUrl TEXT NOT NULL, " +
                        "source TEXT NOT NULL, " +
                        "fetchedAt INTEGER NOT NULL)"
                )
            }
        }

        /**
         * Every migration, oldest first: the one list the app opens the database with
         * (DatabaseModule) and the migration tests replay. A new migration goes here too
         * (MigrationTest fails when one declared above is missing).
         */
        val ALL_MIGRATIONS: Array<Migration>
            get() = arrayOf(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20
            )
    }
}
