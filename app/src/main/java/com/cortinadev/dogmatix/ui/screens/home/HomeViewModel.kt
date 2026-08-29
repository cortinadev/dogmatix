package com.cortinadev.dogmatix.ui.screens.home

import android.content.Context
import com.cortinadev.dogmatix.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.local.entity.ConsoleEntity
import com.cortinadev.dogmatix.data.local.dao.ConsoleWithFileCount
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.model.CategorizedTags
import com.cortinadev.dogmatix.data.repository.ConsoleRepository
import com.cortinadev.dogmatix.data.repository.DownloadableFileRepository
import com.cortinadev.dogmatix.data.repository.FavouritesRepository
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.LibraryIndexService
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.service.DownloadService
import com.cortinadev.dogmatix.data.service.GameMetadataService
import com.cortinadev.dogmatix.data.model.GameDetails
import kotlinx.coroutines.Job
import com.cortinadev.dogmatix.data.state.LibraryFilterRequest
import com.cortinadev.dogmatix.data.state.PendingLibraryFilters
import com.cortinadev.dogmatix.data.state.RescanStateHolder
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.DeepLinkResolver
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

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: DownloadableFileRepository,
    private val consoleRepository: ConsoleRepository,
    private val downloadService: DownloadService,
    private val settingsRepository: SettingsRepository,
    private val rescanStateHolder: RescanStateHolder,
    private val libraryIndex: LibraryIndexService,
    private val metadataService: GameMetadataService,
    private val favourites: FavouritesRepository,
    private val pendingFilters: PendingLibraryFilters
) : ViewModel() {

    /** `consoleId|fileName` keys of starred games; see [isFavourite]. */
    val favouriteKeys: StateFlow<Set<String>> = favourites.keys

    fun isFavourite(file: DownloadableFileEntity, keys: Set<String>): Boolean = favourites.isFavourite(file, keys)

    /** Star / un-star; returns the new state. */
    suspend fun toggleFavourite(item: DownloadableFileWithTags): Boolean = favourites.toggle(item.file)

    private val _favouritesOnly = MutableStateFlow(false)
    val favouritesOnly: StateFlow<Boolean> = _favouritesOnly.asStateFlow()

    fun setFavouritesOnly(only: Boolean) {
        _favouritesOnly.value = only
    }

    /** The game whose details card is open, if any, and what we know about it so far. */
    private val _details = MutableStateFlow<DetailsState?>(null)
    val details: StateFlow<DetailsState?> = _details.asStateFlow()
    private var detailsJob: Job? = null

    fun openDetails(item: DownloadableFileWithTags) {
        detailsJob?.cancel()
        _details.value = DetailsState(item, loading = true)
        detailsJob = viewModelScope.launch {
            val found = metadataService.lookup(item.file.name, item.file.consoleId)
            if (_details.value?.item == item) _details.value = DetailsState(item, loading = false, details = found)
        }
    }

    fun closeDetails() {
        detailsJob?.cancel()
        _details.value = null
    }

    /** Lower-cased names of files already on disk; see [isOwned]. */
    val ownedKeys: StateFlow<Set<String>> = libraryIndex.ownedKeys

    fun isOwned(file: DownloadableFileEntity, keys: Set<String>): Boolean = libraryIndex.isOwned(file, keys)

    /** File names with a download in flight (queued, downloading, copying or extracting). */
    val activeDownloads: StateFlow<Set<String>> = downloadService.downloads
        .map { list -> list.filter { !it.isFinished }.map { it.fileName }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun isDownloading(file: DownloadableFileEntity, active: Set<String>): Boolean = file.fileName in active

    /** Remove an owned game from the download folder. Returns true if something was deleted. */
    suspend fun deleteOwned(fileWithTags: DownloadableFileWithTags): Boolean =
        libraryIndex.deleteOwned(fileWithTags.file)

    private val _selectedConsoles = MutableStateFlow<Set<String>>(emptySet())
    val selectedConsoles: StateFlow<Set<String>> = _selectedConsoles

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _activeTags = MutableStateFlow<Set<String>>(emptySet())
    val activeTags: StateFlow<Set<String>> = _activeTags.asStateFlow()

    private val _sortAsc = MutableStateFlow(true)
    val sortAsc: StateFlow<Boolean> = _sortAsc.asStateFlow()

    private val _results = MutableStateFlow<List<DownloadableFileWithTags>>(emptyList())
    val results: StateFlow<List<DownloadableFileWithTags>> = _results

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
    private val pageSize = 100

    init {
        // Deep links (dogmatix://library?…): apply whatever is waiting, now and on every new link.
        viewModelScope.launch {
            pendingFilters.request.collect { if (it != null) pendingFilters.consume()?.let { request -> applyRequest(request) } }
        }
        viewModelScope.launch {
            combine(
                combine(_searchQuery, _selectedConsoles, _activeTags) { q, c, t -> Triple(q, c, t) },
                combine(_sortAsc, _favouritesOnly, rescanStateHolder.lastRescanTime) { s, f, r -> Triple(s, f, r) },
                // Re-query when a star changes while "Favourites only" is on, else the row would linger.
                combine(_favouritesOnly, favourites.keys) { only, keys -> if (only) keys else emptySet() }.distinctUntilChanged()
            ) { (query, consoles, tags), (sortAsc, favouritesOnly, _), _ ->
                FilterParams(query = query, consoles = consoles, tags = tags, sortAsc = sortAsc, favouritesOnly = favouritesOnly)
            }.collect { params ->
                currentOffset = 0
                val initialResults = performSearch(params)
                _results.value = initialResults
                _hasMoreResults.value = initialResults.size >= pageSize
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
        val consoleIds = if (request.consoles.isEmpty()) emptySet() else {
            val consoles = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { _consoles.first { it.isNotEmpty() } } ?: _consoles.value
            DeepLinkResolver.resolveConsoles(request.consoles, consoles.map { it.id })
        }
        val tags = if (request.tags.isEmpty()) emptySet() else {
            val catalogue = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { _categorizedTags.first { it != null } } ?: _categorizedTags.value
            val known = catalogue?.let { it.regions.tags + it.languages.tags + it.videoStandards.tags + it.contentTypes.tags + it.fileTypes.tags }.orEmpty()
            DeepLinkResolver.resolveTags(request.tags, known)
        }
        _selectedConsoles.value = consoleIds
        _activeTags.value = tags
        _searchQuery.value = request.query.orEmpty()
        request.favouritesOnly?.let { _favouritesOnly.value = it }
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

    fun setSortAsc(ascending: Boolean) {
        _sortAsc.value = ascending
    }

    /** Replace whichever of [categoryTags] are active with [selection] (a subset of them). */
    fun setTagsInCategory(categoryTags: Collection<String>, selection: Set<String>) {
        _activeTags.value = (_activeTags.value - categoryTags.toSet()) + selection
    }

    fun setConsoleSelection(consoleIds: Set<String>) {
        _selectedConsoles.value = consoleIds
    }

    fun clearAllFilters() {
        _searchQuery.value = ""
        _activeTags.value = emptySet()
        _selectedConsoles.value = emptySet()
        _sortAsc.value = true
        _favouritesOnly.value = false
    }

    private suspend fun performSearch(params: FilterParams): List<DownloadableFileWithTags> {
        currentOffset = 0
        return repository.searchFilesWithTags(
            query = params.query,
            consoleIds = params.consoles,
            tags = params.tags,
            favouritesOnly = params.favouritesOnly,
            sortAsc = params.sortAsc,
            limit = pageSize,
            offset = 0
        )
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

    suspend fun loadMore() {
        if (_isLoadingMore.value || !_hasMoreResults.value) return

        _isLoadingMore.value = true
        currentOffset += pageSize

        val newResults = repository.searchFilesWithTags(
            query = _searchQuery.value,
            consoleIds = _selectedConsoles.value,
            tags = _activeTags.value,
            favouritesOnly = _favouritesOnly.value,
            sortAsc = _sortAsc.value,
            limit = pageSize,
            offset = currentOffset
        )

        if (newResults.isEmpty()) {
            _hasMoreResults.value = false
        } else {
            _results.value += newResults
            if (newResults.size < pageSize) _hasMoreResults.value = false
        }

        _isLoadingMore.value = false
    }

    suspend fun startDownload(fileWithTags: DownloadableFileWithTags, context: Context) {
        val downloadDirectory = settingsRepository.downloadDirectory.first()
        if (downloadDirectory.isEmpty()) {
            ToastUtil.showError(context, context.getString(R.string.error_download_dir_missing))
            return
        }

        if (!StorageHelper.isValidUri(context, downloadDirectory)) {
            ToastUtil.showError(context, context.getString(R.string.error_download_dir_inaccessible))
            return
        }

        downloadService.startDownload(fileWithTags.file)
    }
}

data class FilterParams(
    val query: String,
    val consoles: Set<String>,
    val tags: Set<String>,
    val sortAsc: Boolean,
    val favouritesOnly: Boolean = false
)

data class DetailsState(
    val item: DownloadableFileWithTags,
    val loading: Boolean,
    val details: GameDetails? = null
)
