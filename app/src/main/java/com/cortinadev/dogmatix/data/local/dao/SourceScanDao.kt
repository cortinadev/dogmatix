package com.cortinadev.dogmatix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.local.entity.FileTagEntity
import com.cortinadev.dogmatix.data.local.entity.SourceScanEntity
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.util.ListingRenames
import com.cortinadev.dogmatix.util.SourceKind
import kotlinx.coroutines.flow.Flow

/**
 * The library rows seen per source, and the last scan of each source. Rows indexed before
 * sources were tracked have an empty `sourceUrl` ("legacy" below): a source that lists a file
 * of the same name takes its place, and a full scan in which every source of the console
 * answered drops whatever no source claimed.
 */
@Dao
interface SourceScanDao {

    // ---- Scan results -------------------------------------------------------------------------

    @Query("SELECT * FROM source_scans WHERE consoleId = :consoleId AND sourceUrl = :sourceUrl")
    suspend fun get(consoleId: String, sourceUrl: String): SourceScanEntity?

    @Query("SELECT * FROM source_scans")
    suspend fun all(): List<SourceScanEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(scan: SourceScanEntity)

    @Query("DELETE FROM source_scans WHERE consoleId = :consoleId AND sourceUrl = :sourceUrl")
    suspend fun delete(consoleId: String, sourceUrl: String)

    /** The results of every source of a console (the console was deleted). */
    @Query("DELETE FROM source_scans WHERE consoleId = :consoleId")
    suspend fun deleteConsole(consoleId: String)

    /** Every source of the latest scan run, with its console's name (the "Last scan" report). */
    @Query("""
        SELECT s.consoleId, c.name AS consoleName, s.sourceUrl, s.status, s.fileCount, s.newFiles,
               s.failure, s.httpCode, s.detail, s.servedBy, s.scannedAt, s.runId, s.runKind
        FROM source_scans s JOIN consoles c ON c.id = s.consoleId
        WHERE s.runId = (SELECT MAX(runId) FROM source_scans)
        ORDER BY c.name COLLATE NOCASE, s.sourceUrl
    """)
    fun lastRun(): Flow<List<ScanReportRow>>

    // ---- Rows per source ----------------------------------------------------------------------

    @Query("SELECT consoleId, sourceUrl, COUNT(*) AS rowCount FROM downloadable_files GROUP BY consoleId, sourceUrl")
    suspend fun rowCounts(): List<SourceRowCount>

    @Query("SELECT COUNT(*) FROM downloadable_files WHERE consoleId = :consoleId AND sourceUrl = :sourceUrl")
    suspend fun countRows(consoleId: String, sourceUrl: String): Int

    @Query("SELECT fileName, groupKey FROM downloadable_files WHERE consoleId = :consoleId AND sourceUrl = :sourceUrl")
    suspend fun sourceNames(consoleId: String, sourceUrl: String): List<FileNameKey>

    @Query("SELECT EXISTS(SELECT 1 FROM downloadable_files WHERE consoleId = :consoleId AND sourceUrl = '')")
    suspend fun hasLegacyRows(consoleId: String): Boolean

    @Query("SELECT fileName, groupKey FROM downloadable_files WHERE consoleId = :consoleId AND sourceUrl = '' AND fileName IN (:names)")
    suspend fun legacyNames(consoleId: String, names: List<String>): List<FileNameKey>

    @Query("DELETE FROM downloadable_file_tags WHERE fileId IN (SELECT id FROM downloadable_files WHERE consoleId = :consoleId AND sourceUrl = '' AND fileName IN (:names))")
    suspend fun deleteLegacyTags(consoleId: String, names: List<String>)

    @Query("DELETE FROM downloadable_files WHERE consoleId = :consoleId AND sourceUrl = '' AND fileName IN (:names)")
    suspend fun deleteLegacyFiles(consoleId: String, names: List<String>)

    @Query("DELETE FROM downloadable_file_tags WHERE fileId IN (SELECT id FROM downloadable_files WHERE consoleId = :consoleId AND sourceUrl = :sourceUrl)")
    suspend fun deleteSourceTags(consoleId: String, sourceUrl: String)

