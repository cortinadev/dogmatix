package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.Log
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.model.SeedingMode
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.DownloadFailure
import com.cortinadev.dogmatix.util.StorageHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentFlags
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts a selective torrent file download:
 * 1. Gets the handle from [TorrentHandleRegistry] (instant cache hit after indexing).
 * 2. Sets every file to IGNORE except [DownloadableFileEntity.torrentFileIndex].
 * 3. Resumes the torrent and hands off to [TorrentProgressBridge] for updates.
 */
@Singleton
class TorrentDownloadService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: TorrentHandleRegistry,
    private val progressBridge: TorrentProgressBridge,
    private val downloadFileManager: DownloadFileManager,
    private val progressTracker: DownloadProgressTracker,
    private val settingsRepository: SettingsRepository,
    private val seedingManager: SeedingManager
) {
    data class TorrentFileInfo(val relativePath: String, val expectedSize: Long)

    // Caches file path/size at download-start time so moveTorrentFile can proceed even if the
    // handle is later invalidated (e.g. session stopped during app shutdown before copy finishes).
    private val fileInfoCache = ConcurrentHashMap<String, TorrentFileInfo>()

    fun getFileInfo(fileName: String): TorrentFileInfo? = fileInfoCache[fileName]

    suspend fun startDownload(file: DownloadableFileEntity) = withContext(Dispatchers.IO) {
        val magnet = file.torrentMagnet
            ?: throw IllegalArgumentException("No torrentMagnet on ${file.fileName}")
        val fileIndex = file.torrentFileIndex
            ?: throw IllegalArgumentException("No torrentFileIndex on ${file.fileName}")

        // If this torrent is being seeded, the download flow takes the handle back: the seed
        // monitor must not release it (deleting the cache) under an active download.
        seedingManager.forget(magnet)

        val handle = try {
            registry.getOrFetch(magnet)
        } catch (e: TorrentMetadataTimeoutException) {
            Log.e(TAG, "Metadata timeout: ${e.message}")
            progressTracker.recordFailure(file.fileName, DownloadFailure.TORRENT_METADATA)
            progressTracker.updateDownloadStatus(file.fileName, DownloadStatus.FAILED)
            return@withContext
        }

        val torrentInfo = handle.torrentFile() ?: run {
            Log.e(TAG, "No TorrentInfo for ${file.fileName}")
            progressTracker.recordFailure(file.fileName, DownloadFailure.TORRENT_METADATA)
            progressTracker.updateDownloadStatus(file.fileName, DownloadStatus.FAILED)
            return@withContext
        }

        if (fileIndex >= torrentInfo.numFiles()) {
            Log.e(TAG, "fileIndex $fileIndex out of range")
            progressTracker.recordFailure(file.fileName, DownloadFailure.TORRENT)
            progressTracker.updateDownloadStatus(file.fileName, DownloadStatus.FAILED)
            return@withContext
        }

        val downloadDirUri = downloadFileManager.getDownloadDirectoryUri(file)
        val subPath = downloadFileManager.getSubPath(file)

        val finalDir = StorageHelper.createDirectory(context, downloadDirUri.toString(), subPath)
        if (finalDir == null) {
            Log.e(TAG, "Could not create/access download directory")
            progressTracker.recordFailure(file.fileName, DownloadFailure.STORAGE)
            progressTracker.updateDownloadStatus(file.fileName, DownloadStatus.FAILED)
            return@withContext
        }

        // Cache file path/size before tracking so moveTorrentFile can find the file
        // even if the handle is gone at move time.
        val relativePath = torrentInfo.files().filePath(fileIndex)
        val expectedSize = torrentInfo.files().fileSize(fileIndex)
        fileInfoCache[file.fileName] = TorrentFileInfo(relativePath, expectedSize)

        // Track first so getTrackedFileIndicesForHandle includes this file when building priorities.
        progressBridge.trackDownload(file.fileName, fileIndex, handle)

        // Build merged priorities: IGNORE all files, then DEFAULT for every file currently tracked
        // from this torrent (including the one we just added). This prevents concurrent startDownload
        // calls from clobbering each other's priority and leaving sibling files un-downloaded.
        val priorities = Array(torrentInfo.numFiles()) { Priority.IGNORE }
        for (idx in progressBridge.getTrackedFileIndicesForHandle(handle)) {
            if (idx < priorities.size) priorities[idx] = Priority.DEFAULT
        }
        handle.prioritizeFiles(priorities)
        // The registry fetched metadata in upload mode (no piece requests); lift it now that
        // only the wanted files have a priority.
        handle.unsetFlags(TorrentFlags.UPLOAD_MODE)

        // Only move storage if the torrent isn't already downloading to our cache directory.
        // Calling moveStorage() is asynchronous — redundant calls on the same handle fire
        // unnecessary STORAGE_MOVED_ALERT events and can briefly pause in-flight downloads.
        val torrentDataDir = File(context.cacheDir, "torrent_data").apply { mkdirs() }
        val currentSavePath = try { handle.status().swig().save_path } catch (_: Exception) { null }
        if (currentSavePath != torrentDataDir.absolutePath) {
            handle.moveStorage(torrentDataDir.absolutePath)
        }

        handle.resume()

        Log.i(TAG, "Torrent resumed for ${file.fileName} — TorrentProgressBridge takes over")
    }

    /**
     * Untracks [file] and decides what happens to the torrent once nothing else downloads from
     * it: with "Seed after download" off the handle is released (cached data deleted); otherwise
     * it is handed to [SeedingManager], which keeps it uploading and releases it when the goal
     * is met. Returns true when the cached torrent data must stay on disk for seeding.
     */
    suspend fun finishDownload(file: DownloadableFileEntity, allowSeeding: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        val magnet = file.torrentMagnet ?: return@withContext false
        val fileIndex = file.torrentFileIndex ?: return@withContext false

        fileInfoCache.remove(file.fileName)
        val handle = registry.getCachedHandle(magnet)
        progressBridge.untrackDownload(file.fileName, fileIndex)

        val mode = if (allowSeeding) settingsRepository.seedingMode.first() else SeedingMode.OFF
        val seeding = mode != SeedingMode.OFF && handle != null

        val stillTracked = if (handle != null) progressBridge.countTrackedForHandle(handle) else 0
        when {
            stillTracked > 0 ->
                Log.i(TAG, "Handle kept alive after move for ${file.fileName} ($stillTracked files still active)")
            seeding -> {
                seedingManager.startSeeding(magnet, mode)
                Log.i(TAG, "Handle kept seeding (${mode.name}) after move for ${file.fileName}")
            }
            else -> {
                registry.releaseHandle(magnet)
                Log.i(TAG, "Handle released after successful move for ${file.fileName}")
            }
        }
        seeding
    }

    /**
     * Parks [file]: its priority drops to IGNORE and the bridge stops tracking it, but the
     * handle and the partial data in the cache stay, so resuming (the retry flow) continues
     * from the pieces already on disk. With nothing else active the torrent is paused.
     */
    suspend fun pauseDownload(file: DownloadableFileEntity) = withContext(Dispatchers.IO) {
        val magnet = file.torrentMagnet ?: return@withContext
        val fileIndex = file.torrentFileIndex ?: return@withContext
        progressBridge.untrackDownload(file.fileName, fileIndex)
        val handle = registry.getCachedHandle(magnet) ?: return@withContext
        val torrentInfo = handle.torrentFile() ?: return@withContext
        val priorities = Array(torrentInfo.numFiles()) { Priority.IGNORE }
        for (idx in progressBridge.getTrackedFileIndicesForHandle(handle)) {
            if (idx < priorities.size) priorities[idx] = Priority.DEFAULT
        }
        handle.prioritizeFiles(priorities)
        if (progressBridge.countTrackedForHandle(handle) == 0) {
            try { handle.pause() } catch (e: Exception) { Log.w(TAG, "pause: ${e.message}") }
        }
        Log.i(TAG, "Paused ${file.fileName} (cache kept)")
    }

    suspend fun cancelDownload(file: DownloadableFileEntity) = withContext(Dispatchers.IO) {
        val magnet = file.torrentMagnet ?: return@withContext
        val fileIndex = file.torrentFileIndex ?: return@withContext

        fileInfoCache.remove(file.fileName)
        val handle = registry.getCachedHandle(magnet)
        progressBridge.untrackDownload(file.fileName, fileIndex)

        val remainingForThisTorrent = if (handle != null) progressBridge.countTrackedForHandle(handle) else 0
        if (remainingForThisTorrent == 0) {
            registry.releaseHandle(magnet)
            Log.i(TAG, "Stopped and purged torrent download for ${file.fileName}")
        } else {
            Log.i(TAG, "Stopped tracking ${file.fileName}, handle kept alive ($remainingForThisTorrent files still active)")
        }

        progressTracker.updateDownloadStatus(file.fileName, DownloadStatus.STOPPED)
    }

    companion object { private const val TAG = "TorrentDownloadService" }
}
