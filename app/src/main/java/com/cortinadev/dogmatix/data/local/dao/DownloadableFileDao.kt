package com.cortinadev.dogmatix.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.local.entity.FileTagEntity
import com.cortinadev.dogmatix.util.CatalogRow
import com.cortinadev.dogmatix.util.SearchNormalizer

@Dao
interface DownloadableFileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFiles(files: List<DownloadableFileEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<FileTagEntity>)

    /**
     * Tag filters: each category (regions, languages, …) is OR-ed within itself and AND-ed
     * with the others, e.g. (GBC ∨ GBA) ∧ (ES) ∧ (RETROACHIEVEMENTS). [source] narrows by origin:
     * RomM rows are recognised by their `/api/roms/{id}/content/` download URL (see RommSource).
     * [ids] (when given) restricts the result to those rows: the "similar games" view.
     */
    @Query("""
        SELECT df.id, df.name, df.fileName, df.consoleId, df.downloadUrl, df.fileSize,
               df.fileExtension, df.torrentFileIndex, df.torrentMagnet, df.groupKey,
               GROUP_CONCAT(t.tag, '|') as tags
        FROM downloadable_files df
        LEFT JOIN downloadable_file_tags t ON df.id = t.fileId
        JOIN consoles c ON df.consoleId = c.id
        JOIN manufacturers m ON c.manufacturerId = m.id
        LEFT JOIN game_traits gt ON gt.consoleId = df.consoleId AND gt.groupKey = df.groupKey
        WHERE (:query = '*' OR df.searchKey LIKE '%' || :query || '%')
          AND (:idsCount = 0 OR df.id IN (:ids))
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
          AND (:favouritesOnly = 0 OR EXISTS (
                SELECT 1 FROM favourites f WHERE f.consoleId = df.consoleId AND f.fileName = df.fileName
          ))
          AND (:source = 0
               OR (:source = 1 AND df.torrentFileIndex IS NOT NULL)
               OR (:source = 2 AND df.downloadUrl LIKE '%/api/roms/%/content/%')
               OR (:source = 3 AND df.torrentFileIndex IS NULL AND df.downloadUrl NOT LIKE '%/api/roms/%/content/%'))
          AND (:groupKeysCount = 0 OR df.groupKey IN (:groupKeys))
          AND (:genreMask = 0 OR EXISTS (
                SELECT 1 FROM game_traits g_genre WHERE g_genre.consoleId = df.consoleId AND g_genre.groupKey = df.groupKey AND (g_genre.genres & :genreMask) != 0
          ))
          AND (:decadesCount = 0 OR EXISTS (
                SELECT 1 FROM game_traits g_decade WHERE g_decade.consoleId = df.consoleId AND g_decade.groupKey = df.groupKey AND (g_decade.year / 10) * 10 IN (:decades)
          ))
        GROUP BY df.id, df.name, df.fileName, df.consoleId, df.downloadUrl, df.fileSize,
                 df.fileExtension, df.torrentFileIndex, df.torrentMagnet, df.groupKey
        ORDER BY
            CASE WHEN :sort = 0 THEN df.name END ASC,
            CASE WHEN :sort = 1 THEN df.name END DESC,
            CASE WHEN :sort = 2 THEN df.fileSize END DESC,
            CASE WHEN :sort = 3 THEN df.fileSize END ASC,
            CASE WHEN :sort = 4 THEN c.name END ASC,
            CASE WHEN :sort IN (5, 6) THEN gt.year IS NULL END ASC,
            CASE WHEN :sort = 5 THEN gt.year / 10 END ASC,
            CASE WHEN :sort = 6 THEN gt.year / 10 END DESC,
            df.name ASC
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
        favouritesOnly: Boolean,
        /** [com.cortinadev.dogmatix.data.model.SourceFilter] ordinal: 0 all, 1 torrent, 2 RomM, 3 direct HTTP. */
        source: Int,
        /** [com.cortinadev.dogmatix.data.model.SortOption] ordinal: 0 A→Z, 1 Z→A, 2 biggest first, 3 smallest first, 4 console, 5 / 6 decade old / new first. */
        sort: Int,
        limit: Int = 100,
        offset: Int = 0,
        ids: List<Long> = emptyList(),
        idsCount: Int = 0,
        /** Only these games (see [queryGameGroups]); empty = no restriction. */
        groupKeys: List<String> = emptyList(),
        groupKeysCount: Int = 0,
        /** [com.cortinadev.dogmatix.util.LibraryGenre] bits: games with any of them (see game_traits); 0 = any. */
        genreMask: Int = 0,
        /** Decades (1990…) of the first release; empty = any. A game without traits never passes either. */
        decades: List<Int> = emptyList(),
        decadesCount: Int = 0
    ): List<DownloadableFileWithTagsResult>

    /**
     * One page of games: the (console, groupKey) pairs with at least one file passing the same
     * filters as [queryFilesWithTags], in list order. Paging by game keeps every version of a
     * game on the same page.
     */
    @Query("""
        SELECT df.consoleId, df.groupKey
        FROM downloadable_files df
        JOIN consoles c ON df.consoleId = c.id
        JOIN manufacturers m ON c.manufacturerId = m.id
        LEFT JOIN game_traits gt ON gt.consoleId = df.consoleId AND gt.groupKey = df.groupKey
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
          AND (:favouritesOnly = 0 OR EXISTS (
                SELECT 1 FROM favourites f WHERE f.consoleId = df.consoleId AND f.fileName = df.fileName
          ))
          AND (:source = 0
               OR (:source = 1 AND df.torrentFileIndex IS NOT NULL)
               OR (:source = 2 AND df.downloadUrl LIKE '%/api/roms/%/content/%')
               OR (:source = 3 AND df.torrentFileIndex IS NULL AND df.downloadUrl NOT LIKE '%/api/roms/%/content/%'))
          AND (:genreMask = 0 OR EXISTS (
                SELECT 1 FROM game_traits g_genre WHERE g_genre.consoleId = df.consoleId AND g_genre.groupKey = df.groupKey AND (g_genre.genres & :genreMask) != 0
          ))
          AND (:decadesCount = 0 OR EXISTS (
                SELECT 1 FROM game_traits g_decade WHERE g_decade.consoleId = df.consoleId AND g_decade.groupKey = df.groupKey AND (g_decade.year / 10) * 10 IN (:decades)
          ))
          AND (:collectionId = 0 OR EXISTS (
                SELECT 1 FROM collection_games cg WHERE cg.collectionId = :collectionId AND cg.consoleId = df.consoleId AND cg.groupKey = df.groupKey
          ))
        GROUP BY df.consoleId, df.groupKey
        ORDER BY
            CASE WHEN :sort = 0 THEN MIN(df.name) END ASC,
            CASE WHEN :sort = 1 THEN MIN(df.name) END DESC,
            CASE WHEN :sort = 2 THEN MAX(df.fileSize) END DESC,
            CASE WHEN :sort = 3 THEN MIN(df.fileSize) END ASC,
            CASE WHEN :sort = 4 THEN MIN(c.name) END ASC,
            CASE WHEN :sort IN (5, 6) THEN MIN(gt.year) IS NULL END ASC,
            CASE WHEN :sort = 5 THEN MIN(gt.year) / 10 END ASC,
            CASE WHEN :sort = 6 THEN MIN(gt.year) / 10 END DESC,
            MIN(df.name) ASC, df.consoleId ASC, df.groupKey ASC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun queryGameGroups(
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
        favouritesOnly: Boolean,
        source: Int,
        sort: Int,
        limit: Int,
        offset: Int,
        genreMask: Int = 0,
        decades: List<Int> = emptyList(),
        decadesCount: Int = 0,
        /** Only the games of this collection (Collection → Collections); 0 = no restriction. */
        collectionId: Long = 0
    ): List<GameGroupRef>

    /** Every file of one game (all its versions), with tags, for the details card. */
    @Query("""
        SELECT df.id, df.name, df.fileName, df.consoleId, df.downloadUrl, df.fileSize,
               df.fileExtension, df.torrentFileIndex, df.torrentMagnet, df.groupKey,
               GROUP_CONCAT(t.tag, '|') as tags
        FROM downloadable_files df
        LEFT JOIN downloadable_file_tags t ON df.id = t.fileId
        WHERE df.consoleId = :consoleId AND df.groupKey = :groupKey
        GROUP BY df.id
        ORDER BY df.fileName ASC
    """)
    suspend fun getGameFiles(consoleId: String, groupKey: String): List<DownloadableFileWithTagsResult>

    /** File names of one game, to count the discs a release has (see MultiDiscService). */
    @Query("SELECT fileName FROM downloadable_files WHERE consoleId = :consoleId AND groupKey = :groupKey")
    suspend fun getGameFileNames(consoleId: String, groupKey: String): List<String>

    @Query("SELECT COUNT(*) FROM downloadable_files")
    suspend fun getFilesCount(): Int

    /** Rows whose search key contains [key] (a [com.cortinadev.dogmatix.util.SearchNormalizer.key]). */
    @Query("SELECT COUNT(*) FROM downloadable_files WHERE searchKey LIKE '%' || :key || '%'")
    suspend fun countBySearchKey(key: String): Int

    /** Id and name of rows matching a search pattern: no tags, no grouping (the similar-games lookups). */
    @Query("SELECT id, name FROM downloadable_files WHERE searchKey LIKE '%' || :pattern || '%' LIMIT :limit")
    suspend fun findIdNames(pattern: String, limit: Int): List<FileIdName>

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

    /**
     * One source's files and their tags in a single transaction (no file is ever visible without
     * its tags, and observers refresh once). [tags] runs parallel to [files], so files sharing a
     * name in different folders keep their own tags. Returns the number of tag rows written.
     */
    @Transaction
    suspend fun insertFilesWithTags(files: List<DownloadableFileEntity>, tags: List<List<String>>): Int {
        if (files.isEmpty()) return 0
        val rows = insertAll(files).zip(tags).flatMap { (id, fileTags) -> fileTags.map { FileTagEntity(fileId = id, tag = it) } }
        insertTags(rows)
        return rows.size
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

    // frontends
    /** Name and game of every row of [consoleId]: matching files on disk to library games (ES-DE favourites). */
    @Query("SELECT fileName, groupKey FROM downloadable_files WHERE consoleId = :consoleId")
    suspend fun getFileNamesAndGroups(consoleId: String): List<FileNameGroup>

    // biblioteca

    /**
     * Games per first-character bucket of their list name (`MIN(name)`, the A→Z / Z→A sort key)
     * among those passing the same filters as [queryGameGroups]: what letter jumps need to reach
     * rows that are not loaded yet. The buckets are `com.cortinadev.dogmatix.util.LetterJump`'s.
     */
    @Query("""
        SELECT bucket, COUNT(*) AS games FROM (
            SELECT CASE
                    WHEN substr(MIN(df.name), 1, 1) < 'A' THEN '0'
                    WHEN substr(MIN(df.name), 1, 1) <= 'Z' THEN substr(MIN(df.name), 1, 1)
                    WHEN substr(MIN(df.name), 1, 1) < 'a' THEN '1'
                    WHEN substr(MIN(df.name), 1, 1) <= 'z' THEN substr(MIN(df.name), 1, 1)
                    ELSE '2' END AS bucket
            FROM downloadable_files df
            JOIN consoles c ON df.consoleId = c.id
            JOIN manufacturers m ON c.manufacturerId = m.id
            WHERE (:query = '*' OR df.searchKey LIKE '%' || :query || '%')
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
              AND (:favouritesOnly = 0 OR EXISTS (
                    SELECT 1 FROM favourites f WHERE f.consoleId = df.consoleId AND f.fileName = df.fileName
              ))
              AND (:source = 0
                   OR (:source = 1 AND df.torrentFileIndex IS NOT NULL)
                   OR (:source = 2 AND df.downloadUrl LIKE '%/api/roms/%/content/%')
                   OR (:source = 3 AND df.torrentFileIndex IS NULL AND df.downloadUrl NOT LIKE '%/api/roms/%/content/%'))
              AND (:genreMask = 0 OR EXISTS (
                    SELECT 1 FROM game_traits g_genre WHERE g_genre.consoleId = df.consoleId AND g_genre.groupKey = df.groupKey AND (g_genre.genres & :genreMask) != 0
              ))
              AND (:decadesCount = 0 OR EXISTS (
                    SELECT 1 FROM game_traits g_decade WHERE g_decade.consoleId = df.consoleId AND g_decade.groupKey = df.groupKey AND (g_decade.year / 10) * 10 IN (:decades)
              ))
              AND (:collectionId = 0 OR EXISTS (
                    SELECT 1 FROM collection_games cg WHERE cg.collectionId = :collectionId AND cg.consoleId = df.consoleId AND cg.groupKey = df.groupKey
              ))
            GROUP BY df.consoleId, df.groupKey
        ) GROUP BY bucket
    """)
    suspend fun letterBuckets(
        query: String,
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
        favouritesOnly: Boolean,
        source: Int,
        genreMask: Int,
        decades: List<Int>,
        decadesCount: Int,
        collectionId: Long = 0
    ): List<LetterBucket>

    /** Library rows per console (the recommendations' "highlights" and the traits' change check). */
    @Query("SELECT consoleId, COUNT(*) AS files FROM downloadable_files GROUP BY consoleId")
    suspend fun consoleFileCounts(): List<ConsoleFileCount>

    /** Each game of a console with a name to match it by (its list name). */
    @Query("SELECT groupKey, MIN(name) AS name FROM downloadable_files WHERE consoleId = :consoleId GROUP BY groupKey")
    suspend fun gameNames(consoleId: String): List<GameName>

    /** Which of [groupKeys] are games of [consoleId], with their list name. */
    @Query("SELECT consoleId, groupKey, MIN(name) AS name FROM downloadable_files WHERE consoleId = :consoleId AND groupKey IN (:groupKeys) GROUP BY groupKey")
    suspend fun gamesIn(consoleId: String, groupKeys: List<String>): List<GameRef>

    /**
     * The games of [consoleIds] with the most files first: regional releases and revisions pile
     * up on the well-known games, so this is the offline stand-in for "popular". With a
     * [genreMask], only games with one of those genres (see game_traits); [GameCandidate.genres]
     * is 0 when unknown.
     */
    @Query("""
        SELECT df.consoleId, df.groupKey, MIN(df.name) AS name, COUNT(*) AS files, IFNULL(MAX(gt.genres), 0) AS genres
        FROM downloadable_files df
        LEFT JOIN game_traits gt ON gt.consoleId = df.consoleId AND gt.groupKey = df.groupKey
        WHERE df.consoleId IN (:consoleIds)
          AND (:genreMask = 0 OR (gt.genres & :genreMask) != 0)
        GROUP BY df.consoleId, df.groupKey
        ORDER BY files DESC, name ASC
        LIMIT :limit
    """)
    suspend fun notableGames(consoleIds: List<String>, genreMask: Int, limit: Int): List<GameCandidate>

    /** Like [findIdNames], with the game each row belongs to. */
    @Query("SELECT id, name, consoleId, groupKey FROM downloadable_files WHERE searchKey LIKE '%' || :pattern || '%' LIMIT :limit")
    suspend fun findGameRows(pattern: String, limit: Int): List<GameRow>

    // coleccion-analisis: the collection tools match what is on disk with the catalogue.

    /** Rows of the given games in one console (the index on consoleId + groupKey serves it). */
    @Query("SELECT id, consoleId, groupKey, fileName, fileSize FROM downloadable_files WHERE consoleId = :consoleId AND groupKey IN (:groupKeys)")
    suspend fun catalogRowsForConsole(consoleId: String, groupKeys: List<String>): List<CatalogRow>

    /** Rows of the given games in any console: for files whose folder says nothing about it. */
    @Query("SELECT id, consoleId, groupKey, fileName, fileSize FROM downloadable_files WHERE groupKey IN (:groupKeys)")
    suspend fun catalogRowsForGroups(groupKeys: List<String>): List<CatalogRow>

    /** Distinct games (group keys) per console. */
    @Query("SELECT consoleId, COUNT(DISTINCT groupKey) AS games FROM downloadable_files GROUP BY consoleId")
    suspend fun gameCountsByConsole(): List<ConsoleGameCount>

    @Query("SELECT DISTINCT consoleId, fileExtension FROM downloadable_files")
    suspend fun extensionsByConsole(): List<ConsoleExtension>
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
    val groupKey: String = "",
    val tags: String?
)

data class GameGroupRef(val consoleId: String, val groupKey: String)

data class ConsoleWithFileCount(
    val id: String,
    val name: String,
    val manufacturerId: String,
    val urls: String,
    val fileCount: Int
)

data class FileIdName(val id: Long, val name: String)

// frontends
data class FileNameGroup(val fileName: String, val groupKey: String)

// biblioteca
data class LetterBucket(val bucket: String, val games: Int)

data class ConsoleFileCount(val consoleId: String, val files: Int)

data class GameName(val groupKey: String, val name: String)

data class GameRef(val consoleId: String, val groupKey: String, val name: String)

data class GameCandidate(val consoleId: String, val groupKey: String, val name: String, val files: Int, val genres: Int)

data class GameRow(val id: Long, val name: String, val consoleId: String, val groupKey: String)
