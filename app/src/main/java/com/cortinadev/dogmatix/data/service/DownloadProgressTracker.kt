package com.cortinadev.dogmatix.data.service

import android.util.Log
import com.cortinadev.dogmatix.data.local.dao.DownloadHistoryDao
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadProgressTracker @Inject constructor(
    private val historyDao: DownloadHistoryDao
) {

    private val _downloads = MutableStateFlow<List<DownloadItemModel>>(emptyList())
    val downloads: StateFlow<List<DownloadItemModel>> = _downloads

    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Seeds the list with entries persisted from previous runs (see [DownloadService]). */
    fun restore(items: List<DownloadItemModel>) {
        _downloads.update { current ->
            val known = current.map { it.fileName }.toSet()
            items.filter { it.fileName !in known } + current
        }
    }

    fun updateDownloadStatus(fileName: String, status: DownloadStatus) {
        var changed: DownloadItemModel? = null
        _downloads.update { list ->
            list.map { item ->
                if (item.fileName != fileName) item
                else {
                    val updated = item.copy(status = status)
                    val finished = if (updated.isFinished) item.finishedAt ?: System.currentTimeMillis() else null
                    updated.copy(finishedAt = finished).also { changed = it }
                }
            }
        }
        changed?.let { persistStatus(it) }
    }

    private fun persistStatus(item: DownloadItemModel) {
        persistScope.launch {
            try {
                historyDao.updateStatus(item.fileName, item.status.name, item.finishedAt)
            } catch (e: Exception) {
                Log.e("DownloadProgressTracker", "Could not persist status for ${item.fileName}: ${e.message}")
            }
        }
    }

    private val lastUpdateTimes = ConcurrentHashMap<String, Long>()

    fun updateDownloadProgress(fileName: String, progress: Float, speed: Float, downloadedBytes: Long) {
        val now = System.currentTimeMillis()
        val lastUpdate = lastUpdateTimes[fileName] ?: 0L

        if (shouldUpdateProgress(progress, lastUpdate, now)) {
            lastUpdateTimes[fileName] = now
            _downloads.update { list ->
                list.map { item ->
                    if (item.fileName == fileName) {
                        item.copy(
                            progress = progress,
                            downloadSpeed = speed,
                            downloadedBytes = downloadedBytes
                        )
                    } else {
                        item
                    }
                }
            }
        }
    }

    fun addDownload(downloadItem: DownloadItemModel) {
        _downloads.update { list -> list.filter { it.fileName != downloadItem.fileName } + downloadItem }
    }

    fun removeDownload(fileName: String) {
        _downloads.update { list -> list.filter { it.fileName != fileName } }
        lastUpdateTimes.remove(fileName)
    }

    fun getDownloads(): List<DownloadItemModel> {
        return _downloads.value
    }

    fun resetDownloadForRetry(fileName: String) {
        _downloads.update { list ->
            list.map { item ->
                if (item.fileName == fileName) {
                    item.copy(
                        status = DownloadStatus.DOWNLOADING,
                        progress = 0f,
                        downloadSpeed = 0f,
                        downloadedBytes = 0L,
                        startedAt = System.currentTimeMillis(),
                        finishedAt = null
                    )
                } else {
                    item
                }
            }
        }
    }

    /** Finished rows (completed included — the UI offers "download again"), stopped or failed. */
    fun canRetryDownload(fileName: String): Boolean {
        return _downloads.value.any {
            it.fileName == fileName &&
            (it.status == DownloadStatus.FAILED || it.status == DownloadStatus.STOPPED ||
             it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.PAUSED)
        }
    }

    /** True while [fileName] is queued, downloading, copying or extracting. */
    fun isActive(fileName: String): Boolean =
        _downloads.value.any { it.fileName == fileName && !it.isFinished }

    fun hasActiveDownloads(): Boolean {
        return _downloads.value.any {
            it.status == DownloadStatus.QUEUED ||
            it.status == DownloadStatus.DOWNLOADING ||
            it.status == DownloadStatus.COPYING ||
            it.status == DownloadStatus.UNZIPPING
        }
    }

    fun shouldUpdateProgress(progress: Float, lastUpdateTime: Long, currentTime: Long): Boolean {
        return progress >= Constants.PROGRESS_COMPLETE ||
               (currentTime - lastUpdateTime) > Constants.PROGRESS_UPDATE_INTERVAL_MS
    }
}
