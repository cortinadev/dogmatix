package com.cortinadev.dogmatix.testing

import com.cortinadev.dogmatix.data.local.dao.DownloadableFileDao
import com.cortinadev.dogmatix.data.local.dao.GameGroupRef
import com.cortinadev.dogmatix.data.local.dao.LetterBucket

/** The library DAO queries with every filter off, as the list runs them with nothing selected. */
object LibraryQueries {

    /** [query]: '*' or a SearchNormalizer.likePattern; [sort]: SortOption ordinal (0 A→Z, 1 Z→A, 2 / 3 size). */
    suspend fun DownloadableFileDao.gamePage(
        limit: Int,
        offset: Int = 0,
        query: String = "*",
        sort: Int = 0,
        consoleIds: List<String> = emptyList()
    ): List<GameGroupRef> = queryGameGroups(
        query = query, manufacturer = null,
        consoleIds = consoleIds, consoleIdsCount = consoleIds.size,
        regions = emptyList(), regionsCount = 0, languages = emptyList(), languagesCount = 0,
        videoStandards = emptyList(), videoStandardsCount = 0, contentTypes = emptyList(), contentTypesCount = 0,
        fileTypes = emptyList(), fileTypesCount = 0,
        favouritesOnly = false, source = 0, sort = sort, limit = limit, offset = offset
    )

    suspend fun DownloadableFileDao.buckets(query: String = "*"): List<LetterBucket> = letterBuckets(
        query = query, consoleIds = emptyList(), consoleIdsCount = 0,
        regions = emptyList(), regionsCount = 0, languages = emptyList(), languagesCount = 0,
        videoStandards = emptyList(), videoStandardsCount = 0, contentTypes = emptyList(), contentTypesCount = 0,
        fileTypes = emptyList(), fileTypesCount = 0,
        favouritesOnly = false, source = 0, genreMask = 0, decades = emptyList(), decadesCount = 0
    )
}
