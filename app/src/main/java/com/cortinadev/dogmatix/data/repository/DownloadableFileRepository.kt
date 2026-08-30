package com.cortinadev.dogmatix.data.repository

import com.cortinadev.dogmatix.data.local.dao.ConsoleWithFileCount
import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.CategorizedTags
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.model.TagCategorizer
import com.cortinadev.dogmatix.data.model.SourceFilter
import com.cortinadev.dogmatix.data.model.TagKind
import com.cortinadev.dogmatix.util.SearchNormalizer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadableFileRepository @Inject constructor(
    private val dao: DownloadableFileDao
) {
    suspend fun searchFilesWithTags(
        query: String,
        manufacturer: String? = null,
        consoleIds: Set<String> = emptySet(),
        tags: Set<String> = emptySet(),
        favouritesOnly: Boolean = false,
        source: SourceFilter = SourceFilter.ALL,
        sortAsc: Boolean = true,
        limit: Int = 100,
        offset: Int = 0
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
            otherTags = kind(TagKind.OTHER),
            otherTagsCount = kind(TagKind.OTHER).size,
            fileTypes = kind(TagKind.FILE_TYPE),
            fileTypesCount = kind(TagKind.FILE_TYPE).size,
            favouritesOnly = favouritesOnly,
            source = source.ordinal,
            sortAsc = sortAsc,
            limit = limit,
            offset = offset
        )
        return results.map { result ->
            DownloadableFileWithTags(
                file = DownloadableFileEntity(
                    id = result.id,
                    name = result.name,
                    fileName = result.fileName,
                    consoleId = result.consoleId,
                    downloadUrl = result.downloadUrl,
                    fileSize = result.fileSize,
                    fileExtension = result.fileExtension,
                    torrentFileIndex = result.torrentFileIndex,
                    torrentMagnet = result.torrentMagnet
                ),
                tags = result.tags?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            )
        }
    }

    /** The indexed file (with its tags) behind a download, or null if it was re-indexed away. */
    suspend fun findByFileName(fileName: String): DownloadableFileWithTags? {
        val file = dao.getFileByFileName(fileName) ?: return null
        return DownloadableFileWithTags(file = file, tags = dao.getTagsForFile(file.id))
    }

    suspend fun clearAll() = dao.clearAll()

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
}
