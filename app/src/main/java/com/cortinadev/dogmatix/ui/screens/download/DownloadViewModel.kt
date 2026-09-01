package com.cortinadev.dogmatix.ui.screens.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.repository.DownloadRepository
import com.cortinadev.dogmatix.data.repository.DownloadableFileRepository
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.RommUploadService
import com.cortinadev.dogmatix.data.service.UploadState
import kotlinx.coroutines.flow.SharingStarted
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
    settingsRepository: SettingsRepository
) : ViewModel() {

    /** Name of the debrid service shown on QUEUED rows ("TorBox 40%"). */
    val debridLabel: StateFlow<String> = settingsRepository.debridProvider.map { it.label }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val downloads: StateFlow<List<DownloadItemModel>> = repository.downloads

    /** RomM upload state per download (see [RommUploadService]). */
    val uploads: StateFlow<Map<String, UploadState>> = rommUploadService.uploads

    fun retryUpload(fileName: String) = rommUploadService.retry(fileName)

    private val detailsCache = mutableMapOf<String, DownloadableFileWithTags?>()

    /** Indexed file + tags for each download, keyed by fileName, so the list can show what each one is. */
    val downloadDetails: StateFlow<Map<String, DownloadableFileWithTags>> = downloads
        .map { list ->
            list.mapNotNull { item ->
                val details = if (detailsCache.containsKey(item.fileName)) {
                    detailsCache[item.fileName]
                } else {
                    fileRepository.findByFileName(item.fileName).also { detailsCache[item.fileName] = it }
                }
                details?.let { item.fileName to it }
            }.toMap()
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

    /** Y on the list: tick everything, or clear it when everything is already ticked. */
    fun toggleSelectAll() {
        val all = downloads.value.mapTo(mutableSetOf()) { it.fileName }
        _selection.value = if (_selection.value.containsAll(all)) emptySet() else all
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    private fun selectedItems(): List<DownloadItemModel> =
        downloads.value.filter { it.fileName in _selection.value }

    fun retrySelected() = runOnSelection({ it.status.canRetry }) { repository.retryDownload(it.fileName) }

    fun stopSelected() = runOnSelection({ it.status.canStop }) { repository.cancelDownload(it.fileName) }

    /** Only torrents can be paused; the rest have no resumable session to hold. */
    fun pauseSelected() = runOnSelection({
        it.status == DownloadStatus.DOWNLOADING && downloadDetails.value[it.fileName]?.file?.isTorrent == true
    }) { repository.pauseDownload(it.fileName) }

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
}
