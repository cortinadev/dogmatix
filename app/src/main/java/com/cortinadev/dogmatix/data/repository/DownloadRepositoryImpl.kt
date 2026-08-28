package com.cortinadev.dogmatix.data.repository

import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.service.DownloadService
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepositoryImpl @Inject constructor(
    private val downloadService: DownloadService
) : DownloadRepository {

    override val downloads: StateFlow<List<DownloadItemModel>> = downloadService.downloads

    override suspend fun getDownloads(): List<DownloadItemModel> {
        return downloadService.getDownloads()
    }

    override suspend fun startDownload(file: DownloadableFileEntity) {
        downloadService.startDownload(file)
    }

    override suspend fun cancelDownload(fileName: String) {
        downloadService.cancelDownload(fileName)
    }

    override suspend fun retryDownload(fileName: String) {
        downloadService.retryDownload(fileName)
    }

    override suspend fun deleteDownload(fileName: String, deleteFile: Boolean) {
        downloadService.deleteDownload(fileName, deleteFile)
    }
}
