package com.cortinadev.dogmatix.data.repository

import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import kotlinx.coroutines.flow.StateFlow

interface DownloadRepository {
    val downloads: StateFlow<List<DownloadItemModel>>
    suspend fun getDownloads(): List<DownloadItemModel>
    suspend fun startDownload(file: DownloadableFileEntity)
    suspend fun cancelDownload(fileName: String)
    suspend fun retryDownload(fileName: String)
    suspend fun deleteDownload(fileName: String, deleteFile: Boolean = false)
}
