package com.cortinadev.dogmatix.ui.screens.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.model.LibraryGame
import com.cortinadev.dogmatix.data.model.VersionPrefs
import com.cortinadev.dogmatix.data.repository.DownloadRepository
import com.cortinadev.dogmatix.data.repository.DownloadableFileRepository
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.DownloadAutoRetry
import com.cortinadev.dogmatix.data.service.DownloadProgressTracker
import com.cortinadev.dogmatix.data.service.RommUploadService
import com.cortinadev.dogmatix.data.service.UploadState
import com.cortinadev.dogmatix.data.service.QueueState
import com.cortinadev.dogmatix.data.state.PendingDownloadsView
import com.cortinadev.dogmatix.util.DownloadBulk
import com.cortinadev.dogmatix.util.DownloadFailure
import com.cortinadev.dogmatix.util.DownloadFilter
import com.cortinadev.dogmatix.util.DownloadListFilter
import com.cortinadev.dogmatix.util.DownloadQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Statuses each action accepts, shared by the row buttons and the multi-selection bar. */
internal val DownloadStatus.canRetry: Boolean
    get() = this == DownloadStatus.COMPLETED || this == DownloadStatus.STOPPED ||
        this == DownloadStatus.FAILED || this == DownloadStatus.PAUSED

internal val DownloadStatus.canStop: Boolean
    get() = this == DownloadStatus.QUEUED || this == DownloadStatus.DOWNLOADING ||
        this == DownloadStatus.UNZIPPING

/** Same set as [canRetry]: a running download is stopped first, never deleted outright. */
internal val DownloadStatus.canDelete: Boolean get() = canRetry

