package com.cortinadev.dogmatix.ui.screens.home

import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.search.GlobalSearchResults
import com.cortinadev.dogmatix.data.search.GlobalSearchService
import com.cortinadev.dogmatix.data.search.SearchEntry
import com.cortinadev.dogmatix.data.state.PendingNavigation
import com.cortinadev.dogmatix.util.RecentSearches
import com.cortinadev.dogmatix.util.SearchIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the library search box offers besides games: the recent searches listed while it is open
 * and empty, and the matches in the rest of the app (Downloads, Sources, Settings, Collection)
 * listed under the games while it has text. Owned by [HomeViewModel], which keeps the query.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class LibrarySearch(
    private val scope: CoroutineScope,
    query: StateFlow<String>,
    service: GlobalSearchService,
    private val pendingNavigation: PendingNavigation,
    private val settings: SettingsRepository
) {
    /** Newest first; see [RecentSearches]. */
    val recent: StateFlow<List<String>> =
        settings.recentSearches.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Built when the box gets text and kept while it has some, so each keystroke only re-runs
    // the match. Rebuilt after a while without searching (a language change shows up then).
    private val index = service.index
        .flowOn(Dispatchers.Default)
        .shareIn(scope, SharingStarted.WhileSubscribed(INDEX_KEEP_MS), replay = 1)

    /** Matches outside the games, per section, for the box's text (debounced: typing never waits). */
    val results: StateFlow<GlobalSearchResults> = query
        .map { RecentSearches.clean(it) }
        .distinctUntilChanged()
        .debounce { if (SearchIndex.isSearchable(it)) DEBOUNCE_MS else 0L }
        .flatMapLatest { q ->
            if (!SearchIndex.isSearchable(q)) flowOf(GlobalSearchResults(q))
            else index.map { GlobalSearchResults(q, it.search(q)) }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), GlobalSearchResults(""))

    /**
     * The keyboard was closed or the query sent: keep [text] among the recent searches if it found
     * something, here ([hasGames]) or elsewhere. Waits for the debounced search to catch up first.
     */
    fun confirm(text: String, hasGames: () -> Boolean) {
        val q = RecentSearches.clean(text)
        if (!RecentSearches.isWorthKeeping(q)) return
        scope.launch {
            val found = withTimeoutOrNull(SETTLE_MS) { results.first { it.query == q } }
            if (hasGames() || found?.groups?.isNotEmpty() == true) record(q)
        }
    }

    fun record(text: String) {
        scope.launch { settings.updateRecentSearches { RecentSearches.add(it, text) } }
    }

    fun forget(text: String) {
        scope.launch { settings.updateRecentSearches { RecentSearches.remove(it, text) } }
    }

    fun clearRecent() {
        scope.launch { settings.updateRecentSearches { emptyList() } }
    }

    /** A match in another section: the search counts as found, and the shell takes the user there. */
    fun open(entry: SearchEntry, text: String) {
        record(text)
        pendingNavigation.submit(entry.target)
    }

    private companion object {
        const val DEBOUNCE_MS = 250L
        const val SETTLE_MS = 2_000L
        const val INDEX_KEEP_MS = 3_000L
    }
}
