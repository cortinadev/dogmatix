package com.cortinadev.dogmatix.data.repository

import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.service.DownloadQueueService
import com.cortinadev.dogmatix.data.service.DownloadService
import com.cortinadev.dogmatix.data.service.QueueState
import com.cortinadev.dogmatix.util.DownloadBulk
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepositoryImpl @Inject constructor(
    private val downloadService: DownloadService,
    private val downloadQueue: DownloadQueueService
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

    override suspend fun pauseDownload(fileName: String) {
        downloadService.pauseDownload(fileName)
    }

    override suspend fun deleteDownload(fileName: String, deleteFile: Boolean) {
        downloadService.deleteDownload(fileName, deleteFile)
    }

    // dl-cola
    override val queue: StateFlow<QueueState> = downloadQueue.state
    override fun moveUp(fileName: String) = downloadQueue.moveUp(fileName)
    override fun moveDown(fileName: String) = downloadQueue.moveDown(fileName)
    override fun moveToFront(fileName: String) = downloadQueue.moveToFront(fileName)
    override fun holdDownload(fileName: String) = downloadService.holdDownload(fileName)
    override fun releaseDownload(fileName: String) = downloadService.releaseDownload(fileName)

    override fun pauseAll() =
        DownloadBulk.pausable(downloadService.getDownloads(), downloadQueue.heldNames()).forEach(downloadService::pauseDownload)

    /** Paused rows go back to their places in line and start by that order. */
    override fun resumeAll() {
        val paused = DownloadBulk.resumable(downloadService.getDownloads())
        downloadQueue.batch { paused.forEach(downloadService::retryDownload) }
    }

    override fun retryFailed(fileNames: List<String>) = fileNames.forEach(downloadService::retryDownload)

    override fun stopAll() = DownloadBulk.stoppable(downloadService.getDownloads()).forEach(downloadService::cancelDownload)

    /** Off the list only: the files stay in the folder. */
    override fun clearCompleted() =
        DownloadBulk.completed(downloadService.getDownloads()).forEach { downloadService.deleteDownload(it, deleteFile = false) }
}
