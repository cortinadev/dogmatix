package com.cortinadev.dogmatix.data.search

import com.cortinadev.dogmatix.util.SearchIndex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything the library search box finds besides games: the entries of every [SearchProvider]
 * (Downloads, Sources, Settings, and whatever other sections register), in one in-memory index.
 */
@Singleton
class GlobalSearchService @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards SearchProvider>
) {
    /** Rebuilt whenever a provider's entries change; cold, so nothing is read until someone searches. */
    val index: Flow<GlobalSearchIndex> =
        if (providers.isEmpty()) flowOf(GlobalSearchIndex(emptyList()))
        else combine(providers.map { it.entries }) { lists -> GlobalSearchIndex(lists.flatMap { it }) }
}

/** The entries of every section, normalized once ([SearchIndex]) and grouped per section on each query. */
class GlobalSearchIndex(entries: List<SearchEntry>) {
    private val index = SearchIndex(entries, { it.title }, { it.keywords })

    /** Each group with its best [perGroup] matches, in [SearchGroup] order; groups without matches are left out. */
    fun search(query: String, perGroup: Int = PER_GROUP): List<SearchGroupHits> =
        index.search(query)
            .groupBy { it.group }
            .entries
            .sortedBy { it.key.ordinal }
            .map { (group, hits) -> SearchGroupHits(group, hits.take(perGroup)) }

    companion object {
        const val PER_GROUP = 5
    }
}
