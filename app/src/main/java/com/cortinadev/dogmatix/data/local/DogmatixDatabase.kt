package com.cortinadev.dogmatix.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cortinadev.dogmatix.data.local.dao.ConsoleDao
import com.cortinadev.dogmatix.data.local.dao.DownloadHistoryDao
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.dao.FavouriteDao
import com.cortinadev.dogmatix.data.local.dao.GameMetadataDao
import com.cortinadev.dogmatix.data.local.dao.ManufacturerDao
import com.cortinadev.dogmatix.data.local.entity.ConsoleEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadHistoryEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.local.entity.FavouriteEntity
import com.cortinadev.dogmatix.data.local.entity.FileTagEntity
import com.cortinadev.dogmatix.data.local.entity.GameMetadataEntity
import com.cortinadev.dogmatix.data.local.entity.ManufacturerEntity
import com.cortinadev.dogmatix.data.local.queries.DownloadableFileFts

@Database(
    entities = [ManufacturerEntity::class, ConsoleEntity::class, DownloadableFileEntity::class, FileTagEntity::class, DownloadableFileFts::class, DownloadHistoryEntity::class, GameMetadataEntity::class, FavouriteEntity::class],
    version = 9,
    exportSchema = false
)
abstract class DogmatixDatabase : RoomDatabase() {
    abstract fun downloadableFileDao(): DownloadableFileDao
    abstract fun consoleDao(): ConsoleDao
    abstract fun manufacturerDao(): ManufacturerDao
    abstract fun downloadHistoryDao(): DownloadHistoryDao
    abstract fun gameMetadataDao(): GameMetadataDao
    abstract fun favouriteDao(): FavouriteDao

    companion object {
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
    }
}
