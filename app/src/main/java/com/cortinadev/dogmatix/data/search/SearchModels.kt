package com.cortinadev.dogmatix.data.search

import androidx.annotation.StringRes
import com.cortinadev.dogmatix.R
import kotlinx.coroutines.flow.Flow

/**
 * The sections the library search box also looks into, in the order their groups are listed
 * under the games. Each one has its header in the result list and the "Also in" line.
 */
enum class SearchGroup(@param:StringRes val label: Int) {
    DOWNLOADS(R.string.nav_downloads),
    SOURCES(R.string.nav_sources),
    SETTINGS(R.string.nav_settings),
    COLLECTION(R.string.search_group_collection)
}

/**
 * Where a result takes the user: the shell switches to its section and the screen there puts the
 * focus on the element (see [com.cortinadev.dogmatix.data.state.PendingNavigation]).
 */
sealed interface SearchTarget {
    /** A row of Downloads, by file name. */
    data class Download(val fileName: String) : SearchTarget

    /** A console of Sources, unfolded; with [urlIndex], one of its addresses. */
    data class Source(val consoleId: String, val urlIndex: Int? = null) : SearchTarget

    /** A row of Settings, by its [SettingsRowKeys] key. */
    data class Setting(val key: String) : SearchTarget

    /**
     * Any other screen (the Collection tab, a tool…): its navigation [route] and the [key] of the
     * element to focus there, which only that screen interprets.
     */
    data class Screen(val route: String, val key: String) : SearchTarget
}

/**
 * One thing the search can find outside the game list. [title] and [keywords] are what it is
 * found by, already in the app's language; [subtitle] is only shown. [id] is unique and stable
 * (list key).
 */
data class SearchEntry(
    val id: String,
    val group: SearchGroup,
    val title: String,
    val subtitle: String? = null,
    val keywords: List<String> = emptyList(),
    val target: SearchTarget
)

/**
 * A source of [SearchEntry]s (Downloads, Sources, Settings, Collection…). Implementations are
 * bound into a set (`@Binds @IntoSet`, see `di/SearchModule`); [GlobalSearchService] indexes them
 * all and re-indexes whenever one emits. Collected only while the search box has text.
 */
interface SearchProvider {
    val entries: Flow<List<SearchEntry>>
}

/**
 * A searchable entry whose texts are string resources, resolved in the app's language each time
 * the index is built ([SearchStrings.resolve]). [keywords] is a comma-separated list of synonyms.
 */
data class StaticSearchEntry(
    val id: String,
    val group: SearchGroup,
    @param:StringRes val title: Int,
    @param:StringRes val hint: Int? = null,
    @param:StringRes val keywords: Int? = null,
    val target: SearchTarget
)

/** Per group, the best few entries for a query. */
data class SearchGroupHits(val group: SearchGroup, val entries: List<SearchEntry>)

/** What the search across the app found for [query] (empty groups are left out). */
data class GlobalSearchResults(val query: String, val groups: List<SearchGroupHits> = emptyList())
