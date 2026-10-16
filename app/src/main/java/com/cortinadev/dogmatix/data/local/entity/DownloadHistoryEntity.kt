package com.cortinadev.dogmatix.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus

/**
 * One row per entry of the Downloads list, so the list survives app restarts.
 * Keeps enough of the indexed file to rebuild a [DownloadableFileEntity] for retries,
 * even if the file has since been re-indexed or removed from the library.
 */
@Entity(tableName = "download_history")
data class DownloadHistoryEntity(
    @PrimaryKey val fileName: String,
    val name: String,
    val consoleId: String,
    val downloadUrl: String,
    val fileSize: Long,
    val fileExtension: String,
    val torrentFileIndex: Int?,
    val torrentMagnet: String?,
    val status: String,
    /** Epoch millis when the download was (last) started. */
    val startedAt: Long,
    /** Epoch millis when it reached COMPLETED / FAILED / STOPPED; null while in progress. */
    val finishedAt: Long?,
    /** Debrid service + its torrent/file ids while a debrid download is in flight, so a retry can resume it. */
    @ColumnInfo(defaultValue = "NULL") val debridProvider: String? = null,
    @ColumnInfo(defaultValue = "NULL") val debridTorrentId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val debridFileId: Int? = null,
    /** Why the last attempt failed, a [com.cortinadev.dogmatix.util.DownloadFailure] name; null otherwise. */
    @ColumnInfo(defaultValue = "NULL") val failureReason: String? = null,
    // dl-cola
    /** Place in the download queue (smaller starts first, see [com.cortinadev.dogmatix.util.DownloadQueue]). */
    @ColumnInfo(defaultValue = "0") val queueOrder: Long = 0L,
    /** Held in the queue: waits without starting until let go. */
    @ColumnInfo(defaultValue = "0") val queueHeld: Boolean = false
) {
    fun toEntity(): DownloadableFileEntity = DownloadableFileEntity(
        name = name,
        fileName = fileName,
        consoleId = consoleId,
        downloadUrl = downloadUrl,
        fileSize = fileSize,
        fileExtension = fileExtension,
        torrentFileIndex = torrentFileIndex,
        torrentMagnet = torrentMagnet
    )

    /** Whether the row was still running when the process died (see [com.cortinadev.dogmatix.data.service.DownloadService.resumeInterrupted]). */
    val wasInFlight: Boolean
        get() = status == DownloadStatus.QUEUED.name || status == DownloadStatus.DOWNLOADING.name ||
            status == DownloadStatus.COPYING.name || status == DownloadStatus.UNZIPPING.name

    /** In-flight statuses can't be resumed after a process death, so they come back as STOPPED. */
    fun toItem(): DownloadItemModel {
        val restored = when (val s = DownloadStatus.valueOf(status)) {
            DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.COPYING, DownloadStatus.UNZIPPING -> DownloadStatus.STOPPED
            // A paused torrent comes back as STOPPED: the startup sweep clears the cache its resume
            // relied on. A paused web download keeps its partial file, so it stays paused.
            DownloadStatus.PAUSED -> if (torrentFileIndex != null) DownloadStatus.STOPPED else DownloadStatus.PAUSED
            else -> s
        }
        return DownloadItemModel(
            name = name,
            fileName = fileName,
            downloadSpeed = 0f,
            progress = if (restored == DownloadStatus.COMPLETED) 1f else 0f,
            fileSize = fileSize,
            downloadedBytes = if (restored == DownloadStatus.COMPLETED) fileSize else 0L,
            status = restored,
            startedAt = startedAt,
            finishedAt = finishedAt ?: if (restored == DownloadStatus.STOPPED) startedAt else null
        )
    }

    companion object {
        fun from(file: DownloadableFileEntity, item: DownloadItemModel) = DownloadHistoryEntity(
            fileName = file.fileName,
            name = file.name,
            consoleId = file.consoleId,
            downloadUrl = file.downloadUrl,
            fileSize = file.fileSize,
            fileExtension = file.fileExtension,
            torrentFileIndex = file.torrentFileIndex,
            torrentMagnet = file.torrentMagnet,
            status = item.status.name,
            startedAt = item.startedAt,
            finishedAt = item.finishedAt
        )
    }
}