    @Query("DELETE FROM downloadable_files WHERE consoleId = :consoleId AND sourceUrl = :sourceUrl")
    suspend fun deleteSourceFiles(consoleId: String, sourceUrl: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFiles(files: List<DownloadableFileEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<FileTagEntity>)

    /** Removes one source's rows (a source deleted or switched off); '' removes the console's legacy rows. */
    @Transaction
    suspend fun deleteSource(consoleId: String, sourceUrl: String) {
        deleteSourceTags(consoleId, sourceUrl)
        deleteSourceFiles(consoleId, sourceUrl)
    }

    /** A source deleted: its rows and its last scan result, in one transaction. */
    @Transaction
    suspend fun forgetSource(consoleId: String, sourceUrl: String) {
        deleteSource(consoleId, sourceUrl)
        delete(consoleId, sourceUrl)
    }

    /**
     * Replaces the rows of one source with [files] (tags parallel to them) in one transaction, so
     * the library never shows the source half-written. Legacy rows of the console with the same
     * file names go too (they were this source's before it was tracked). A file counts as new
     * when the source (or the legacy rows) did not list it; on a source's first scan nothing does.
     * A web row whose name changed with the listing parser keeps its ★ and its Downloads row
     * ([moveRenamedKeys]) and does not count as new.
     */
    @Transaction
    suspend fun replaceSource(consoleId: String, sourceUrl: String, files: List<DownloadableFileEntity>, tags: List<List<String>>): SourceRowsWritten {
        val known = HashMap<String, String>()
        sourceNames(consoleId, sourceUrl).forEach { known[it.fileName] = it.groupKey }
        if (SourceKind.of(sourceUrl) == SourceKind.WEB) {
            // Web rows once named after their whole link: what is keyed by that name follows them (once).
            val renames = ListingRenames.pairs(known.keys, files.map { it.fileName })
            moveRenamedKeys(consoleId, renames)
            renames.forEach { (from, to) -> known[to] = known.getValue(from) }
        }
        if (hasLegacyRows(consoleId)) {
            files.map { it.fileName }.distinct().chunked(SQL_CHUNK).forEach { chunk ->
                legacyNames(consoleId, chunk).forEach { known.putIfAbsent(it.fileName, it.groupKey) }
                deleteLegacyTags(consoleId, chunk)
                deleteLegacyFiles(consoleId, chunk)
            }
        }
        deleteSource(consoleId, sourceUrl)
        val stamped = files.map { it.copy(id = 0, consoleId = consoleId, sourceUrl = sourceUrl) }
        val ids = if (stamped.isEmpty()) emptyList() else insertFiles(stamped)
        val tagRows = ids.zip(tags).flatMap { (id, fileTags) -> fileTags.distinct().map { FileTagEntity(fileId = id, tag = it) } }
        if (tagRows.isNotEmpty()) insertTags(tagRows)
        if (known.isEmpty()) return SourceRowsWritten(tagRows.size, 0, 0)
        val fresh = stamped.filter { it.fileName !in known }
        val knownGames = known.values.toHashSet()
        return SourceRowsWritten(tagRows.size, fresh.size, fresh.map { it.groupKey }.filter { it !in knownGames }.toSet().size)
    }

    /** Every (console, source) that has rows; '' = legacy rows. */
    @Query("SELECT DISTINCT consoleId, sourceUrl FROM downloadable_files")
    suspend fun indexedSources(): List<IndexedSource>

    // ---- File names that changed (see ListingRenames) -----------------------------------------
    // Covers are not moved: they are a cache, looked up again under the new name (the old look-up
    // used the whole link as the name). Collections and game traits go by game, not by file name.

    @Query("SELECT fileName FROM favourites WHERE consoleId = :consoleId AND fileName IN (:names)")
    suspend fun favouriteNamesAmong(consoleId: String, names: List<String>): List<String>

    @Query("""
        UPDATE favourites SET fileName = :newName WHERE consoleId = :consoleId AND fileName = :oldName
        AND NOT EXISTS (SELECT 1 FROM favourites WHERE consoleId = :consoleId AND fileName = :newName)
    """)
    suspend fun renameFavourite(consoleId: String, oldName: String, newName: String)

    @Query("SELECT fileName, consoleId, status FROM download_history WHERE fileName IN (:names)")
    suspend fun downloadsAmong(names: List<String>): List<DownloadNameRow>

    @Query("""
        UPDATE download_history SET fileName = :newName WHERE fileName = :oldName AND consoleId = :consoleId
        AND NOT EXISTS (SELECT 1 FROM download_history WHERE fileName = :newName)
    """)
    suspend fun renameDownload(consoleId: String, oldName: String, newName: String)

    @Query("""
        UPDATE download_resume SET fileName = :newName WHERE fileName = :oldName
        AND NOT EXISTS (SELECT 1 FROM download_resume WHERE fileName = :newName)
    """)
    suspend fun renameResume(oldName: String, newName: String)

    @Query("""
        UPDATE download_checks SET fileName = :newName WHERE fileName = :oldName
        AND NOT EXISTS (SELECT 1 FROM download_checks WHERE fileName = :newName)
    """)
    suspend fun renameCheck(oldName: String, newName: String)

    @Query("""
        UPDATE storage_redirects SET fileName = :newName WHERE fileName = :oldName AND consoleId = :consoleId
        AND NOT EXISTS (SELECT 1 FROM storage_redirects WHERE fileName = :newName)
    """)
    suspend fun renameRedirect(consoleId: String, oldName: String, newName: String)

    @Query("UPDATE romm_uploads SET downloadFileName = :newName WHERE downloadFileName = :oldName AND consoleId = :consoleId")
    suspend fun renameUploadSource(consoleId: String, oldName: String, newName: String)

    /**
     * Moves what is keyed by the old file names of [renames] (old → new, one console) to the new
     * ones: the ★ always (unless the new name has one already), the Downloads row with its resume
     * state, checksum verdict, storage redirect and RomM upload when [ListingRenames.forDownloads]
     * allows it. Runs inside [replaceSource]'s transaction.
     */
    suspend fun moveRenamedKeys(consoleId: String, renames: Map<String, String>) {
        if (renames.isEmpty()) return
        val olds = renames.keys.toList()
        olds.chunked(SQL_CHUNK).flatMap { favouriteNamesAmong(consoleId, it) }
            .forEach { renameFavourite(consoleId, it, renames.getValue(it)) }
        val history = (olds + renames.values).distinct().chunked(SQL_CHUNK).flatMap { downloadsAmong(it) }
            .associate { it.fileName to ListingRenames.Download(it.consoleId, it.status) }
        ListingRenames.forDownloads(renames, consoleId, history, BUSY_DOWNLOADS).forEach { (from, to) ->
            renameDownload(consoleId, from, to)
            renameResume(from, to)
            renameCheck(from, to)
            renameRedirect(consoleId, from, to)
            renameUploadSource(consoleId, from, to)
        }
    }

    companion object {
        /** Bound variables per IN (…): SQLite before 3.32 (Android 10) allows 999. */
        const val SQL_CHUNK = 500

        /** Downloads with a job writing their row under its name right now. */
        val BUSY_DOWNLOADS: Set<String> = setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.COPYING, DownloadStatus.UNZIPPING)
            .mapTo(HashSet()) { it.name }
    }
}

data class ScanReportRow(
    val consoleId: String,
    val consoleName: String,
    val sourceUrl: String,
    val status: String,
    val fileCount: Int,
    val newFiles: Int,
    val failure: String?,
    val httpCode: Int?,
    val detail: String?,
    val servedBy: String?,
    val scannedAt: Long,
    val runId: Long,
    val runKind: String
)

data class SourceRowCount(val consoleId: String, val sourceUrl: String, val rowCount: Int)

data class FileNameKey(val fileName: String, val groupKey: String)

data class IndexedSource(val consoleId: String, val sourceUrl: String)

/** A Downloads row by name, for [SourceScanDao.moveRenamedKeys]. */
data class DownloadNameRow(val fileName: String, val consoleId: String, val status: String)

/** What [SourceScanDao.replaceSource] wrote: tag rows, files the source did not list before and the games they add. */
data class SourceRowsWritten(val tags: Int, val newFiles: Int, val newGames: Int)
