package com.cortinadev.dogmatix.ui.screens.home

import android.content.Context
import com.cortinadev.dogmatix.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.local.entity.ConsoleEntity
import com.cortinadev.dogmatix.data.local.dao.ConsoleWithFileCount
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.model.CategorizedTags
import com.cortinadev.dogmatix.data.model.SortOption
import com.cortinadev.dogmatix.data.model.SourceFilter
import com.cortinadev.dogmatix.data.repository.ConsoleRepository
import com.cortinadev.dogmatix.data.repository.DownloadableFileRepository
import com.cortinadev.dogmatix.data.repository.FavouritesRepository
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.LibraryIndexService
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.service.DownloadService
import com.cortinadev.dogmatix.data.service.GameMetadataService
import com.cortinadev.dogmatix.data.service.SimilarGamesService
import com.cortinadev.dogmatix.data.service.GameTraitsService
import com.cortinadev.dogmatix.data.service.RecommendationsService
import com.cortinadev.dogmatix.data.model.RecommendationState
import com.cortinadev.dogmatix.util.LetterJump
import com.cortinadev.dogmatix.util.LibraryGenre
import com.cortinadev.dogmatix.data.model.GameDetails
import com.cortinadev.dogmatix.data.model.GameVersion
import com.cortinadev.dogmatix.data.model.LibraryGame
import com.cortinadev.dogmatix.data.model.PreferredRegion
import com.cortinadev.dogmatix.data.model.VersionPrefs
import kotlinx.coroutines.Job
import com.cortinadev.dogmatix.data.state.LibraryFilterRequest
import com.cortinadev.dogmatix.data.state.PendingLibraryFilters
import com.cortinadev.dogmatix.data.state.PendingNavigation
import com.cortinadev.dogmatix.data.search.GlobalSearchService
import com.cortinadev.dogmatix.data.state.RescanStateHolder
import com.cortinadev.dogmatix.data.state.ActiveCollection
import com.cortinadev.dogmatix.data.state.CollectionFilterState
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.Constants
import com.cortinadev.dogmatix.util.DeepLinkResolver
import com.cortinadev.dogmatix.util.GameTitleCleaner
import com.cortinadev.dogmatix.util.StorageHelper
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** How long a deep link waits for consoles / tag catalogue before applying what it has. */
private const val RESOLVE_TIMEOUT_MS = 5_000L