@HiltViewModel
class DownloadViewModel @Inject constructor(
    private val repository: DownloadRepository,
    private val fileRepository: DownloadableFileRepository,
    private val rommUploadService: RommUploadService,
    private val settingsRepository: SettingsRepository,
    progressTracker: DownloadProgressTracker,
    autoRetry: DownloadAutoRetry,
    private val pendingView: PendingDownloadsView
) : ViewModel() {

    /** Why each failed row failed (shown under its name). */
    val failures: StateFlow<Map<String, DownloadFailure>> = progressTracker.failures

    /** When each failed row will be retried on its own, if it will. */
    val nextRetryAt: StateFlow<Map<String, Long>> = autoRetry.nextRetryAt

    /** Name of the debrid service shown on QUEUED rows ("TorBox 40%"). */
    val debridLabel: StateFlow<String> = settingsRepository.debridProvider.map { it.label }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val downloads: StateFlow<List<DownloadItemModel>> = repository.downloads

    /** RomM upload state per download (see [RommUploadService]). */
    val uploads: StateFlow<Map<String, UploadState>> = rommUploadService.uploads

    fun retryUpload(fileName: String) = rommUploadService.retry(fileName)

    private val detailsCache = mutableMapOf<String, DownloadableFileWithTags?>()

    /**
     * Downloads running or waiting on a debrid service, for the tab badge. The shell reads this
     * instead of [downloads], so it no longer redraws on every progress tick.
     */
    val activeCount: StateFlow<Int> = combine(downloads, repository.queue) { list, queue ->
        // Held rows wait for the user, not for the network: they do not count.
        list.count { (it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED) && it.fileName !in queue.held }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Indexed file + tags for each download, keyed by fileName, so the list can show what each one is. */
    val downloadDetails: StateFlow<Map<String, DownloadableFileWithTags>> = downloads
        // Only when rows come or go, not on every progress tick.
        .map { list -> list.map { it.fileName } }
        .distinctUntilChanged()
        .map { names ->
            names.mapNotNull { name ->
                val details = if (detailsCache.containsKey(name)) {
                    detailsCache[name]
                } else {
                    fileRepository.findByFileName(name).also { detailsCache[name] = it }
                }
                details?.let { name to it }
            }.toMap()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Square covers in front of the rows, as in the Library (Settings → Covers in the list). */
    val showListCovers: StateFlow<Boolean> = settingsRepository.showListCovers
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * The library game behind each download (every version, best first), keyed by fileName, so
     * its row shows the Library's cover: the preferred version's, or another version's when that
     * one has none.
     */
    val downloadGames: StateFlow<Map<String, LibraryGame>> = combine(
        downloadDetails,
        combine(settingsRepository.preferredRegion, settingsRepository.favoriteLanguages) { region, languages -> VersionPrefs(region, languages) }
    ) { details, prefs ->
        val refs = details.values.map { it.file.consoleId to it.file.groupKey }
        val games = runCatching { fileRepository.gamesFor(refs, prefs) }.getOrDefault(emptyList())
            .associateBy { it.consoleId to it.groupKey }
        details.mapNotNull { (name, row) -> games[row.file.consoleId to row.file.groupKey]?.let { name to it } }.toMap()
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** File names ticked for a bulk action; empty = no selection mode. */
    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    init {
        // Rows deleted elsewhere (or by us) must not linger in the selection.
        viewModelScope.launch {
            downloads.collect { list ->
                if (_selection.value.isNotEmpty()) {
                    val alive = list.mapTo(mutableSetOf()) { it.fileName }
                    _selection.value = _selection.value.intersect(alive)
                }
            }
        }
    }

    fun toggleSelection(fileName: String) {
        _selection.value = _selection.value.let { if (fileName in it) it - fileName else it + fileName }
    }

    /** Y on the list: tick every row on screen (search and chip applied), or clear them all when they are. */
    fun toggleSelectAll() {
        val all = view.value.rows.mapTo(mutableSetOf()) { it.fileName }
        _selection.value = if (_selection.value.containsAll(all)) emptySet() else all
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    private fun selectedItems(): List<DownloadItemModel> =
        downloads.value.filter { it.fileName in _selection.value }

    fun retrySelected() = runOnSelection({ it.status.canRetry }) { repository.retryDownload(it.fileName) }

    fun stopSelected() = runOnSelection({ it.status.canStop }) { repository.cancelDownload(it.fileName) }

    /** Any running transfer pauses: torrents keep their session, web downloads their partial file. */
    fun pauseSelected() = runOnSelection({ it.status == DownloadStatus.DOWNLOADING }) { repository.pauseDownload(it.fileName) }

    /**
     * Runs [action] over the ticked rows that [accepts] takes. The selection stays: the rows are
     * still there, their new status is visible, and the bar keeps the focus for a follow-up action.
     */
    private fun runOnSelection(
        accepts: (DownloadItemModel) -> Boolean,
        action: suspend (DownloadItemModel) -> Unit
    ) {
        val targets = selectedItems().filter(accepts)
        viewModelScope.launch { targets.forEach { action(it) } }
    }

    /** Deleting the selection asks about the files only when some of them finished. */
    fun deleteSelected() {
        val targets = selectedItems().filter { it.status.canDelete }
        if (targets.isEmpty()) return
        if (targets.any { it.status == DownloadStatus.COMPLETED }) {
            _showDeleteConfirmation.value = targets.map { it.fileName }
        } else {
            clearSelection()
            deleteDownloads(targets.map { it.fileName }, deleteFile = false)
        }
    }

    /** File names awaiting the "delete the file too?" answer (one row, or a whole selection). */
    private val _showDeleteConfirmation = MutableStateFlow<List<String>?>(null)
    val showDeleteConfirmation: StateFlow<List<String>?> = _showDeleteConfirmation.asStateFlow()

    fun cancelDownload(fileName: String) {
        viewModelScope.launch {
            repository.cancelDownload(fileName)
        }
    }

    fun pauseDownload(fileName: String) {
        viewModelScope.launch { repository.pauseDownload(fileName) }
    }

    fun retryDownload(fileName: String) {
        viewModelScope.launch { 
            repository.retryDownload(fileName) 
        }
    }

    fun deleteDownload(fileName: String, deleteFile: Boolean = false) {
        deleteDownloads(listOf(fileName), deleteFile)
    }

    private fun deleteDownloads(fileNames: List<String>, deleteFile: Boolean) {
        viewModelScope.launch {
            fileNames.forEach { repository.deleteDownload(it, deleteFile) }
        }
    }

    fun deleteDownloadWithConfirmation(fileName: String, isCompleted: Boolean) {
        if (isCompleted) {
            _showDeleteConfirmation.value = listOf(fileName)
        } else {
            deleteDownload(fileName, deleteFile = false)
        }
    }
    
    fun confirmDeleteKeepFile(fileNames: List<String>) {
        _showDeleteConfirmation.value = null
        clearSelection()
        deleteDownloads(fileNames, deleteFile = false)
    }
    
    fun confirmDeleteRemoveFile(fileNames: List<String>) {
        _showDeleteConfirmation.value = null
        clearSelection()
        deleteDownloads(fileNames, deleteFile = true)
    }
    
    fun cancelDeleteConfirmation() {
        _showDeleteConfirmation.value = null
    }

    // dl-cola
    /** Who waits in line, in order, and who is held. */
    val queue: StateFlow<QueueState> = repository.queue

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(DownloadFilter.ALL)
    val filter: StateFlow<DownloadFilter> = _filter.asStateFlow()

    /** The rows on screen: queued ones in line order, then the search and the chip; with the count of each chip. */
    val view: StateFlow<DownloadListFilter.Result> = combine(downloads, queue, _query, _filter) { rows, line, query, filter ->
        DownloadListFilter.apply(DownloadQueue.arrange(rows, line.waiting) { it.fileName }, query, filter, line.waitingSet, line.held)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000),
            DownloadListFilter.apply(downloads.value, "", DownloadFilter.ALL, emptySet(), emptySet())
        )

    fun setQuery(query: String) {
        _query.value = query
    }

    fun setFilter(filter: DownloadFilter) {
        _filter.value = filter
    }

    fun moveUp(fileName: String) = repository.moveUp(fileName)
    fun moveDown(fileName: String) = repository.moveDown(fileName)
    fun moveToFront(fileName: String) = repository.moveToFront(fileName)

    /** Holds a waiting row, or lets a held one go. */
    fun toggleHold(fileName: String) {
        val line = queue.value
        when {
            fileName in line.held -> repository.releaseDownload(fileName)
            fileName in line.waitingSet -> repository.holdDownload(fileName)
        }
    }

    /** Ticked rows that wait in line go first, keeping their order among themselves. */
    fun moveSelectedToFront() {
        val ticked = _selection.value
        queue.value.waiting.filter { it in ticked }.asReversed().forEach(repository::moveToFront)
    }

    fun holdSelected() {
        val line = queue.value
        _selection.value.filter { it in line.waitingSet && it !in line.held }.forEach(repository::holdDownload)
    }

    fun releaseSelected() {
        val held = queue.value.held
        _selection.value.filter { it in held }.forEach(repository::releaseDownload)
    }

    fun pauseAll() = repository.pauseAll()
    fun resumeAll() = repository.resumeAll()
    /** Only the failed rows on screen (search and chip applied), not every failure in the history. */
    fun retryFailed() = repository.retryFailed(DownloadBulk.failed(view.value.rows))

    /** "Clear completed" asks first (focus starts on Cancel). */
    private val _confirmClearCompleted = MutableStateFlow(false)
    val confirmClearCompleted: StateFlow<Boolean> = _confirmClearCompleted.asStateFlow()

    fun requestClearCompleted() {
        _confirmClearCompleted.value = true
    }

    fun dismissClearCompleted() {
        _confirmClearCompleted.value = false
    }

    fun confirmClearCompleted() {
        _confirmClearCompleted.value = false
        repository.clearCompleted()
    }

    /** Chip asked for by a tapped notification (the shell has switched to this tab). */
    val pendingFilter: StateFlow<DownloadFilter?> = pendingView.request

    fun applyPendingFilter() {
        val filter = pendingView.consume() ?: return
        _query.value = ""
        _filter.value = filter
    }

    /** Android 13+ notification permission: asked once, at the first download (null = not read yet). */
    val notificationPermissionAsked: StateFlow<Boolean?> = settingsRepository.notificationPermissionAsked
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun onNotificationPermissionAsked() {
        viewModelScope.launch { settingsRepository.setNotificationPermissionAsked() }
    }
}
