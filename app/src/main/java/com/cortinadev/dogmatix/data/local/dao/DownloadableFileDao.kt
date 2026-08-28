package com.cortinadev.dogmatix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.local.entity.FileTagEntity
import com.cortinadev.dogmatix.util.SearchNormalizer

@Dao
interface DownloadableFileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFiles(files: List<DownloadableFileEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<FileTagEntity>)

    /**
     * Tag filters: each category (regions, languages, …) is OR-ed within itself and AND-ed
     * with the others, e.g. (GBC ∨ GBA) ∧ (ES) ∧ (RETROACHIEVEMENTS).
     */
    @Query("""
        SELECT df.id, df.name, df.fileName, df.consoleId, df.downloadUrl, df.fileSize,
               df.fileExtension, df.torrentFileIndex, df.torrentMagnet,
               GROUP_CONCAT(t.tag, '|') as tags
        FROM downloadable_files df
        LEFT JOIN downloadable_file_tags t ON df.id = t.fileId
        JOIN consoles c ON df.consoleId = c.id
        JOIN manufacturers m ON c.manufacturerId = m.id
        WHERE (:query = '*' OR df.searchKey LIKE '%' || :query || '%')
          AND (:manufacturer IS NULL OR m.name = :manufacturer)
          AND (:consoleIdsCount = 0 OR df.consoleId IN (:consoleIds))
          AND (:regionsCount = 0 OR EXISTS (
                SELECT 1 FROM downloadable_file_tags t_regions WHERE t_regions.fileId = df.id AND t_regions.tag IN (:regions)
          ))
          AND (:languagesCount = 0 OR EXISTS (
                SELECT 1 FROM downloadable_file_tags t_languages WHERE t_languages.fileId = df.id AND t_languages.tag IN (:languages)
          ))
          AND (:videoStandardsCount = 0 OR EXISTS (
                SELECT 1 FROM downloadable_file_tags t_videoStandards WHERE t_videoStandards.fileId = df.id AND t_videoStandards.tag IN (:videoStandards)
          ))
          AND (:contentTypesCount = 0 OR EXISTS (
                SELECT 1 FROM downloadable_file_tags t_contentTypes WHERE t_contentTypes.fileId = df.id AND t_contentTypes.tag IN (:contentTypes)
          ))
          AND (:fileTypesCount = 0 OR EXISTS (
                SELECT 1 FROM downloadable_file_tags t_fileTypes WHERE t_fileTypes.fileId = df.id AND t_fileTypes.tag IN (:fileTypes)
          ))
        GROUP BY df.id, df.name, df.fileName, df.consoleId, df.downloadUrl, df.fileSize,
                 df.fileExtension, df.torrentFileIndex, df.torrentMagnet
        ORDER BY
            CASE WHEN :sortAsc = 1 THEN df.name END ASC,
            CASE WHEN :sortAsc = 0 THEN df.name END DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun queryFilesWithTags(
        query: String,
        manufacturer: String?,
        consoleIds: List<String>,
        consoleIdsCount: Int,
        regions: List<String>,
        regionsCount: Int,
        languages: List<String>,
        languagesCount: Int,
        videoStandards: List<String>,
        videoStandardsCount: Int,
        contentTypes: List<String>,
        contentTypesCount: Int,
        fileTypes: List<String>,
        fileTypesCount: Int,
        sortAsc: Boolean,
        limit: Int = 100,
        offset: Int = 0
    ): List<DownloadableFileWithTagsResult>

    @Query("SELECT COUNT(*) FROM downloadable_files")
    suspend fun getFilesCount(): Int

    @Query("SELECT * FROM downloadable_files WHERE fileName = :fileName LIMIT 1")
    suspend fun getFileByFileName(fileName: String): DownloadableFileEntity?

    @Query("SELECT tag FROM downloadable_file_tags WHERE fileId = :fileId ORDER BY tag ASC")
    suspend fun getTagsForFile(fileId: Long): List<String>

    @Query("""
        SELECT DISTINCT t.tag
        FROM downloadable_file_tags t
        JOIN downloadable_files df ON t.fileId = df.id
        JOIN consoles c ON df.consoleId = c.id
        JOIN manufacturers m ON c.manufacturerId = m.id
        WHERE (:query = '*' OR df.searchKey LIKE '%' || :query || '%')
          AND (:manufacturer IS NULL OR m.name = :manufacturer)
          AND (:consoleIdsCount = 0 OR df.consoleId IN (:consoleIds))
        ORDER BY t.tag ASC
    """)
    suspend fun getAvailableTags(
        query: String,
        manufacturer: String?,
        consoleIds: List<String>,
        consoleIdsCount: Int
    ): List<String>

    @Query("""
        SELECT DISTINCT c.id, c.name, c.manufacturerId, c.urls, COUNT(df.id) as fileCount
        FROM consoles c
        JOIN downloadable_files df ON c.id = df.consoleId
        JOIN manufacturers m ON c.manufacturerId = m.id
        WHERE (:query = '*' OR df.searchKey LIKE '%' || :query || '%')
          AND (:manufacturer IS NULL OR m.name = :manufacturer)
        GROUP BY c.id, c.name, c.manufacturerId, c.urls
        HAVING fileCount > 0
        ORDER BY c.name ASC
    """)
    suspend fun getConsolesWithFiles(
        query: String,
        manufacturer: String?
    ): List<ConsoleWithFileCount>

    @Transaction
    suspend fun insertFilesWithTags(files: List<DownloadableFileEntity>, tags: List<FileTagEntity>) {
        val ids = insertAll(files)
        val tagsWithIds = tags.mapIndexed { index, tag ->
            tag.copy(fileId = ids.getOrNull(index) ?: 0L)
        }
        insertTags(tagsWithIds)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(files: List<DownloadableFileEntity>): List<Long>

    @Query("DELETE FROM downloadable_files")
    suspend fun clearAll()

    @Query("SELECT id, name FROM downloadable_files WHERE searchKey = ''")
    suspend fun getFilesMissingSearchKey(): List<FileIdName>

    @Query("UPDATE downloadable_files SET searchKey = :searchKey WHERE id = :id")
    suspend fun updateSearchKey(id: Long, searchKey: String)

    /** Fills [DownloadableFileEntity.searchKey] for rows indexed before the column existed. */
    @Transaction
    suspend fun backfillSearchKeys() {
        getFilesMissingSearchKey().forEach { updateSearchKey(it.id, SearchNormalizer.key(it.name)) }
    }

    @Query("DELETE FROM downloadable_file_tags WHERE fileId IN (SELECT id FROM downloadable_files WHERE consoleId = :consoleId)")
    suspend fun deleteTagsByConsoleId(consoleId: String)

    @Query("DELETE FROM downloadable_files WHERE consoleId = :consoleId")
    suspend fun deleteFilesByConsoleIdInternal(consoleId: String)

    @Transaction
    suspend fun deleteFilesByConsoleId(consoleId: String) {
        deleteTagsByConsoleId(consoleId)
        deleteFilesByConsoleIdInternal(consoleId)
    }
}

data class DownloadableFileWithTagsResult(
    val id: Long,
    val name: String,
    val fileName: String,
    val consoleId: String,
    val downloadUrl: String,
    val fileSize: Long,
    val fileExtension: String,
    val torrentFileIndex: Int?,
    val torrentMagnet: String?,
    val tags: String?
)

data class ConsoleWithFileCount(
    val id: String,
    val name: String,
    val manufacturerId: String,
    val urls: String,
    val fileCount: Int
)

data class FileIdName(val id: Long, val name: String)
