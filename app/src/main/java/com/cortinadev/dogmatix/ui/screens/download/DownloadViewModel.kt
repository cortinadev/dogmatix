package com.cortinadev.dogmatix.ui.screens.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.model.DownloadItemModel
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
    
    private val _showDeleteConfirmation = MutableStateFlow<String?>(null)
    val showDeleteConfirmation: StateFlow<String?> = _showDeleteConfirmation.asStateFlow()

    fun cancelDownload(fileName: String) {
        viewModelScope.launch {
            repository.cancelDownload(fileName)
        }
    }

    fun retryDownload(fileName: String) {
        viewModelScope.launch { 
            repository.retryDownload(fileName) 
        }
    }

    fun deleteDownload(fileName: String, deleteFile: Boolean = false) {
        viewModelScope.launch { 
            repository.deleteDownload(fileName, deleteFile) 
        }
    }
    
    fun deleteDownloadWithConfirmation(fileName: String, isCompleted: Boolean) {
        if (isCompleted) {
            _showDeleteConfirmation.value = fileName
        } else {
            deleteDownload(fileName, deleteFile = false)
        }
    }
    
    fun confirmDeleteKeepFile(fileName: String) {
        _showDeleteConfirmation.value = null
        deleteDownload(fileName, deleteFile = false)
    }
    
    fun confirmDeleteRemoveFile(fileName: String) {
        _showDeleteConfirmation.value = null
        deleteDownload(fileName, deleteFile = true)
    }
    
    fun cancelDeleteConfirmation() {
        _showDeleteConfirmation.value = null
    }
}