/** Games loaded at most per query when a letter jump leaps past the loaded pages. */
private const val MAX_JUMP_PAGE = 500

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: DownloadableFileRepository,
    private val consoleRepository: ConsoleRepository,
    private val downloadService: DownloadService,
    private val settingsRepository: SettingsRepository,
    private val rescanStateHolder: RescanStateHolder,
    private val libraryIndex: LibraryIndexService,
    private val metadataService: GameMetadataService,
    private val similarGames: SimilarGamesService,
    private val favourites: FavouritesRepository,
    private val pendingFilters: PendingLibraryFilters,
    globalSearch: GlobalSearchService,
    pendingNavigation: PendingNavigation,
    private val traitsService: GameTraitsService,
    private val recommendationsService: RecommendationsService,
    // coleccion-gestion
    private val collectionFilter: CollectionFilterState
) : ViewModel() {

    // coleccion-gestion: "Collection: X" (opened from Collection → Collections); B clears it as a layer.
    val collection: StateFlow<ActiveCollection?> = collectionFilter.active

    fun clearCollection() = collectionFilter.clear()

    /** Set when a newly opened collection's first page is in: the screen puts the focus on the list. */
    private val _landOnCollection = MutableStateFlow(false)
    val landOnCollection: StateFlow<Boolean> = _landOnCollection.asStateFlow()
    private var listedCollection = 0L

    fun landedOnCollection() { _landOnCollection.value = false }

    /** `consoleId|fileName` keys of starred games; see [isFavourite]. */
    val favouriteKeys: StateFlow<Set<String>> = favourites.keys

    fun isFavourite(file: DownloadableFileEntity, keys: Set<String>): Boolean = favourites.isFavourite(file, keys)

    /**
     * Star / un-star a game; returns the new state. A game is starred when any of its versions
     * is: un-starring clears them all, starring marks the row's version.
     */
    suspend fun toggleFavourite(item: DownloadableFileWithTags): Boolean {
        val keys = favourites.keys.value
        val starred = membersOf(item).filter { favourites.isFavourite(it.file, keys) }
        if (starred.isEmpty()) return favourites.toggle(item.file)
        starred.forEach { favourites.toggle(it.file) }
        return false
    }

    fun isGameFavourite(item: DownloadableFileWithTags, keys: Set<String>): Boolean =
        membersOf(item).any { favourites.isFavourite(it.file, keys) }

    private val _favouritesOnly = MutableStateFlow(false)
    val favouritesOnly: StateFlow<Boolean> = _favouritesOnly.asStateFlow()

    fun setFavouritesOnly(only: Boolean) {
        _favouritesOnly.value = only
    }

    private val _source = MutableStateFlow(SourceFilter.ALL)
    val source: StateFlow<SourceFilter> = _source.asStateFlow()

    fun setSource(source: SourceFilter) {
        _source.value = source
    }

    /** The game whose details card is open, if any, and what we know about it so far. */
    private val _details = MutableStateFlow<DetailsState?>(null)
    val details: StateFlow<DetailsState?> = _details.asStateFlow()
    private var detailsJob: Job? = null

    fun openDetails(item: DownloadableFileWithTags) {
        detailsJob?.cancel()
        _details.value = DetailsState(item, loading = true, versions = gameOf(item.file.id)?.versions.orEmpty())
        detailsJob = viewModelScope.launch {
            // Every version of the game, not only those passing the library filters.
            val versions = runCatching { repository.gameVersions(item.file.consoleId, item.file.groupKey, versionPrefs.value) }.getOrNull()
            if (versions != null && _details.value?.item == item) _details.value = _details.value?.copy(versions = versions)
            val found = metadataService.lookup(item.file.name, item.file.consoleId)
            if (_details.value?.item == item) _details.value = _details.value?.copy(loading = false, details = found)
            // What the lookup found also feeds the Genre / Decade filters.
            found?.let { runCatching { traitsService.remember(item.file.consoleId, item.file.groupKey, it.genres, it.released) } }
        }
    }

    fun closeDetails() {
        detailsJob?.cancel()
        _details.value = null
    }

    /**
     * "Similar to X" (the details card's button): the list shows the games of X's franchise on
     * every console, then same-genre games when the franchise is short (see [SimilarGamesService]).
     * It replaces the text search and the console filter; the other filters still apply.
     */
    private val _similar = MutableStateFlow<SimilarFilter?>(null)
    val similar: StateFlow<SimilarFilter?> = _similar.asStateFlow()
    private var similarJob: Job? = null

    fun showSimilar(item: DownloadableFileWithTags, details: GameDetails?) {
        closeDetails()
        collectionFilter.clear()
        similarJob?.cancel()
        val filter = SimilarFilter(
            fileId = item.file.id,
            title = GameTitleCleaner.clean(item.file.name)
        )
        // Similar mode first: the emissions in between then stay in that mode (no full search).
        _similar.value = filter
        _searchQuery.value = ""
        _selectedConsoles.value = emptySet()
        similarJob = viewModelScope.launch {
            val ids = runCatching { similarGames.similarTo(item, details) }.getOrDefault(emptyList())
            if (_similar.value?.fileId == filter.fileId) _similar.value = filter.copy(loading = false, ids = ids)
        }
    }

    fun clearSimilar() {
        similarJob?.cancel()
        _similar.value = null
    }

    /** Lower-cased names of files already on disk; see [isOwned]. */
    val ownedKeys: StateFlow<Set<String>> = libraryIndex.ownedKeys

    fun isOwned(file: DownloadableFileEntity, keys: Set<String>): Boolean = libraryIndex.isOwned(file, keys)

    /** Any version of the game is on disk. */
    fun isGameOwned(item: DownloadableFileWithTags, keys: Set<String>): Boolean =
        membersOf(item).any { libraryIndex.isOwned(it.file, keys) }

    fun isVersionOwned(version: GameVersion, keys: Set<String>): Boolean =
        version.discs.any { libraryIndex.isOwned(it.file, keys) }

    /** File names with a download in flight (queued, downloading, copying or extracting). */
    val activeDownloads: StateFlow<Set<String>> = downloadService.downloads
        .map { list -> list.filter { !it.isFinished }.map { it.fileName }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun isDownloading(file: DownloadableFileEntity, active: Set<String>): Boolean = file.fileName in active

    fun isGameDownloading(item: DownloadableFileWithTags, active: Set<String>): Boolean =
        membersOf(item).any { it.file.fileName in active }

    fun isVersionDownloading(version: GameVersion, active: Set<String>): Boolean =
        version.discs.any { it.file.fileName in active }

    /** Remove an owned game (every version on disk) from the download folder. Returns true if something was deleted. */
    suspend fun deleteOwned(fileWithTags: DownloadableFileWithTags): Boolean {
        val keys = libraryIndex.ownedKeys.value
        val owned = membersOf(fileWithTags).filter { libraryIndex.isOwned(it.file, keys) }.ifEmpty { listOf(fileWithTags) }
        return owned.map { libraryIndex.deleteOwned(it.file) }.any { it }
    }

    private val _selectedConsoles = MutableStateFlow<Set<String>>(emptySet())
    val selectedConsoles: StateFlow<Set<String>> = _selectedConsoles

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _activeTags = MutableStateFlow<Set<String>>(emptySet())
    val activeTags: StateFlow<Set<String>> = _activeTags.asStateFlow()

    private val _sort = MutableStateFlow(SortOption.NAME_ASC)
    val sort: StateFlow<SortOption> = _sort.asStateFlow()

    /** One row per game: the first file of its preferred version (see [games]). */
    private val _results = MutableStateFlow<List<DownloadableFileWithTags>>(emptyList())
    val results: StateFlow<List<DownloadableFileWithTags>> = _results

    /** The game behind each row of [results], keyed by the row's file id. */
    private val _games = MutableStateFlow<Map<Long, LibraryGame>>(emptyMap())
    val games: StateFlow<Map<Long, LibraryGame>> = _games

    /** Region and languages that rank a game's versions (onboarding / Settings). */
    private val versionPrefsFlow = combine(settingsRepository.preferredRegion, settingsRepository.favoriteLanguages) { region, languages -> VersionPrefs(region, languages) }
        .distinctUntilChanged()
    private val versionPrefs: StateFlow<VersionPrefs> =
        versionPrefsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, VersionPrefs(PreferredRegion.forLocale(), emptySet()))

    /** Every file of the row's game (all versions in the current results), or just the row. */
    private fun membersOf(item: DownloadableFileWithTags): List<DownloadableFileWithTags> =
        gameOf(item.file.id)?.files ?: listOf(item)

    private val _consoles = MutableStateFlow<List<ConsoleEntity>>(emptyList())
    val consoles: StateFlow<List<ConsoleEntity>> = _consoles

    private val _consolesWithFiles = MutableStateFlow<List<ConsoleWithFileCount>>(emptyList())
    val consolesWithFiles: StateFlow<List<ConsoleWithFileCount>> = _consolesWithFiles

    private val _categorizedTags = MutableStateFlow<CategorizedTags?>(null)
    val categorizedTags: StateFlow<CategorizedTags?> = _categorizedTags

    /** Language tags listed first in the filter; editable in Settings. */
    val favoriteLanguages: StateFlow<Set<String>> = settingsRepository.favoriteLanguages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _hasMoreResults = MutableStateFlow(true)
    val hasMoreResults: StateFlow<Boolean> = _hasMoreResults

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore

    private var currentOffset = 0
    /** Games asked for so far: where the next "Load more" page starts. */
    private var nextOffset = 0
    /** Square covers in front of the rows (Settings → Covers in the list). */
    val showListCovers: StateFlow<Boolean> = settingsRepository.showListCovers
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Rows per search ("Maximum search results" in Settings); [Int.MAX_VALUE] when unlimited. */
    private val pageSize: StateFlow<Int> = settingsRepository.maxSearchResults
        .map { if (it <= 0) Int.MAX_VALUE else it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Constants.DEFAULT_MAX_SEARCH_RESULTS)

    // biblioteca

    private val _genres = MutableStateFlow<Set<LibraryGenre>>(emptySet())
    val genres: StateFlow<Set<LibraryGenre>> = _genres.asStateFlow()

    private val _decades = MutableStateFlow<Set<Int>>(emptySet())
    /** Decades (1990…) of the Decade filter. */
    val decades: StateFlow<Set<Int>> = _decades.asStateFlow()

    fun setGenres(genres: Set<LibraryGenre>) {
        _genres.value = genres
    }

    fun setDecades(decades: Set<Int>) {
        _decades.value = decades
    }

    /** Genres and years are being downloaded or matched for the filtered consoles. */
    val traitsLoading: StateFlow<Boolean> = traitsService.loading

    private var traitsJob: Job? = null

    /** Genre / Decade (and the decade sort) need the consoles' traits: worked out in the background, the list re-queries as they land. */
    private fun ensureTraits(selected: Set<String>) {
        // One run at a time; a later one only re-checks (cheap when nothing is stale).
        val previous = traitsJob
        traitsJob = viewModelScope.launch {
            previous?.join()
            val consoles = selected.ifEmpty { _consolesWithFiles.value.map { it.id }.toSet().ifEmpty { repository.consoleFileCounts().keys } }
            runCatching { traitsService.ensure(consoles) }
        }
    }

    /** "All games" at the end of the recommendations: the full list, until the filters are cleared. */
    private val _browseAll = MutableStateFlow(false)
    private val _fromDeepLink = MutableStateFlow(false)

    /** The list is on screen although no filter is (All games, a deep link): B / Clear go back to the recommendations. */
    val listForced: StateFlow<Boolean> = combine(_browseAll, _fromDeepLink) { all, link -> all || link }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun showAllGames() {
        _browseAll.value = true
    }

    val recommendations: StateFlow<RecommendationState> = recommendationsService.state

    /**
     * The recommendations replace the list while every filter is at its default (sort
     * included), the search is empty and neither "similar to", a collection nor All games / a
     * deep link is on.
     */
    val showRecommendations: StateFlow<Boolean> = combine(
        combine(_searchQuery, _selectedConsoles, _activeTags, _sort) { query, consoles, tags, sort ->
            query.isEmpty() && consoles.isEmpty() && tags.isEmpty() && sort == SortOption.NAME_ASC
        },
        combine(_favouritesOnly, _source, _genres, _decades) { favouritesOnly, source, genres, decades ->
            !favouritesOnly && source == SourceFilter.ALL && genres.isEmpty() && decades.isEmpty()
        },
        // coleccion-gestion: a collection on show is a filter too.
        combine(_similar, listForced, collectionFilter.active) { similar, forced, collection -> similar == null && !forced && collection == null }
    ) { a, b, c -> a && b && c }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** The game behind a row or a recommendation card. */
    private fun gameOf(fileId: Long): LibraryGame? = _games.value[fileId] ?: recommendationsService.state.value.games[fileId]

    /** Filters of the list on screen, and its letter runs once asked for (see [letterIndex]). */
    private var lastParams: FilterParams? = null
    private var letterIndex: LetterJump.Index? = null

    /** Letters jump only in the A→Z / Z→A list (not by size, nor the ranked similar list). */
    val letterJumpEnabled: StateFlow<Boolean> = combine(_sort, _similar) { sort, similar ->
        similar == null && (sort == SortOption.NAME_ASC || sort == SortOption.NAME_DESC)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** The letter runs of the whole list (loaded or not), counted by the DAO once per filter change. */
    private suspend fun letterIndex(): LetterJump.Index? {
        letterIndex?.let { return it }
        val params = lastParams ?: return null
        if (params.similarIds != null || (params.sort != SortOption.NAME_ASC && params.sort != SortOption.NAME_DESC)) return null
        val index = runCatching {
            repository.letterIndex(
                query = params.query, consoleIds = params.consoles, tags = params.tags,
                favouritesOnly = params.favouritesOnly, source = params.source,
                genres = params.genres, decades = params.decades,
                descending = params.sort == SortOption.NAME_DESC,
                collectionId = params.collectionId
            )
        }.getOrNull() ?: return null
        if (lastParams === params) letterIndex = index
        return index
    }

    /** Row where the letter after the one at [index] starts, loading pages up to it; null at the last letter. */
    suspend fun nextLetterStart(index: Int): Int? = letterIndex()?.next(index)?.takeIf { loadThrough(it) }

    /** "Load more" until row [index] is in [results]; false if the list ends before. */
    private suspend fun loadThrough(index: Int): Boolean {
        while (_results.value.size <= index) {
            if (!_hasMoreResults.value || _similar.value != null) return false
            val before = _results.value.size
            // Big leaps load in one go (500 games per query at most), not page by page.
            loadMore(atLeast = (index + 1 - before).coerceAtMost(MAX_JUMP_PAGE))
            if (_results.value.size == before) return false
        }
        return true
    }

    init {
        recommendationsService.start()
        // coleccion-gestion: a collection replaces the "similar to" list.
        viewModelScope.launch { collectionFilter.active.collect { if (it != null) clearSimilar() } }
        // Deep links (dogmatix://library?…): apply whatever is waiting, now and on every new link.
        viewModelScope.launch {
            pendingFilters.request.collect { if (it != null) pendingFilters.consume()?.let { request -> applyRequest(request) } }
        }
        viewModelScope.launch {
            combine(
                combine(_searchQuery, _selectedConsoles, _activeTags) { q, c, t -> Triple(q, c, t) },
                combine(_sort, _favouritesOnly, _source, rescanStateHolder.lastRescanTime) { s, f, src, _ -> Triple(s, f, src) },
                // Re-query when a star changes while "Favourites only" is on, else the row would linger.
                combine(_favouritesOnly, favourites.keys) { only, keys -> if (only) keys else emptySet() }.distinctUntilChanged(),
                combine(
                    pageSize,
                    // Similar mode: the rows to show (empty while they are being looked for).
                    _similar.map { s -> s?.let { if (it.loading) emptyList() else it.ids } }.distinctUntilChanged(),
                    // The cold flow, not the StateFlow: the first search waits for the saved prefs.
                    versionPrefsFlow
                ) { limit, similarIds, prefs -> Triple(limit, similarIds, prefs) },
                combine(
                    // Genre / Decade (filter or sort), and new traits while one of them is on (they arrive per console).
                    combine(_genres, _decades, _sort, traitsService.version) { genres, decades, sort, version ->
                        Triple(genres, decades, if (genres.isEmpty() && decades.isEmpty() && !sort.byDecade) 0 else version)
                    }.distinctUntilChanged(),
                    // coleccion-gestion: the collection on show (its revision bumps when its games change).
                    collectionFilter.active.map { it?.let { active -> active.id to active.revision } }.distinctUntilChanged()
                ) { traits, collection -> traits to collection }
            ) { (query, consoles, tags), (sort, favouritesOnly, source), _, (limit, similarIds, prefs), (traits, collection) ->
                val (genres, decades, _) = traits
                FilterParams(
                    query = query, consoles = consoles, tags = tags, sort = sort, favouritesOnly = favouritesOnly,
                    source = source, limit = limit, similarIds = similarIds, prefs = prefs, genres = genres, decades = decades,
                    collectionId = collection?.first ?: 0L
                )
            }.collect { params ->
                currentOffset = 0
                nextOffset = params.limit
                lastParams = params
                letterIndex = null
                if (params.genres.isNotEmpty() || params.decades.isNotEmpty() || params.sort.byDecade) ensureTraits(params.consoles)
                val initialResults = performSearch(params)
                _results.value = initialResults
                // The similar list comes whole (it is short); no "Load more".
                _hasMoreResults.value = params.similarIds == null && initialResults.size >= params.limit
                if (params.collectionId != listedCollection && params.collectionId != 0L && initialResults.isNotEmpty()) _landOnCollection.value = true
                listedCollection = params.collectionId
                loadConsoles()
                loadAvailableTags(params.query, params.consoles)
            }
        }
    }

    /**
     * Deep-link values are loose (`console=snes`, `region=japan`): resolve them against the
     * console list and the tag catalogue, waiting briefly for both if the link arrived during a
     * cold start. Unknown consoles are dropped; unknown tags are applied verbatim.
     */
    private suspend fun applyRequest(request: LibraryFilterRequest) {
        // A link always opens the list, even when nothing in it resolves.
        _fromDeepLink.value = true
        val consoleIds = if (request.consoles.isEmpty()) emptySet() else {
            val consoles = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { _consoles.first { it.isNotEmpty() } } ?: _consoles.value
            DeepLinkResolver.resolveConsoles(request.consoles, consoles.map { it.id })
        }
        val tags = if (request.tags.isEmpty()) emptySet() else {
            val catalogue = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { _categorizedTags.first { it != null } } ?: _categorizedTags.value
            val known = catalogue?.let { it.regions.tags + it.languages.tags + it.videoStandards.tags + it.contentTypes.tags + it.fileTypes.tags }.orEmpty()
            DeepLinkResolver.resolveTags(request.tags, known)
        }
        // coleccion-gestion: a deep link replaces the collection on show; opening a collection keeps it.
        if (!collectionFilter.takeJustShown()) collectionFilter.clear()
        _selectedConsoles.value = consoleIds
        _activeTags.value = tags
        _searchQuery.value = request.query.orEmpty()
        request.favouritesOnly?.let { _favouritesOnly.value = it }
        _genres.value = request.genres.mapNotNull(LibraryGenre::resolve).toSet()
        _decades.value = request.decades
    }

    fun toggleConsoleFilter(consoleId: String) {
        val currentConsoles = _selectedConsoles.value.toMutableSet()
        if (currentConsoles.contains(consoleId)) {
            currentConsoles.remove(consoleId)
        } else {
            currentConsoles.add(consoleId)
        }
        _selectedConsoles.value = currentConsoles
    }

    fun clearConsoleFilters() {
        _selectedConsoles.value = emptySet()
    }

    fun setSearch(query: String) {
        // Typing a search leaves the "similar to" list.
        if (query.isNotEmpty()) clearSimilar()
        _searchQuery.value = query
    }

    fun toggleTag(tag: String) {
        val currentTags = _activeTags.value.toMutableSet()
        if (currentTags.contains(tag)) {
            currentTags.remove(tag)
        } else {
            currentTags.add(tag)
        }
        _activeTags.value = currentTags
    }

    fun removeTag(tag: String) {
        _activeTags.value = _activeTags.value - tag
    }

    fun removeConsole(consoleId: String) {
        _selectedConsoles.value = _selectedConsoles.value - consoleId
    }

    fun setSort(option: SortOption) {
        _sort.value = option
    }

    /** Replace whichever of [categoryTags] are active with [selection] (a subset of them). */
    fun setTagsInCategory(categoryTags: Collection<String>, selection: Set<String>) {
        _activeTags.value = (_activeTags.value - categoryTags.toSet()) + selection
    }

    fun setConsoleSelection(consoleIds: Set<String>) {
        _selectedConsoles.value = consoleIds
    }

    fun clearAllFilters() {
        clearSimilar()
        collectionFilter.clear()
        _searchQuery.value = ""
        _activeTags.value = emptySet()
        _selectedConsoles.value = emptySet()
        _sort.value = SortOption.NAME_ASC
        _favouritesOnly.value = false
        _source.value = SourceFilter.ALL
        _genres.value = emptySet()
        _decades.value = emptySet()
        _browseAll.value = false
        _fromDeepLink.value = false
    }

    private suspend fun performSearch(params: FilterParams): List<DownloadableFileWithTags> {
        currentOffset = 0
        params.similarIds?.let { ids ->
            // The similar list shows single files, each one its own row (no version grouping).
            _games.value = emptyMap()
            if (ids.isEmpty()) return emptyList()
            // The other filters still narrow it down; the order is the similarity ranking.
            val rank = ids.withIndex().associate { (index, id) -> id to index }
            return repository.searchFilesWithTags(
                query = "",
                consoleIds = params.consoles,
                tags = params.tags,
                favouritesOnly = params.favouritesOnly,
                source = params.source,
                sort = params.sort,
                limit = ids.size,
                ids = ids,
                genres = params.genres,
                decades = params.decades
            ).sortedBy { rank[it.file.id] ?: Int.MAX_VALUE }
        }
        val games = repository.searchGames(
            query = params.query,
            consoleIds = params.consoles,
            tags = params.tags,
            favouritesOnly = params.favouritesOnly,
            source = params.source,
            sort = params.sort,
            limit = params.limit,
            offset = 0,
            prefs = params.prefs,
            genres = params.genres,
            decades = params.decades,
            collectionId = params.collectionId
        )
        _games.value = games.associateBy { it.primary.file.id }
        return games.map { it.primary }
    }

    private suspend fun loadConsoles() {
        val allConsoles = consoleRepository.getAllConsoles().first()
        _consoles.value = allConsoles.sortedBy { ConsoleFormatter.getConsoleDisplayName(it.id) }

        val consolesWithFiles = repository.getConsolesWithFiles(
            query = _searchQuery.value.ifBlank { "*" },
            manufacturer = null
        )
        _consolesWithFiles.value = consolesWithFiles.sortedBy { ConsoleFormatter.getConsoleDisplayName(it.id) }
    }

    private suspend fun loadAvailableTags(query: String, consoleIds: Set<String>) {
        _categorizedTags.value = repository.getCategorizedTags(
            query = query,
            consoleIds = consoleIds
        )
    }

    fun getConsoleName(consoleId: String): String {
        return ConsoleFormatter.formatConsoleField(consoleId)
    }

    suspend fun loadMore(atLeast: Int = 0) {
        if (_isLoadingMore.value || !_hasMoreResults.value || _similar.value != null) return

        val limit = maxOf(pageSize.value, atLeast)
        _isLoadingMore.value = true
        // Pages can differ in size (letter jumps load bigger ones): the next one starts where the last ended.
        currentOffset = nextOffset
        nextOffset = currentOffset + limit

        val newGames = repository.searchGames(
            query = _searchQuery.value,
            consoleIds = _selectedConsoles.value,
            tags = _activeTags.value,
            favouritesOnly = _favouritesOnly.value,
            source = _source.value,
            sort = _sort.value,
            limit = limit,
            offset = currentOffset,
            prefs = versionPrefs.value,
            genres = _genres.value,
            decades = _decades.value,
            collectionId = collectionFilter.active.value?.id ?: 0L
        )
        val newResults = newGames.map { it.primary }

        if (newResults.isEmpty()) {
            _hasMoreResults.value = false
        } else {
            _games.value = _games.value + newGames.associateBy { it.primary.file.id }
            _results.value += newResults
            if (newResults.size < limit) _hasMoreResults.value = false
        }

        _isLoadingMore.value = false
    }

    /** A row downloads its game's preferred version: one file, or every disc of a multi-disc release. */
    suspend fun startDownload(fileWithTags: DownloadableFileWithTags, context: Context) {
        if (!checkDownloadDirectory(context)) return
        val files = gameOf(fileWithTags.file.id)?.preferred?.discs ?: listOf(fileWithTags)
        files.forEach { downloadService.startDownload(it.file) }
    }

    /** Another version picked in the details card (all its discs). */
    suspend fun downloadVersion(version: GameVersion, context: Context) {
        if (!checkDownloadDirectory(context)) return
        version.discs.forEach { downloadService.startDownload(it.file) }
    }

    private suspend fun checkDownloadDirectory(context: Context): Boolean {
        val downloadDirectory = settingsRepository.downloadDirectory.first()
        if (downloadDirectory.isEmpty()) {
            ToastUtil.showError(context, context.getString(R.string.error_download_dir_missing))
            return false
        }
        if (!StorageHelper.isValidUri(context, downloadDirectory)) {
            ToastUtil.showError(context, context.getString(R.string.error_download_dir_inaccessible))
            return false
        }
        return true
    }

    // busqueda: recent searches and matches in the rest of the app, for the same search box.
    val search = LibrarySearch(viewModelScope, searchQuery, globalSearch, pendingNavigation, settingsRepository)
}

data class FilterParams(
    val query: String,
    val consoles: Set<String>,
    val tags: Set<String>,
    val sort: SortOption,
    val favouritesOnly: Boolean = false,
    val source: SourceFilter = SourceFilter.ALL,
    val limit: Int = Constants.DEFAULT_MAX_SEARCH_RESULTS,
    /** Rows of the "similar to" list, best first; null outside that mode. */
    val similarIds: List<Long>? = null,
    val prefs: VersionPrefs = VersionPrefs(PreferredRegion.forLocale(), emptySet()),
    val genres: Set<LibraryGenre> = emptySet(),
    val decades: Set<Int> = emptySet(),
    /** coleccion-gestion: only the games of this collection; 0 = no collection filter. */
    val collectionId: Long = 0
)

/** The "similar to [title]" filter; [ids] are the library rows found, best first. */
data class SimilarFilter(
    val fileId: Long,
    val title: String,
    val loading: Boolean = true,
    val ids: List<Long> = emptyList()
)

data class DetailsState(
    val item: DownloadableFileWithTags,
    val loading: Boolean,
    val details: GameDetails? = null,
    /** Every version of the game, best first; the card lists them when there is more than one. */
    val versions: List<GameVersion> = emptyList()
)
