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
import com.cortinadev.dogmatix.util.DownloadFailure
import com.cortinadev.dogmatix.util.DownloadFailures
import com.cortinadev.dogmatix.util.DownloadOrigins
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.HttpResume
import com.cortinadev.dogmatix.util.UnexpectedContentException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
    private val seedingManager: SeedingManager,
    private val historyDao: DownloadHistoryDao,
    private val torBoxClient: TorBoxClient,
    private val realDebridClient: RealDebridClient,
    private val rommClient: RommClient,
    private val sourceMirrors: SourceMirrors,
    /** Who starts next: replaces the plain semaphore + per-server limiter (order is explicit). */
    private val downloadQueue: DownloadQueueService,
    private val sourceStats: SourceStatsService,
    private val resumeStore: ResumeStore
) {
    val downloads: StateFlow<List<DownloadItemModel>> = downloadProgressTracker.downloads

    /**
     * File names whose download is really finished and on disk (after copy / extraction). The
     * torrent bridge flips a row to COMPLETED as soon as libtorrent is done, before the file is
     * moved into place, so status watchers cannot tell "done" from "about to be copied".
     */
    private val _finished = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val finished: SharedFlow<String> = _finished.asSharedFlow()

    /** File names whose job ended FAILED (the job is over, unlike a failed attempt that is retried in place). */
    private val _failed = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val failed: SharedFlow<String> = _failed.asSharedFlow()

    /** Rows still running when the process died, resumed by [resumeInterrupted] once restored. */
    private var interrupted: List<String> = emptyList()
    private val historyRestored = CompletableDeferred<Unit>()
    private val resumeRequested = AtomicBoolean(false)

    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private var foregroundServiceStarted = false
    private val downloadEntities = ConcurrentHashMap<String, DownloadableFileEntity>()
    private val extractedFilesMap = ConcurrentHashMap<String, List<String>>()
    /** Debrid client + torrent id per file being fetched through the debrid route (see [performDebridDownload]). */
    private val debridTorrents = ConcurrentHashMap<String, Pair<DebridClient, String>>()
    /** Files whose job is being cancelled by a pause (they land on PAUSED instead of STOPPED). */
    private val pausingFiles = ConcurrentHashMap.newKeySet<String>()

    // Single supervised scope for all internal coroutines — tied to this singleton's lifetime
    // so jobs are not orphaned if the service is destroyed.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        serviceScope.launch { restoreHistory() }
        // Seeding keeps the foreground service alive after the last download; stop it when the
        // last seed is released (goal met, or the setting switched off).
        serviceScope.launch {
            seedingManager.active.collect { count -> if (count == 0) checkServiceLifecycle() }
        }
    }

    /**
     * Brings back the Downloads list from the previous run. Anything that was still in
     * flight when the process died comes back as STOPPED so the user can retry it.
     */
    private suspend fun restoreHistory() {
        try {
            val rows = historyDao.getAll()
            downloadQueue.restore(rows)
            rows.forEach { row -> downloadEntities.putIfAbsent(row.fileName, row.toEntity()) }
            val items = rows.map { it.toItem() }
            downloadProgressTracker.restore(items)
            interrupted = rows.filter { it.wasInFlight }.map { it.fileName }
            downloadProgressTracker.restoreFailures(
                rows.mapNotNull { row -> DownloadFailures.fromName(row.failureReason)?.let { row.fileName to it } }.toMap()
            )
            items.filter { it.status == DownloadStatus.STOPPED }.forEach { item ->
                val row = rows.first { it.fileName == item.fileName }
                if (row.status != item.status.name || row.finishedAt != item.finishedAt) {
                    historyDao.updateStatus(item.fileName, item.status.name, item.finishedAt)
                }
            }
            Log.d(TAG, "Restored ${rows.size} download(s) from history")
        } catch (e: Exception) {
            Log.e(TAG, "Could not restore download history: ${e.message}")
        } finally {
            historyRestored.complete(Unit)
        }
    }

    /**
     * Starts again what was downloading when the process died (the queue survives a restart).
     * Called by the activity, not at process start: a download needs the foreground service,
     * which Android only lets a visible app start. Runs once per process.
     */
    fun resumeInterrupted() {
        if (!resumeRequested.compareAndSet(false, true)) return
        serviceScope.launch {
            historyRestored.await()
            // Back in their places (and holds) in line, starting by queue order, not list order.
            downloadQueue.batch { interrupted.forEach { relaunch(it, keepPlace = true) } }
            interrupted = emptyList()
        }
    }

    /** Marks [file] FAILED with the reason worked out from [error] (recorded first, see [DownloadProgressTracker.recordFailure]). */
    private fun markFailed(fileName: String, error: Throwable) {
        downloadProgressTracker.recordFailure(fileName, DownloadFailures.classify(error))
        downloadProgressTracker.updateDownloadStatus(fileName, DownloadStatus.FAILED)
    }

    /** Lets auto-retry know a job ended FAILED (a torrent may fail without throwing). */
    private fun reportIfFailed(fileName: String) {
        if (downloadProgressTracker.statusOf(fileName) == DownloadStatus.FAILED) _failed.tryEmit(fileName)
    }

    fun startDownload(file: DownloadableFileEntity) {
        // Ignore repeated taps: an in-flight download for this file must not be launched twice.
        if (downloadProgressTracker.isActive(file.fileName)) return
        val item = downloadFileManager.createDownloadItem(file)
        downloadProgressTracker.addDownload(item)
        downloadEntities[file.fileName] = file
        // Its place in line is taken now, so downloads started together start in that order.
        val ticket = downloadQueue.enqueue(file)
        serviceScope.launch { historyDao.upsert(DownloadHistoryEntity.from(file, item).copy(queueOrder = ticket.order)) }
        startForegroundService()

        val job = serviceScope.launch {
            try {
                downloadQueue.withSlot(ticket) {
                    // Brief delay to allow the foreground service and initial UI state to settle
                    // before network/torrent activity begins.
                    delay(1000L)
                    perform(file)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                updateStatus(file.fileName, if (pausingFiles.remove(file.fileName)) DownloadStatus.PAUSED else DownloadStatus.STOPPED)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${file.fileName}: ${e.message}")
                markFailed(file.fileName, e)
            } finally {
                downloadJobs.remove(file.fileName)
                reportIfFailed(file.fileName)
                // A stopped or failed last download must take the notification away too.
                checkServiceLifecycle()
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
            // Plain HTTP / RomM keep their partial and continue with a Range request on retry;
            // a web source's reserve addresses take over when its own one fails.
            else -> performHttpDownload(file, resumable = true, mirrorUrls = sourceMirrors.alternativesFor(file))
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

    /**
     * Parks a download. A torrent keeps its cache and handle, so retry resumes from disk; a web
     * or debrid transfer keeps its partial file, and retry continues it with a `Range` request.
     */
    fun pauseDownload(fileName: String) {
        val entity = downloadEntities[fileName] ?: return
        if (!downloadProgressTracker.isActive(fileName)) return
        val torrentSession = entity.isTorrent && !debridTorrents.containsKey(fileName)
        pausingFiles.add(fileName)
        val job = downloadJobs.remove(fileName)
        job?.cancel()
        serviceScope.launch {
            if (torrentSession) {
                torrentDownloadService.pauseDownload(entity)
                updateStatus(fileName, DownloadStatus.PAUSED)
            } else {
                // The job's own cancellation handler lands it on PAUSED.
                job?.join()
            }
            checkServiceLifecycle()
        }
    }

    /** A paused download resumes in its place in line; anything else goes to the back. */
    fun retryDownload(fileName: String) =
        relaunch(fileName, keepPlace = downloadProgressTracker.statusOf(fileName) == DownloadStatus.PAUSED)

    private fun relaunch(fileName: String, keepPlace: Boolean) {
        if (!downloadProgressTracker.canRetryDownload(fileName)) return
        val entity = downloadEntities[fileName] ?: return
        // Reset the existing list entry in place — calling startDownload would add a duplicate.
        downloadProgressTracker.resetDownloadForRetry(fileName)
        val ticket = downloadQueue.enqueue(entity, keepPlace)
        serviceScope.launch {
            historyDao.markRestarted(fileName, DownloadStatus.DOWNLOADING.name, System.currentTimeMillis())
        }
        // A row held before a restart waits without the service; letting it go starts it.
        if (!ticket.held) startForegroundService()
        val job = serviceScope.launch {
            try {
                downloadQueue.withSlot(ticket) {
                    delay(1000L)
                    perform(entity)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                updateStatus(entity.fileName, if (pausingFiles.remove(entity.fileName)) DownloadStatus.PAUSED else DownloadStatus.STOPPED)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Retry failed for ${entity.fileName}: ${e.message}")
                markFailed(entity.fileName, e)
            } finally {
                downloadJobs.remove(entity.fileName)
                reportIfFailed(entity.fileName)
                checkServiceLifecycle()
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
        // Held rows wait without the foreground service (it stops when only they are left) and
        // must keep their place for when they are let go.
        downloadJobs.entries.removeAll { (fileName, job) ->
            (!downloadQueue.isHeld(fileName)).also { if (it) job.cancel() }
        }
    }

    // dl-cola
    /** Holds a row waiting in line: it keeps its place but does not start until [releaseDownload]. */
    fun holdDownload(fileName: String) {
        if (!downloadQueue.isWaiting(fileName)) return
        downloadQueue.setHeld(fileName, true)
        checkServiceLifecycle()
    }

    fun releaseDownload(fileName: String) {
        if (!downloadQueue.isHeld(fileName)) return
        // Its turn may come right away: the transfer needs the foreground service up first.
        startForegroundService()
        downloadQueue.setHeld(fileName, false)
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
        val startedAt = System.currentTimeMillis()
        torrentDownloadService.startDownload(file)

        // Collect just this file's status as a distinct flow instead of polling the full
        // downloads list on every tick — O(1) vs O(n) and no busy-wait sleep.
        val finalStatus = downloadProgressTracker.downloads
            .map { list -> list.find { it.fileName == file.fileName }?.status }
            .distinctUntilChanged()
            .first { it == DownloadStatus.COMPLETED || it == DownloadStatus.FAILED || it == DownloadStatus.STOPPED }

        when (finalStatus) {
            DownloadStatus.FAILED  -> Log.e(TAG, "Torrent FAILED: ${file.fileName}")
            DownloadStatus.STOPPED -> { Log.i(TAG, "Torrent STOPPED: ${file.fileName}"); return }
            else -> moveTorrentFile(file)
        }
        recordTorrentOutcome(file, startedAt)
    }

    /** A torrent's outcome for its track record (HTTP attempts report themselves, see [performHttpDownload]). */
    private fun recordTorrentOutcome(file: DownloadableFileEntity, startedAt: Long) {
        val origin = file.torrentMagnet?.let(DownloadOrigins::forTorrent) ?: return
        val elapsed = System.currentTimeMillis() - startedAt
        when (downloadProgressTracker.statusOf(file.fileName)) {
            DownloadStatus.COMPLETED -> sourceStats.recordSuccess(origin, file.fileSize, elapsed)
            DownloadStatus.FAILED -> sourceStats.recordFailure(
                origin, downloadProgressTracker.failures.value[file.fileName] ?: DownloadFailure.UNKNOWN, 0L, elapsed
            )
            else -> Unit
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
                // Copying a truncated file to the ROMs folder would mark a broken download as
                // completed (seen with ENOSPC on the cache partition: 0 bytes were "flushed").
                throw Exception(
                    "Incomplete torrent data for ${file.fileName}: " +
                    "${internalFile.length()}/$expectedSize bytes (disk full or write error)"
                )
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

            // Release handle only when no sibling files still downloading from same torrent;
            // with "Seed after download" on it stays seeding instead and the cached copy must
            // survive (libtorrent serves pieces from it) — releaseHandle deletes it at seed end.
            val seeding = torrentDownloadService.finishDownload(file)
            if (!seeding) {
                internalFile.delete()
                internalFile.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
            }

            Log.i(TAG, "Torrent processed successfully: ${file.fileName}")
            updateStatus(file.fileName, DownloadStatus.COMPLETED)
            _finished.tryEmit(file.fileName)
            checkServiceLifecycle()

        } catch (e: Exception) {
            Log.e(TAG, "Error processing torrent file for ${file.fileName}: ${e.message}", e)
            markFailed(file.fileName, e)
            // Untrack and, if nothing else uses the torrent, release it (deletes the cached data);
            // a retry re-fetches the metadata and starts clean instead of leaving a partial behind.
            // Never seed a failed move: the release is what lets the retry start clean.
            runCatching { torrentDownloadService.finishDownload(file, allowSeeding = false) }
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
            // Still QUEUED: the service itself failed before the transfer (its attempts report themselves).
            if (e !is kotlinx.coroutines.CancellationException && downloadProgressTracker.statusOf(file.fileName) == DownloadStatus.QUEUED) {
                sourceStats.recordFailure(DownloadOrigins.forDebrid(client.provider.name), DownloadFailures.classify(e), 0L, 0L)
            }
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
            performHttpDownload(file, resumable = true, origin = DownloadOrigins.forDebrid(client.provider.name)) {
                client.requestDownload(torrent.id, remote.id)
            }
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
        performHttpDownload(file, resumable = true, origin = DownloadOrigins.forDebrid(client.provider.name)) {
            client.requestDownload(torrentId, remote.id)
        }
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
        /** Reserve addresses of the source: attempts go round the file's own URL and these. */
        mirrorUrls: List<String> = emptyList(),
        /** What the attempts are recorded under when it is not the URL's host (the debrid service). */
        origin: String? = null,
        urlProvider: suspend () -> String = { file.downloadUrl }
    ) {
        // Best first by how each host has been doing lately; without history, the file's own URL first.
        val urls = sourceStats.orderBy(listOf(file.downloadUrl) + mirrorUrls, DownloadOrigins::forWeb)
        val attempts = maxOf(3, urls.size)
        // Addresses that gave an answer another try cannot change (a missing file, a web page instead
        // of the file): once every address has, the rest of the attempts are skipped. Debrid links are
        // new on every attempt, so they always get them all.
        val settled = HashSet<String>()
        repeat(attempts) { attempt ->
            val meter = TransferMeter()
            var attemptOrigin = origin
            var url: String? = null
            try {
                if (attempt > 0) {
                    delay(2000L * minOf(attempt, 3))
                    // A failed attempt left the row on FAILED; this one is running again.
                    updateStatus(file.fileName, DownloadStatus.DOWNLOADING)
                }
                url = if (mirrorUrls.isEmpty()) urlProvider() else urls[attempt % urls.size]
                if (attemptOrigin == null) attemptOrigin = DownloadOrigins.forWeb(url)
                performHttpDownloadAttempt(file, url, resumable, meter, debridOrigin = origin)
                sourceStats.recordSuccess(attemptOrigin, meter.bytes, meter.millis)
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Attempt ${attempt + 1} failed for ${file.fileName}: ${e.message}")
                val failure = DownloadFailures.classify(e)
                sourceStats.recordFailure(attemptOrigin, failure, meter.bytes, meter.millis)
                if (attempt == attempts - 1) throw e
                if (origin == null && !failure.transient && url != null) {
                    settled += url
                    if (urls.all { it in settled }) throw e
                }
            }
        }
    }

    private suspend fun performHttpDownloadAttempt(
        file: DownloadableFileEntity,
        downloadUrl: String,
        resumable: Boolean = false,
        meter: TransferMeter = TransferMeter(),
        /** Set for a debrid link: it changes on every attempt, so no validator can be sent back to it. */
        debridOrigin: String? = null
    ) {
        val downloadDirUri = downloadFileManager.getDownloadDirectoryUri(file)
        if (downloadDirUri == android.net.Uri.EMPTY)
            throw Exception("Download directory not configured or no longer accessible.")

        var inputStream: InputStream? = null
        var outputStream: OutputStream? = null
        var documentFile: DocumentFile? = null

        try {
            val subPath = downloadFileManager.getSubPath(file)
            // Only a transfer this app started and has not finished is continued: a file that was
            // finished and is fetched again, or one already in the folder, starts from zero.
            val resume = if (resumable) resumeStore.stateFor(file.fileName) else null
            val partial = resume?.let { downloadFileManager.findExistingFile(file, downloadDirUri.toString(), subPath) }
            val partialBytes = partial?.length() ?: 0L
            val origin = debridOrigin ?: DownloadOrigins.forWeb(downloadUrl)
            // If-Range only to the server that gave the validator. A debrid file is fixed by the
            // torrent's hashes, so its length is check enough when the link changes.
            val ifRange = resume?.validator?.takeIf { debridOrigin == null && resume.host == origin }
            val expectedTotal = resume?.totalBytes?.takeIf { it > 0 }
            val name = FileParsingUtils.decodeUrlEncodedFileName(file.fileName)
            // Files served by the RomM library need the account's credentials.
            val headers = if (RommSource.isDownloadFrom(rommClient.configuredBaseUrl(), downloadUrl)) rommClient.downloadHeaders() else emptyMap()
            var connection: HttpURLConnection = downloadHttpClient.createConnection(downloadUrl, rangeStart = partialBytes, headers = headers, ifRange = ifRange, configure = rommClient::secure)
            // Decided before the partial is touched.
            var plan = HttpResume.plan(partialBytes, expectedTotal, connection.resumeResponse(), name)
            if (plan == HttpResume.Plan.Refetch) {
                Log.d(TAG, "Cannot continue ${file.fileName} from $partialBytes bytes; fetching it whole")
                connection.disconnect()
                connection = downloadHttpClient.createConnection(downloadUrl, headers = headers, configure = rommClient::secure)
                plan = HttpResume.plan(0L, null, connection.resumeResponse(), name)
            }

            val target: DocumentFile
            val startOffset: Long
            val total: Long?
            when (plan) {
                is HttpResume.Plan.Append -> {
                    target = partial ?: throw UnexpectedContentException("Lost the partial file of ${file.fileName}")
                    outputStream = downloadFileManager.getAppendOutputStream(target)
                        ?: throw Exception("Failed to open output stream for ${target.uri}")
                    startOffset = plan.offset
                    total = plan.total
                    Log.d(TAG, "Resuming ${file.fileName} from ${plan.offset} of ${plan.total} bytes")
                }
                is HttpResume.Plan.Fresh -> {
                    target = downloadFileManager.createDocumentFile(file, downloadDirUri.toString(), subPath)
                        ?: throw Exception("Failed to create file in storage.")
                    outputStream = downloadFileManager.getOutputStream(target)
                        ?: throw Exception("Failed to open output stream for ${target.uri}")
                    startOffset = 0L
                    total = plan.total
                    if (resumable) {
                        val validator = HttpResume.validator(connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"))
                        resumeStore.started(file.fileName, validator, origin, plan.total)
                    }
                }
                HttpResume.Plan.Complete -> {
                    connection.disconnect()
                    Log.d(TAG, "${file.fileName} was already complete on disk")
                    handlePostDownload(file, partial ?: throw UnexpectedContentException("Lost the partial file of ${file.fileName}"), subPath)
                    return
                }
                is HttpResume.Plan.Reject -> {
                    connection.disconnect()
                    throw UnexpectedContentException("Unexpected response for ${file.fileName}: ${plan.reason}")
                }
                HttpResume.Plan.Refetch -> {
                    connection.disconnect()
                    throw UnexpectedContentException("Unexpected response for ${file.fileName}: the server did not send the file")
                }
            }
            documentFile = target
            inputStream = connection.inputStream

            streamWithProgress(inputStream, outputStream, file, total ?: -1L, startOffset, meter)
            handlePostDownload(file, target, subPath)

        } catch (e: kotlinx.coroutines.CancellationException) {
            if (!resumable) documentFile?.let { downloadFileManager.deleteFile(it) }
            updateStatus(file.fileName, DownloadStatus.STOPPED)
            throw e
        } catch (e: Exception) {
            if (!resumable) documentFile?.let { downloadFileManager.deleteFile(it) }
            updateStatus(file.fileName, DownloadStatus.FAILED)
            throw e
        } finally {
            inputStream?.close()
            outputStream?.close()
        }
    }

    private suspend fun streamWithProgress(
        input: InputStream,
        output: OutputStream,
        file: DownloadableFileEntity,
        contentLength: Long,
        startOffset: Long = 0L,
        meter: TransferMeter? = null
    ) {
        val buffer = ByteArray(Constants.BUFFER_SIZE)
        var downloaded = startOffset
        val startTime = System.currentTimeMillis()
        var lastUpdateTime = startTime
        var lastDownloaded = startOffset

        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead == -1) break
            if (downloadJobs[file.fileName]?.isCancelled == true) {
                updateStatus(file.fileName, DownloadStatus.STOPPED)
                return
            }
            // Stop and pause remove the job from downloadJobs before cancelling it, so the check
            // above never fires: without a cancellation point the transfer would run to the end.
            currentCoroutineContext().ensureActive()

            output.write(buffer, 0, bytesRead)
            downloaded += bytesRead
            // Shared with every other HTTP download, and re-read live from Settings.
            downloadSpeedController.throttle(bytesRead)

            val now = System.currentTimeMillis()
            meter?.let { it.bytes += bytesRead; it.millis = now - startTime }
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
        // A connection that drops early reads as the end of the stream: never call that complete.
        if (contentLength > 0 && downloaded < contentLength) {
            throw EOFException("Incomplete download of ${file.fileName}: $downloaded of $contentLength bytes")
        }
    }

    private suspend fun handlePostDownload(
        file: DownloadableFileEntity,
        documentFile: DocumentFile,
        subPath: String
    ) {
        if (!ArchiveUtils.isExtractable(file.fileExtension) || !settingsRepository.autoUnzip.first()) {
            resumeStore.finished(file.fileName)
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
        resumeStore.finished(file.fileName)
        updateStatus(file.fileName, DownloadStatus.COMPLETED)
        _finished.tryEmit(file.fileName)
        checkServiceLifecycle()
    }

    private suspend fun updateStatus(fileName: String, status: DownloadStatus) =
        downloadProgressTracker.updateDownloadStatus(fileName, status)

    private fun startForegroundService() {
        // Android 12+ refuses a foreground service started from the background (an automatic
        // retry while the app is closed): the download still runs as long as the process lives.
        runCatching {
            context.startForegroundService(Intent(context, DownloadForegroundService::class.java).apply {
                action = DownloadForegroundService.ACTION_START_SERVICE
            })
        }.onSuccess { foregroundServiceStarted = true }
            .onFailure { Log.w(TAG, "Foreground service not started: ${it.message}") }
    }

    private fun checkServiceLifecycle() {
        if (foregroundServiceStarted && !downloadProgressTracker.hasActiveDownloads(ignoring = downloadQueue.heldNames()) && !seedingManager.isSeeding) {
            context.startService(Intent(context, DownloadForegroundService::class.java).apply {
                action = DownloadForegroundService.ACTION_STOP_SERVICE
            })
            foregroundServiceStarted = false
        }
    }
}
