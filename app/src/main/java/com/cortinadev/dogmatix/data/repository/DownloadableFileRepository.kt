package com.cortinadev.dogmatix.data.repository

import com.cortinadev.dogmatix.data.local.dao.ConsoleWithFileCount
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.dao.FileIdName
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileWithTagsResult
import com.cortinadev.dogmatix.data.local.dao.GameCandidate
import com.cortinadev.dogmatix.data.local.dao.GameName
import com.cortinadev.dogmatix.data.local.dao.GameRef
import com.cortinadev.dogmatix.data.local.dao.GameRow
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.CategorizedTags
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.model.GameVersion
import com.cortinadev.dogmatix.data.model.LibraryGame
import com.cortinadev.dogmatix.data.model.VersionPrefs
import com.cortinadev.dogmatix.data.model.TagCategorizer
import com.cortinadev.dogmatix.data.model.SortOption
import com.cortinadev.dogmatix.data.model.SourceFilter
import com.cortinadev.dogmatix.data.model.TagKind
import com.cortinadev.dogmatix.data.service.SourceStatsService
import com.cortinadev.dogmatix.util.DownloadOrigins
import com.cortinadev.dogmatix.util.LetterJump
import com.cortinadev.dogmatix.util.LibraryGenre
import com.cortinadev.dogmatix.util.SearchNormalizer
import com.cortinadev.dogmatix.util.VersionGrouping
import javax.inject.Inject
import javax.inject.Singleton

/** Keys bound in one query; SQLite on older Android allows 999 values in all. */
private const val MAX_KEYS_PER_QUERY = 500

