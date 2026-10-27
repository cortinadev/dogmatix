package com.cortinadev.dogmatix.data.local

import android.database.sqlite.SQLiteDatabase

/**
 * The schema of a database written by an old release, as its Room created it on a fresh install
 * (the `createAllTables` of that version), to replay every migration since.
 *
 * - v9: Dogmatix 1.1 – 1.2 (tag v1.2): the entities of that tag, i.e. today's minus what
 *   MIGRATION_9_10 … MIGRATION_18_19 add (consoles.color, downloadable_files.groupKey/sourceUrl
 *   and their indices, download_history.failureReason/queueOrder/queueHeld, and the tables
 *   covers onwards). `download_history` already has the debrid columns of MIGRATION_8_9.
 * - v6: Dogmatix 1.0 (tag v1.0): v9 without `favourites` (MIGRATION_6_7) and with the
 *   `download_history` of MIGRATION_3_4 (before the TorBox columns of 7_8 and their rebuild in 8_9).
 */
object LegacySchemas {

    private val common = listOf(
        "CREATE TABLE IF NOT EXISTS `manufacturers` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `consoles` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `manufacturerId` TEXT NOT NULL, `urls` TEXT NOT NULL, `shortName` TEXT NOT NULL DEFAULT '', `folderAliases` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_consoles_manufacturerId` ON `consoles` (`manufacturerId`)",
        "CREATE TABLE IF NOT EXISTS `downloadable_files` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `fileName` TEXT NOT NULL, `consoleId` TEXT NOT NULL, `downloadUrl` TEXT NOT NULL, `fileSize` INTEGER NOT NULL, `fileExtension` TEXT NOT NULL, `torrentFileIndex` INTEGER, `torrentMagnet` TEXT, `searchKey` TEXT NOT NULL DEFAULT '', FOREIGN KEY(`consoleId`) REFERENCES `consoles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_downloadable_files_consoleId` ON `downloadable_files` (`consoleId`)",
        "CREATE TABLE IF NOT EXISTS `downloadable_file_tags` (`fileId` INTEGER NOT NULL, `tag` TEXT NOT NULL, PRIMARY KEY(`fileId`, `tag`), FOREIGN KEY(`fileId`) REFERENCES `downloadable_files`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_downloadable_file_tags_fileId` ON `downloadable_file_tags` (`fileId`)",
        "CREATE INDEX IF NOT EXISTS `index_downloadable_file_tags_tag` ON `downloadable_file_tags` (`tag`)",
        "CREATE VIRTUAL TABLE IF NOT EXISTS `downloadable_files_fts` USING FTS4(`name` TEXT NOT NULL, content=`downloadable_files`)",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_downloadable_files_fts_BEFORE_UPDATE BEFORE UPDATE ON `downloadable_files` BEGIN DELETE FROM `downloadable_files_fts` WHERE `docid`=OLD.`rowid`; END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_downloadable_files_fts_BEFORE_DELETE BEFORE DELETE ON `downloadable_files` BEGIN DELETE FROM `downloadable_files_fts` WHERE `docid`=OLD.`rowid`; END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_downloadable_files_fts_AFTER_UPDATE AFTER UPDATE ON `downloadable_files` BEGIN INSERT INTO `downloadable_files_fts`(`docid`, `name`) VALUES (NEW.`rowid`, NEW.`name`); END",
        "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_downloadable_files_fts_AFTER_INSERT AFTER INSERT ON `downloadable_files` BEGIN INSERT INTO `downloadable_files_fts`(`docid`, `name`) VALUES (NEW.`rowid`, NEW.`name`); END",
        "CREATE TABLE IF NOT EXISTS `game_metadata` (`lookupKey` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL, `genres` TEXT NOT NULL, `released` TEXT NOT NULL, `developer` TEXT NOT NULL, `imageUrl` TEXT NOT NULL, `source` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`lookupKey`))",
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        // The identity of the old version does not matter: Room rewrites it after migrating.
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'legacy-identity')"
    )

    private const val HISTORY_V9 =
        "CREATE TABLE IF NOT EXISTS `download_history` (`fileName` TEXT NOT NULL, `name` TEXT NOT NULL, `consoleId` TEXT NOT NULL, `downloadUrl` TEXT NOT NULL, `fileSize` INTEGER NOT NULL, `fileExtension` TEXT NOT NULL, `torrentFileIndex` INTEGER, `torrentMagnet` TEXT, `status` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, `debridProvider` TEXT DEFAULT NULL, `debridTorrentId` TEXT DEFAULT NULL, `debridFileId` INTEGER DEFAULT NULL, PRIMARY KEY(`fileName`))"

    private const val HISTORY_V6 =
        "CREATE TABLE IF NOT EXISTS `download_history` (`fileName` TEXT NOT NULL, `name` TEXT NOT NULL, `consoleId` TEXT NOT NULL, `downloadUrl` TEXT NOT NULL, `fileSize` INTEGER NOT NULL, `fileExtension` TEXT NOT NULL, `torrentFileIndex` INTEGER, `torrentMagnet` TEXT, `status` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `finishedAt` INTEGER, PRIMARY KEY(`fileName`))"

    private const val FAVOURITES =
        "CREATE TABLE IF NOT EXISTS `favourites` (`consoleId` TEXT NOT NULL, `fileName` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`consoleId`, `fileName`))"

    fun create(db: SQLiteDatabase, version: Int) {
        val statements = when (version) {
            9 -> common + HISTORY_V9 + FAVOURITES
            6 -> common + HISTORY_V6
            else -> error("No schema for version $version")
        }
        statements.forEach(db::execSQL)
        db.version = version
    }
}
