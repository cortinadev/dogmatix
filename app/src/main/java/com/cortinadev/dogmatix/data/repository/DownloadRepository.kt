package com.cortinadev.dogmatix.data.repository

import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.service.QueueState
import kotlinx.coroutines.flow.StateFlow

interface DownloadRepository {
    val downloads: StateFlow<List<DownloadItemModel>>
    suspend fun getDownloads(): List<DownloadItemModel>
    suspend fun startDownload(file: DownloadableFileEntity)
    suspend fun cancelDownload(fileName: String)
    suspend fun retryDownload(fileName: String)
    suspend fun pauseDownload(fileName: String)
    suspend fun deleteDownload(fileName: String, deleteFile: Boolean = false)

    // dl-cola
    /** Who waits in line, in order, and who is held. */
    val queue: StateFlow<QueueState>
    fun moveUp(fileName: String)
    fun moveDown(fileName: String)
    /** "Download next": first in line. */
    fun moveToFront(fileName: String)
    fun holdDownload(fileName: String)
    fun releaseDownload(fileName: String)
    /** The whole-list buttons (Downloads header, notification); see [com.cortinadev.dogmatix.util.DownloadBulk]. */
    fun pauseAll()
    fun resumeAll()
    /** Retries these failed rows: the ones the Downloads list shows, not the whole history. */
    fun retryFailed(fileNames: List<String>)
    fun stopAll()
    fun clearCompleted()
}
