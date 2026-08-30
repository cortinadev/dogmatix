package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.cortinadev.dogmatix.data.local.dao.DownloadHistoryDao
import com.cortinadev.dogmatix.data.local.entity.DownloadHistoryEntity
import com.cortinadev.dogmatix.data.local.entity.DownloadableFileEntity
import com.cortinadev.dogmatix.data.model.DebridProvider
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.ArchiveUtils
import com.cortinadev.dogmatix.util.ArchiveExtractionUtils
import com.cortinadev.dogmatix.util.Constants
import com.cortinadev.dogmatix.util.RommSource
import com.cortinadev.dogmatix.util.DebridMatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DownloadService"
/** Upper bound for an uncached torrent to be fetched by the debrid service before we give up. */
private const val DEBRID_MAX_WAIT_MS = 6 * 60 * 60 * 1000L

@Singleton
class DownloadService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val archiveExtractorService: ArchiveExtractorService,
    private val downloadSpeedController: DownloadSpeedController,
    private val downloadHttpClient: DownloadHttpClient,
    private val downloadProgressTracker: DownloadProgressTracker,
    private val downloadFileManager: DownloadFileManager,
    private val torrentDownloadService: TorrentDownloadService,
    private val torrentHandleRegistry: TorrentHandleRegistry,
    private val historyDao: DownloadHistoryDao,
    private val torBoxClient: TorBoxClient,
    private val realDebridClient: RealDebridClient,
    private val rommClient: RommClient
) {
    val downloads: StateFlow<List<DownloadItemModel>> = downloadProgressTracker.downloads

    /**
     * File names whose download is really finished and on disk (after copy / extraction). The
     * torrent bridge flips a row to COMPLETED as soon as libtorrent is done, before the file is
     * moved into place, so status watchers cannot tell "done" from "about to be copied".
     */
    private val _finished = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val finished: SharedFlow<String> = _finished.asSharedFlow()

    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private var downloadSemaphore = Semaphore(3)
    private var foregroundServiceStarted = false
    private val downloadEntities = ConcurrentHashMap<String, DownloadableFileEntity>()
    private val extractedFilesMap = ConcurrentHashMap<String, List<String>>()
    /** Debrid client + torrent id per file being fetched through the debrid route (see [performDebridDownload]). */
    private val debridTorrents = ConcurrentHashMap<String, Pair<DebridClient, String>>()

    // Single supervised scope for all internal coroutines — tied to this singleton's lifetime
    // so jobs are not orphaned if the service is destroyed.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        serviceScope.launch {
            settingsRepository.concurrentDownloads.collect { max ->
                downloadSemaphore = Semaphore(max)
            }
        }
        serviceScope.launch { restoreHistory() }
    }

    /**
     * Brings back the Downloads list from the previous run. Anything that was still in
     * flight when the process died comes back as STOPPED so the user can retry it.
     */
    private suspend fun restoreHistory() {
        try {
            val rows = historyDao.getAll()
            rows.forEach { row -> downloadEntities.putIfAbsent(row.fileName, row.toEntity()) }
            val items = rows.map { it.toItem() }
            downloadProgressTracker.restore(items)
            items.filter { it.status == DownloadStatus.STOPPED }.forEach { item ->
                val row = rows.first { it.fileName == item.fileName }
                if (row.status != item.status.name || row.finishedAt != item.finishedAt) {
                    historyDao.updateStatus(item.fileName, item.status.name, item.finishedAt)
                }
            }
            Log.d(TAG, "Restored ${rows.size} download(s) from history")
        } catch (e: Exception) {
            Log.e(TAG, "Could not restore download history: ${e.message}")
        }
    }

    fun startDownload(file: DownloadableFileEntity) {
        // Ignore repeated taps: an in-flight download for this file must not be launched twice.
        if (downloadProgressTracker.isActive(file.fileName)) return
        val item = downloadFileManager.createDownloadItem(file)
        downloadProgressTracker.addDownload(item)
        downloadEntities[file.fileName] = file
        serviceScope.launch { historyDao.upsert(DownloadHistoryEntity.from(file, item)) }
        startForegroundService()

        val job = serviceScope.launch {
            try {
                downloadSemaphore.withPermit {
                    // Brief delay to allow the foreground service and initial UI state to settle
                    // before network/torrent activity begins.
                    delay(1000L)
                    perform(file)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                updateStatus(file.fileName, DownloadStatus.STOPPED)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${file.fileName}: ${e.message}")
                updateStatus(file.fileName, DownloadStatus.FAILED)
            } finally {
                downloadJobs.remove(file.fileName)
            }
        }
        downloadJobs[file.fileName] = job
    }

    /** Routes a file to the debrid, torrent or plain HTTP path (decided at start and on every retry). */
    private suspend fun perform(file: DownloadableFileEntity) {
        val debrid = if (file.isTorrent) debridClient(settingsRepository.debridProvider.first()) else null
        when {
            debrid != null -> performDebridDownload(file, debrid)
            file.isTorrent -> performTorrentDownload(file)
            else -> performHttpDownload(file)
        }
    }

    private fun debridClient(provider: DebridProvider): DebridClient? = when (provider) {
        DebridProvider.NONE -> null
        DebridProvider.TORBOX -> torBoxClient
        DebridProvider.REAL_DEBRID -> realDebridClient
    }

    fun cancelDownload(fileName: String) {
        downloadJobs.remove(fileName)?.cancel()
        val entity = downloadEntities[fileName] ?: return
        serviceScope.launch {
            val debrid = debridTorrents.remove(fileName)
            when {
                debrid != null -> {
                    updateStatus(fileName, DownloadStatus.STOPPED)
                    historyDao.setDebrid(fileName, null, null, null)
                    debrid.first.delete(debrid.second)
                }
                entity.isTorrent -> torrentDownloadService.cancelDownload(entity)
                else -> updateStatus(fileName, DownloadStatus.STOPPED)
            }
        }
    }

    fun retryDownload(fileName: String) {
        if (!downloadProgressTracker.canRetryDownload(fileName)) return
        val entity = downloadEntities[fileName] ?: return
        // Reset the existing list entry in place — calling startDownload would add a duplicate.
        downloadProgressTracker.resetDownloadForRetry(fileName)
        serviceScope.launch {
            historyDao.markRestarted(fileName, DownloadStatus.DOWNLOADING.name, System.currentTimeMillis())
        }
        startForegroundService()
        val job = serviceScope.launch {
            try {
                downloadSemaphore.withPermit {
                    delay(1000L)
                    perform(entity)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                updateStatus(entity.fileName, DownloadStatus.STOPPED)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Retry failed for ${entity.fileName}: ${e.message}")
                updateStatus(entity.fileName, DownloadStatus.FAILED)
            } finally {
                downloadJobs.remove(entity.fileName)
            }
        }
        downloadJobs[entity.fileName] = job
    }

    fun deleteDownload(fileName: String, deleteFile: Boolean = false) {
        downloadJobs.remove(fileName)?.cancel()
        val entity = downloadEntities.remove(fileName)
        val extracted = extractedFilesMap.remove(fileName) ?: emptyList()
        val debrid = debridTorrents.remove(fileName)
        serviceScope.launch {
            if (debrid != null) debrid.first.delete(debrid.second)
            else if (entity?.isTorrent == true) torrentDownloadService.cancelDownload(entity)
            if (deleteFile && entity != null) downloadFileManager.deleteFileByName(entity, true, extracted)
            historyDao.delete(fileName)
        }
        downloadProgressTracker.removeDownload(fileName)
    }

    fun cancelAllDownloads() {
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()
    }

    fun getDownloads(): List<DownloadItemModel> = downloadProgressTracker.getDownloads()

    /** The indexed file behind a download in this process (restored history included). */
    fun entityFor(fileName: String): DownloadableFileEntity? = downloadEntities[fileName]

    /** Names on disk for a finished download: the extracted files, or the file itself. */
    fun uploadCandidates(fileName: String): List<String> =
        extractedFilesMap[fileName]?.takeIf { it.isNotEmpty() }
            ?: listOf(com.cortinadev.dogmatix.util.FileParsingUtils.decodeUrlEncodedFileName(fileName))

    private suspend fun performTorrentDownload(file: DownloadableFileEntity) {
        Log.d(TAG, "Starting torrent download for ${file.fileName}")
        torrentDownloadService.startDownload(file)

        // Collect just this file's status as a distinct flow instead of polling the full
        // downloads list on every tick — O(1) vs O(n) and no busy-wait sleep.
        val finalStatus = downloadProgressTracker.downloads
            .map { list -> list.find { it.fileName == file.fileName }?.status }
            .distinctUntilChanged()
            .first { it == DownloadStatus.COMPLETED || it == DownloadStatus.FAILED || it == DownloadStatus.STOPPED }

        when (finalStatus) {
            DownloadStatus.FAILED  -> { Log.e(TAG, "Torrent FAILED: ${file.fileName}"); return }
            DownloadStatus.STOPPED -> { Log.i(TAG, "Torrent STOPPED: ${file.fileName}"); return }
            else -> moveTorrentFile(file)
        }
    }

    private suspend fun moveTorrentFile(file: DownloadableFileEntity) {
        try {
            val downloadDirUri = downloadFileManager.getDownloadDirectoryUri(file)
            if (downloadDirUri == android.net.Uri.EMPTY)
                throw Exception("Download directory not configured or no longer accessible.")

            // Use the info cached at download-start time so this works even if the handle was
            // invalidated (e.g. session stopped during app shutdown before the copy finishes).
            val fileInfo = torrentDownloadService.getFileInfo(file.fileName)
                ?: throw Exception("Could not get torrent file info for ${file.fileName}")
            val relativePath = fileInfo.relativePath
            val expectedSize = fileInfo.expectedSize
            val fileExtension = relativePath.substringAfterLast(".", "")

            val internalFile = File(context.cacheDir, "torrent_data/$relativePath")
            Log.d(TAG, "Internal torrent file: ${internalFile.absolutePath}, exists: ${internalFile.exists()}")

            if (!internalFile.exists())
                throw Exception("Internal torrent file not found at ${internalFile.absolutePath}")

            // libtorrent marks a file complete (via fileProgress) after hash-verification,
            // but its disk thread flushes writes asynchronously. Poll until the OS-visible
            // file size matches the torrent metadata size before we copy.
            // Also bail early if the session has been stopped (e.g. app shutdown) — the file
            // will never grow any further and the job should fail fast rather than wait 15s.
            var waitedMs = 0
            while (internalFile.length() < expectedSize && waitedMs < 15_000 && torrentHandleRegistry.isRunning) {
                Log.d(TAG, "Waiting for disk flush for ${file.fileName}: ${internalFile.length()}/$expectedSize bytes")
                delay(500)
                waitedMs += 500
            }
            if (internalFile.length() < expectedSize) {
                Log.w(TAG, "Disk flush incomplete for ${file.fileName}: ${internalFile.length()}/$expectedSize bytes written")
            }

            val subPath = downloadFileManager.getSubPath(file)

            if (ArchiveUtils.isExtractable(fileExtension) && settingsRepository.autoUnzip.first()) {
                // Extract directly from cache — skips writing the compressed archive to SAF entirely.
                // Flow: cacheDir/torrent_data/ → extraction_temp/ → SAF destination
                Log.d(TAG, "Extracting torrent archive directly from cache: ${internalFile.name}")
                updateStatus(file.fileName, DownloadStatus.UNZIPPING)
                val extracted = archiveExtractorService.extractArchiveFile(
                    context, internalFile, downloadDirUri, subPath
                )
                if (extracted.isNotEmpty()) {
                    extractedFilesMap[file.fileName] = extracted
                } else {
                    Log.w(TAG, "Extraction produced no files for ${file.fileName}")
                }
            } else {
                // Non-archive or auto-unzip disabled: copy directly from cache to SAF
                val documentFile = downloadFileManager.createDocumentFile(file, downloadDirUri.toString(), subPath)
                    ?: throw Exception("Failed to create destination file in storage.")
                Log.d(TAG, "Copying torrent file to SAF: ${documentFile.uri}")
                updateStatus(file.fileName, DownloadStatus.COPYING)
                context.contentResolver.openOutputStream(documentFile.uri)?.use { out ->
                    BufferedOutputStream(out, Constants.EXTRACTION_BUFFER_SIZE).use { buffOut ->
                        internalFile.inputStream().use { it.copyTo(buffOut, Constants.EXTRACTION_BUFFER_SIZE) }
                    }
                } ?: throw Exception("Could not open output stream for ${documentFile.uri}")
            }

            // Clean up cache
            internalFile.delete()
            internalFile.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()

            // Release handle only when no sibling files still downloading from same torrent
            torrentDownloadService.finishDownload(file)

            Log.i(TAG, "Torrent processed successfully: ${file.fileName}")
            updateStatus(file.fileName, DownloadStatus.COMPLETED)
            _finished.tryEmit(file.fileName)
            checkServiceLifecycle()

        } catch (e: Exception) {
            Log.e(TAG, "Error processing torrent file for ${file.fileName}: ${e.message}", e)
            updateStatus(file.fileName, DownloadStatus.FAILED)
            // Untrack and, if nothing else uses the torrent, release it (deletes the cached data);
            // a retry re-fetches the metadata and starts clean instead of leaving a partial behind.
            runCatching { torrentDownloadService.finishDownload(file) }
        }
    }

    /**
     * Debrid route: the service (TorBox, Real-Debrid) fetches the torrent server-side, then the
     * file comes down over plain HTTP through [performHttpDownload] (same throttling, SAF write
     * and extraction).
     */
    private suspend fun performDebridDownload(file: DownloadableFileEntity, client: DebridClient) {
        val magnet = file.torrentMagnet ?: throw Exception("Missing magnet for ${file.fileName}")
        Log.d(TAG, "Starting ${client.provider.label} download for ${file.fileName}")
        updateStatus(file.fileName, DownloadStatus.QUEUED)

        try {
            performDebridDownloadInner(file, client, magnet)
        } catch (e: Exception) {
            // Leave nothing behind on the account when the debrid route gives up.
            releaseDebrid(file.fileName)
            throw e
        }
    }

    /** Removes the torrent from the debrid account, detached from the (cancellable) download job. */
    private fun releaseDebrid(fileName: String) {
        val (client, id) = debridTorrents.remove(fileName) ?: return
        serviceScope.launch { client.delete(id) }
    }

    private suspend fun performDebridDownloadInner(file: DownloadableFileEntity, client: DebridClient, magnet: String) {
        val label = client.provider.label
        // A previous run that died mid-transfer left the ids in the history: pick the same
        // torrent up again and resume the HTTP transfer from what is already on disk.
        val previous = historyDao.getByFileName(file.fileName)
        val resumed = previous?.debridTorrentId?.takeIf { previous.debridProvider == client.provider.name }?.let { id ->
            runCatching { client.getTorrent(id) }.getOrNull()
                ?.takeIf { it.downloadFinished }
                ?.let { t -> t.files.firstOrNull { it.id == previous.debridFileId }?.let { f -> t to f } }
        }
        if (resumed != null) {
            val (torrent, remote) = resumed
            debridTorrents[file.fileName] = client to torrent.id
            Log.d(TAG, "Resuming $label download of ${file.fileName} (torrent ${torrent.id}, file ${remote.id})")
            updateStatus(file.fileName, DownloadStatus.DOWNLOADING)
            performHttpDownload(file, resumable = true) { client.requestDownload(torrent.id, remote.id) }
            historyDao.setDebrid(file.fileName, null, null, null)
            releaseDebrid(file.fileName)
            return
        }

        val hash = DebridMatcher.infoHashFromMagnet(magnet)
        val cached = hash?.let { client.isCached(it) } == true
        var torrentId = client.createTorrent(magnet)
        debridTorrents[file.fileName] = client to torrentId

        /** Polls until [done] holds; cached torrents get there on the first check. */
        suspend fun await(id: String, done: (DebridTorrent) -> Boolean): DebridTorrent {
            val started = System.currentTimeMillis()
            var torrent = client.getTorrent(id)
            var pollErrors = 0
            while (!done(torrent)) {
                torrent.failure?.let { throw Exception(it) }
                if (System.currentTimeMillis() - started > DEBRID_MAX_WAIT_MS) throw Exception("$label did not finish fetching ${file.fileName} in time")
                downloadProgressTracker.updateDownloadProgress(file.fileName, torrent.progress, 0f, (torrent.progress * file.fileSize).toLong())
                val elapsed = System.currentTimeMillis() - started
                delay(when { cached || elapsed < 30_000L -> 3_000L; elapsed < 5 * 60_000L -> 10_000L; else -> 30_000L })
                // Services answer 5xx now and then while a torrent is being fetched: keep polling.
                torrent = runCatching { client.getTorrent(id) }.getOrElse { e ->
                    if (e is DebridAuthException || ++pollErrors > 5) throw e
                    Log.w(TAG, "$label poll error (${pollErrors}/5): ${e.message}")
                    torrent
                }
            }
            return torrent
        }
        suspend fun awaitListed(id: String) = await(id) { it.filesKnown || it.downloadFinished }
        suspend fun awaitFinished(id: String) = await(id) { it.downloadFinished }.also { t ->
            Log.d(TAG, "$label torrent ${t.id} '${t.name}' files=${t.files.size}: " + t.files.take(5).joinToString { "${it.id}:${it.name}(${it.size})" })
        }

        var torrent = awaitListed(torrentId)
        var remote = DebridMatcher.pickFile(torrent.files, file.fileName, file.fileSize)
        if (remote == null && torrent.files.size == 1 && torrent.files[0].name.endsWith(".zip", ignoreCase = true)) {
            // TorBox zipped the whole torrent (an earlier add without allow_zip=false); re-add it unzipped.
            Log.w(TAG, "$label holds ${torrent.name} as a single zip; re-adding it unzipped")
            client.delete(torrentId)
            torrentId = client.createTorrent(magnet)
            debridTorrents[file.fileName] = client to torrentId
            torrent = awaitListed(torrentId)
            remote = DebridMatcher.pickFile(torrent.files, file.fileName, file.fileSize)
        }
        if (remote == null) {
            // The service only holds a zip of the whole torrent (it cannot serve single files
            // from it): give the torrent back and fetch this file directly instead.
            Log.w(TAG, "$label lists ${torrent.files.size} file(s) for ${torrent.name} but not ${file.fileName}; falling back to direct torrent download")
            releaseDebrid(file.fileName)
            updateStatus(file.fileName, DownloadStatus.DOWNLOADING)
            performTorrentDownload(file)
            return
        }

        client.selectFile(torrentId, remote.id)
        historyDao.setDebrid(file.fileName, client.provider.name, torrentId, remote.id)
        awaitFinished(torrentId)

        updateStatus(file.fileName, DownloadStatus.DOWNLOADING)
        downloadProgressTracker.updateDownloadProgress(file.fileName, 0f, 0f, 0L)
        // Links expire, so each HTTP attempt asks for a fresh one; attempts resume from the partial file.
        performHttpDownload(file, resumable = true) { client.requestDownload(torrentId, remote.id) }
        historyDao.setDebrid(file.fileName, null, null, null)
        // COMPLETED may already have stopped the foreground service, whose onDestroy cancels every
        // download job: the remote clean-up must not run inside this job.
        releaseDebrid(file.fileName)
    }

    /**
     * [resumable]: keep the partial file when an attempt stops and continue it with a `Range`
     * request next time (debrid links serve ranges; plain HTTP sources are not trusted to).
     */
    private suspend fun performHttpDownload(
        file: DownloadableFileEntity,
        resumable: Boolean = false,
        urlProvider: suspend () -> String = { file.downloadUrl }
    ) {
        repeat(3) { attempt ->
            try {
                if (attempt > 0) delay(2000L * attempt)
                performHttpDownloadAttempt(file, urlProvider(), resumable)
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Attempt ${attempt + 1} failed for ${file.fileName}: ${e.message}")
                if (attempt == 2) throw e
            }
        }
    }

    private suspend fun performHttpDownloadAttempt(file: DownloadableFileEntity, downloadUrl: String, resumable: Boolean = false) {
        val downloadDirUri = downloadFileManager.getDownloadDirectoryUri(file)
        if (downloadDirUri == android.net.Uri.EMPTY)
            throw Exception("Download directory not configured or no longer accessible.")

        var speedLimit = settingsRepository.limitSpeed.first()
        val speedLimitJob = downloadSpeedController.createSpeedLimiter { speedLimit = it }

        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        var documentFile: DocumentFile? = null

        try {
            val subPath = downloadFileManager.getSubPath(file)
            val partial = if (resumable) downloadFileManager.findExistingFile(file, downloadDirUri.toString(), subPath) else null
            val partialBytes = partial?.length() ?: 0L
            // Files served by the RomM library need the account's credentials.
            val headers = if (RommSource.isDownloadFrom(rommClient.configuredBaseUrl(), downloadUrl)) rommClient.downloadHeaders() else emptyMap()
            val connection = downloadHttpClient.createConnection(downloadUrl, rangeStart = partialBytes, headers = headers)
            inputStream = connection.inputStream

            val startOffset: Long
            if (partial != null && partialBytes > 0L && connection.responseCode == java.net.HttpURLConnection.HTTP_PARTIAL) {
                documentFile = partial
                outputStream = downloadFileManager.getAppendOutputStream(partial)
                    ?: throw Exception("Failed to open output stream for ${partial.uri}")
                startOffset = partialBytes
                Log.d(TAG, "Resuming ${file.fileName} from $partialBytes bytes")
            } else {
                documentFile = downloadFileManager.createDocumentFile(file, downloadDirUri.toString(), subPath)
                    ?: throw Exception("Failed to create file in storage.")
                outputStream = downloadFileManager.getOutputStream(documentFile)
                    ?: throw Exception("Failed to open output stream for ${documentFile.uri}")
                startOffset = 0L
            }
            val contentLength = connection.contentLengthLong.let { if (it > 0) it + startOffset else it }

            streamWithProgress(inputStream, outputStream, file, speedLimit, contentLength, startOffset)
            handlePostDownload(file, documentFile, subPath)

        } catch (e: kotlinx.coroutines.CancellationException) {
            if (!resumable) documentFile?.let { downloadFileManager.deleteFile(it) }
            updateStatus(file.fileName, DownloadStatus.STOPPED)
            throw e
        } catch (e: Exception) {
            if (!resumable) documentFile?.let { downloadFileManager.deleteFile(it) }
            updateStatus(file.fileName, DownloadStatus.FAILED)
            throw e
        } finally {
            speedLimitJob.cancel()
            inputStream?.close()
            outputStream?.close()
        }
    }

    private suspend fun streamWithProgress(
        input: InputStream,
        output: OutputStream,
        file: DownloadableFileEntity,
        initialSpeedLimit: Float,
        contentLength: Long,
        startOffset: Long = 0L
    ) {
        val buffer = ByteArray(Constants.BUFFER_SIZE)
        var downloaded = startOffset
        var bytesSinceCheck = 0L
        val startTime = System.currentTimeMillis()
        var lastUpdateTime = startTime
        var lastDownloaded = startOffset
        var lastSpeedCheckTime = startTime

        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead == -1) break
            if (downloadJobs[file.fileName]?.isCancelled == true) {
                updateStatus(file.fileName, DownloadStatus.STOPPED)
                return
            }

            output.write(buffer, 0, bytesRead)
            downloaded += bytesRead
            bytesSinceCheck += bytesRead

            val now = System.currentTimeMillis()
            val timeSinceCheck = (now - lastSpeedCheckTime) / 1000f
            if (timeSinceCheck >= Constants.SPEED_CHECK_INTERVAL_MS / 1000f) {
                val spd = downloadSpeedController.calculateSpeed(bytesSinceCheck, timeSinceCheck)
                downloadSpeedController.applySpeedThrottling(spd, initialSpeedLimit, bytesSinceCheck, timeSinceCheck)
                lastSpeedCheckTime = now
                bytesSinceCheck = 0L
            }

            val progress = if (contentLength > 0)
                ArchiveExtractionUtils.calculateProgress(downloaded, contentLength) else 0f

            if (downloadProgressTracker.shouldUpdateProgress(progress, lastUpdateTime, now)) {
                val elapsed = (now - lastUpdateTime) / 1000f
                val speedMBs = downloadSpeedController.calculateSpeed(downloaded - lastDownloaded, elapsed)
                    .takeIf { it > 0 }
                    ?: downloadSpeedController.calculateSpeed(downloaded, (now - startTime) / 1000f)
                downloadProgressTracker.updateDownloadProgress(file.fileName, progress, speedMBs, downloaded)
                lastUpdateTime = now
                lastDownloaded = downloaded
            }
        }
    }

    private suspend fun handlePostDownload(
        file: DownloadableFileEntity,
        documentFile: DocumentFile,
        subPath: String
    ) {
        if (!ArchiveUtils.isExtractable(file.fileExtension) || !settingsRepository.autoUnzip.first()) {
            updateStatus(file.fileName, DownloadStatus.COMPLETED)
            _finished.tryEmit(file.fileName)
            checkServiceLifecycle()
            return
        }

        updateStatus(file.fileName, DownloadStatus.UNZIPPING)
        try {
            val extracted = archiveExtractorService.extractArchive(
                context, documentFile.uri, downloadFileManager.getDownloadDirectoryUri(file), subPath)
            if (extracted.isNotEmpty()) {
                downloadFileManager.deleteFile(documentFile)
                extractedFilesMap[file.fileName] = extracted
            } else {
                Log.w(TAG, "Extraction produced no files for ${file.fileName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed for ${file.fileName}: ${e.message}")
        }
        updateStatus(file.fileName, DownloadStatus.COMPLETED)
        _finished.tryEmit(file.fileName)
        checkServiceLifecycle()
    }

    private suspend fun updateStatus(fileName: String, status: DownloadStatus) =
        downloadProgressTracker.updateDownloadStatus(fileName, status)

    private fun startForegroundService() {
        context.startForegroundService(Intent(context, DownloadForegroundService::class.java).apply {
            action = DownloadForegroundService.ACTION_START_SERVICE
        })
        foregroundServiceStarted = true
    }

    private fun checkServiceLifecycle() {
        if (foregroundServiceStarted && !downloadProgressTracker.hasActiveDownloads()) {
            context.startService(Intent(context, DownloadForegroundService::class.java).apply {
                action = DownloadForegroundService.ACTION_STOP_SERVICE
            })
            foregroundServiceStarted = false
        }
    }
}