@Singleton
class DownloadableFileRepository @Inject constructor(
    private val dao: DownloadableFileDao,
    private val sourceStats: SourceStatsService
) {
    suspend fun searchFilesWithTags(
        query: String,
        manufacturer: String? = null,
        consoleIds: Set<String> = emptySet(),
        tags: Set<String> = emptySet(),
        favouritesOnly: Boolean = false,
        source: SourceFilter = SourceFilter.ALL,
        sort: SortOption = SortOption.NAME_ASC,
        limit: Int = 100,
        offset: Int = 0,
        /** Only these rows (the "similar games" view); empty = no restriction. */
        ids: List<Long> = emptyList(),
        genres: Set<LibraryGenre> = emptySet(),
        decades: Set<Int> = emptySet()
    ): List<DownloadableFileWithTags> {
        // Same-kind tags are OR-ed, different kinds are AND-ed (see the DAO query).
        val byKind = TagCategorizer.groupByKind(tags)
        fun kind(k: TagKind) = byKind[k].orEmpty().toList()
        val results = dao.queryFilesWithTags(
            query = searchPattern(query),
            manufacturer = manufacturer,
            consoleIds = consoleIds.toList(),
            consoleIdsCount = consoleIds.size,
            regions = kind(TagKind.REGION),
            regionsCount = kind(TagKind.REGION).size,
            languages = kind(TagKind.LANGUAGE),
            languagesCount = kind(TagKind.LANGUAGE).size,
            videoStandards = kind(TagKind.VIDEO_STANDARD),
            videoStandardsCount = kind(TagKind.VIDEO_STANDARD).size,
            contentTypes = kind(TagKind.CONTENT_TYPE),
            contentTypesCount = kind(TagKind.CONTENT_TYPE).size,
            fileTypes = kind(TagKind.FILE_TYPE),
            fileTypesCount = kind(TagKind.FILE_TYPE).size,
            favouritesOnly = favouritesOnly,
            source = source.ordinal,
            sort = sort.ordinal,
            limit = limit,
            offset = offset,
            ids = ids,
            idsCount = ids.size,
            genreMask = LibraryGenre.maskOfGenres(genres),
            decades = decades.toList(),
            decadesCount = decades.size
        )
        return results.map(::toFileWithTags)
    }

    /**
     * One page of games (see [DownloadableFileDao.queryGameGroups]) with their versions among the
     * files that pass the filters, best first for [prefs]. [limit] counts games, not files.
     */
    suspend fun searchGames(
        query: String,
        consoleIds: Set<String> = emptySet(),
        tags: Set<String> = emptySet(),
        favouritesOnly: Boolean = false,
        source: SourceFilter = SourceFilter.ALL,
        sort: SortOption = SortOption.NAME_ASC,
        limit: Int = 100,
        offset: Int = 0,
        prefs: VersionPrefs,
        genres: Set<LibraryGenre> = emptySet(),
        decades: Set<Int> = emptySet(),
        /** The "Collection: X" filter; 0 = none. */
        collectionId: Long = 0
    ): List<LibraryGame> {
        val genreMask = LibraryGenre.maskOfGenres(genres)
        val byKind = TagCategorizer.groupByKind(tags)
        fun kind(k: TagKind) = byKind[k].orEmpty().toList()
        val pattern = searchPattern(query)
        val groups = dao.queryGameGroups(
            query = pattern, manufacturer = null,
            consoleIds = consoleIds.toList(), consoleIdsCount = consoleIds.size,
            regions = kind(TagKind.REGION), regionsCount = kind(TagKind.REGION).size,
            languages = kind(TagKind.LANGUAGE), languagesCount = kind(TagKind.LANGUAGE).size,
            videoStandards = kind(TagKind.VIDEO_STANDARD), videoStandardsCount = kind(TagKind.VIDEO_STANDARD).size,
            contentTypes = kind(TagKind.CONTENT_TYPE), contentTypesCount = kind(TagKind.CONTENT_TYPE).size,
            fileTypes = kind(TagKind.FILE_TYPE), fileTypesCount = kind(TagKind.FILE_TYPE).size,
            favouritesOnly = favouritesOnly, source = source.ordinal, sort = sort.ordinal,
            limit = limit, offset = offset,
            genreMask = genreMask, decades = decades.toList(), decadesCount = decades.size,
            collectionId = collectionId
        )
        if (groups.isEmpty()) return emptyList()
        // The page's files in one query; an unlimited page skips the key list (SQLite caps the
        // number of bound values at 999 on older Android) and keeps only the page's games below.
        val keys = groups.map { it.groupKey }.distinct().takeIf { it.size <= MAX_KEYS_PER_QUERY }.orEmpty()
        val rows = dao.queryFilesWithTags(
            query = pattern, manufacturer = null,
            consoleIds = consoleIds.toList(), consoleIdsCount = consoleIds.size,
            regions = kind(TagKind.REGION), regionsCount = kind(TagKind.REGION).size,
            languages = kind(TagKind.LANGUAGE), languagesCount = kind(TagKind.LANGUAGE).size,
            videoStandards = kind(TagKind.VIDEO_STANDARD), videoStandardsCount = kind(TagKind.VIDEO_STANDARD).size,
            contentTypes = kind(TagKind.CONTENT_TYPE), contentTypesCount = kind(TagKind.CONTENT_TYPE).size,
            fileTypes = kind(TagKind.FILE_TYPE), fileTypesCount = kind(TagKind.FILE_TYPE).size,
            favouritesOnly = favouritesOnly, source = source.ordinal, sort = sort.ordinal,
            limit = Int.MAX_VALUE, offset = 0,
            groupKeys = keys, groupKeysCount = keys.size,
            genreMask = genreMask, decades = decades.toList(), decadesCount = decades.size
        )
        val byGame = rows.groupBy({ it.consoleId to it.groupKey }, ::toFileWithTags)
        return groups.mapNotNull { group ->
            val files = byGame[group.consoleId to group.groupKey] ?: return@mapNotNull null
            LibraryGame(group.consoleId, group.groupKey, VersionGrouping.versionsOf(files, prefs, ::byOrigin))
        }
    }

    /** Every version of one game, regardless of the library filters (details card). */
    suspend fun gameVersions(consoleId: String, groupKey: String, prefs: VersionPrefs): List<GameVersion> =
        VersionGrouping.versionsOf(dao.getGameFiles(consoleId, groupKey).map(::toFileWithTags), prefs, ::byOrigin)

    /** Copies of one release from several sources: the one whose source has been doing best first. */
    private fun byOrigin(versions: List<GameVersion>): List<GameVersion> =
        sourceStats.orderBy(versions) { DownloadOrigins.forFile(it.primary.file.downloadUrl, it.primary.file.torrentMagnet) }

    /** File names of one game, all versions (to count the discs of a release). */
    suspend fun gameFileNames(consoleId: String, groupKey: String): List<String> = dao.getGameFileNames(consoleId, groupKey)

    private fun toFileWithTags(result: DownloadableFileWithTagsResult) = DownloadableFileWithTags(
        file = DownloadableFileEntity(
            id = result.id,
            name = result.name,
            fileName = result.fileName,
            consoleId = result.consoleId,
            downloadUrl = result.downloadUrl,
            fileSize = result.fileSize,
            fileExtension = result.fileExtension,
            torrentFileIndex = result.torrentFileIndex,
            torrentMagnet = result.torrentMagnet,
            groupKey = result.groupKey.ifEmpty { VersionGrouping.groupKey(result.name) }
        ),
        tags = result.tags?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
    )

    /** The indexed file (with its tags) behind a download, or null if it was re-indexed away. */
    suspend fun findByFileName(fileName: String): DownloadableFileWithTags? {
        val file = dao.getFileByFileName(fileName) ?: return null
        return DownloadableFileWithTags(file = file, tags = dao.getTagsForFile(file.id))
    }

    suspend fun clearAll() = dao.clearAll()

    suspend fun totalFiles(): Int = dao.getFilesCount()

    /** Id and name of the rows a search for [text] finds (leniently, like the search box), unsorted. */
    suspend fun idNamesMatching(text: String, limit: Int): List<FileIdName> =
        SearchNormalizer.likePattern(text).takeIf { it.isNotEmpty() }?.let { dao.findIdNames(it, limit) }.orEmpty()

    /** Library rows whose name contains [word] (leniently, like the search box). */
    suspend fun rowsContaining(word: String): Int =
        SearchNormalizer.key(word).takeIf { it.isNotEmpty() }?.let { dao.countBySearchKey(it) } ?: 0

    suspend fun backfillSearchKeys() = dao.backfillSearchKeys()

    /** '*' means "no text filter" in the DAO queries; anything else is a lenient LIKE pattern. */
    private fun searchPattern(query: String): String =
        SearchNormalizer.likePattern(query).ifEmpty { "*" }

    suspend fun getAvailableTags(
        query: String,
        manufacturer: String? = null,
        consoleIds: Set<String> = emptySet()
    ): List<String> =
        dao.getAvailableTags(searchPattern(query), manufacturer, consoleIds.toList(), consoleIds.size)

    suspend fun getCategorizedTags(
        query: String,
        manufacturer: String? = null,
        consoleIds: Set<String> = emptySet()
    ): CategorizedTags =
        TagCategorizer.categorizeTags(
            dao.getAvailableTags(searchPattern(query), manufacturer, consoleIds.toList(), consoleIds.size)
        )

    suspend fun getConsolesWithFiles(query: String, manufacturer: String? = null): List<ConsoleWithFileCount> =
        dao.getConsolesWithFiles(searchPattern(query), manufacturer)

    // biblioteca

    /** Letter runs of the A→Z / Z→A list for these filters (see [LetterJump]), counted by the DAO. */
    suspend fun letterIndex(
        query: String,
        consoleIds: Set<String>,
        tags: Set<String>,
        favouritesOnly: Boolean,
        source: SourceFilter,
        genres: Set<LibraryGenre>,
        decades: Set<Int>,
        descending: Boolean,
        /** The "Collection: X" filter, as in [searchGames]; 0 = none. */
        collectionId: Long = 0
    ): LetterJump.Index {
        val byKind = TagCategorizer.groupByKind(tags)
        fun kind(k: TagKind) = byKind[k].orEmpty().toList()
        val buckets = dao.letterBuckets(
            query = searchPattern(query),
            consoleIds = consoleIds.toList(), consoleIdsCount = consoleIds.size,
            regions = kind(TagKind.REGION), regionsCount = kind(TagKind.REGION).size,
            languages = kind(TagKind.LANGUAGE), languagesCount = kind(TagKind.LANGUAGE).size,
            videoStandards = kind(TagKind.VIDEO_STANDARD), videoStandardsCount = kind(TagKind.VIDEO_STANDARD).size,
            contentTypes = kind(TagKind.CONTENT_TYPE), contentTypesCount = kind(TagKind.CONTENT_TYPE).size,
            fileTypes = kind(TagKind.FILE_TYPE), fileTypesCount = kind(TagKind.FILE_TYPE).size,
            favouritesOnly = favouritesOnly, source = source.ordinal,
            genreMask = LibraryGenre.maskOfGenres(genres), decades = decades.toList(), decadesCount = decades.size,
            collectionId = collectionId
        )
        return LetterJump.Index(buckets.associate { it.bucket to it.games }, descending)
    }

    /**
     * The games behind [refs] (console + group key) with every version, best first for
     * [prefs], in the order of [refs]; refs that are no longer in the library are skipped.
     */
    suspend fun gamesFor(refs: List<Pair<String, String>>, prefs: VersionPrefs): List<LibraryGame> {
        val found = HashMap<Pair<String, String>, LibraryGame>()
        refs.distinct().groupBy({ it.first }, { it.second }).forEach { (consoleId, keys) ->
            keys.chunked(MAX_KEYS_PER_QUERY).forEach { chunk ->
                dao.queryFilesWithTags(
                    query = "*", manufacturer = null,
                    consoleIds = listOf(consoleId), consoleIdsCount = 1,
                    regions = emptyList(), regionsCount = 0, languages = emptyList(), languagesCount = 0,
                    videoStandards = emptyList(), videoStandardsCount = 0, contentTypes = emptyList(), contentTypesCount = 0,
                    fileTypes = emptyList(), fileTypesCount = 0,
                    favouritesOnly = false, source = SourceFilter.ALL.ordinal, sort = SortOption.NAME_ASC.ordinal,
                    limit = Int.MAX_VALUE, offset = 0,
                    groupKeys = chunk, groupKeysCount = chunk.size
                ).groupBy({ it.groupKey }, ::toFileWithTags).forEach { (groupKey, files) ->
                    found[consoleId to groupKey] = LibraryGame(consoleId, groupKey, VersionGrouping.versionsOf(files, prefs))
                }
            }
        }
        return refs.distinct().mapNotNull { found[it] }
    }

    suspend fun consoleFileCounts(): Map<String, Int> = dao.consoleFileCounts().associate { it.consoleId to it.files }

    suspend fun gameNames(consoleId: String): List<GameName> = dao.gameNames(consoleId)

    /** Which of [groupKeys] are games of [consoleId]. */
    suspend fun gamesIn(consoleId: String, groupKeys: Collection<String>): List<GameRef> =
        groupKeys.distinct().chunked(MAX_KEYS_PER_QUERY).flatMap { dao.gamesIn(consoleId, it) }

    suspend fun notableGames(consoleIds: Collection<String>, genreMask: Int, limit: Int): List<GameCandidate> =
        if (consoleIds.isEmpty()) emptyList() else dao.notableGames(consoleIds.toList(), genreMask, limit)

    /** Rows a search for [text] finds (leniently, like the search box), with their game. */
    suspend fun gameRowsMatching(text: String, limit: Int): List<GameRow> =
        SearchNormalizer.likePattern(text).takeIf { it.isNotEmpty() }?.let { dao.findGameRows(it, limit) }.orEmpty()
}
