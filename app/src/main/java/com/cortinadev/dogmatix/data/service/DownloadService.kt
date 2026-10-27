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
import com.cortinadev.dogmatix.util.ArchiveExtractionException
import com.cortinadev.dogmatix.util.ArchiveExtractionUtils
import com.cortinadev.dogmatix.util.Constants
import com.cortinadev.dogmatix.util.RommSource
import com.cortinadev.dogmatix.util.DebridMatcher
import com.cortinadev.dogmatix.util.DownloadFailure
import com.cortinadev.dogmatix.util.DownloadFailures
import com.cortinadev.dogmatix.util.DownloadOrigins
import com.cortinadev.dogmatix.util.DownloadRestore
import com.cortinadev.dogmatix.util.DownloadRuns
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.HttpResume
import com.cortinadev.dogmatix.util.UnexpectedContentException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withContext
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
    private val resumeStore: ResumeStore,
    private val iaAuth: IaAuth
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
    /** Automatic retries that were pending when the process died, handed over once the queue is resumed. */
    private var savedRetries: List<DownloadRestore.SavedRetry> = emptyList()
    private val resumed = CompletableDeferred<List<DownloadRestore.SavedRetry>>()

    private val downloadJobs = ConcurrentHashMap<String, Job>()
    @Volatile private var foregroundServiceStarted = false
    private val downloadEntities = ConcurrentHashMap<String, DownloadableFileEntity>()
    private val extractedFilesMap = ConcurrentHashMap<String, List<String>>()
    /** Debrid client + torrent id per file being fetched through the debrid route (see [performDebridDownload]). */
    private val debridTorrents = ConcurrentHashMap<String, Pair<DebridClient, String>>()

    /**
     * One run per file at a time (see [DownloadRuns]): every start, pause, stop, delete and the
     * end of each run go through this lock, so a file is never fetched by two jobs and a late
     * clean-up never lands on a newer download of it.
     */
    private val lifecycle = Any()
    private val runs = DownloadRuns<PendingStart>()
    /** Rows deleted while their run winds down: what the clean-up removes (under [lifecycle]). */
    private val deletions = HashMap<String, Deletion>()
    /** Files fetched through a libtorrent session of this process (a paused one included): a stop or delete lets it go. */
    private val torrentSessions = ConcurrentHashMap.newKeySet<String>()
    /** Automatic retries spent per file in its current chain (see [DownloadAutoRetry]); saved with the row. */
    private val retryChains = ConcurrentHashMap<String, Int>()

    /** Who started a run: the user, an automatic retry ([attempt] into its chain), or a restart. */
    private sealed interface RunKind {
        data object Manual : RunKind
        data class Automatic(val attempt: Int) : RunKind
        data object Resumed : RunKind
    }

    /** A start that came while the file's previous run was winding down: it goes once that run is over. */
    private sealed interface PendingStart {
        data class Fresh(val file: DownloadableFileEntity) : PendingStart
        data class Relaunch(val keepPlace: Boolean, val kind: RunKind) : PendingStart
    }

    private data class Deletion(
        val entity: DownloadableFileEntity,
        val deleteFile: Boolean,
        val extracted: List<String>,
        val completed: Boolean
    )

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
            rows.forEach { row -> if (row.retryCount > 0) retryChains[row.fileName] = row.retryCount }
            savedRetries = rows.mapNotNull { row ->
                val at = row.retryAt?.takeIf { row.status == DownloadStatus.FAILED.name } ?: return@mapNotNull null
                DownloadRestore.SavedRetry(row.fileName, at, row.retryCount, DownloadFailures.fromName(row.failureReason))
            }
            // A paused web download that will start again from zero says so (see performHttpDownloadAttempt).
            items.filter { it.status == DownloadStatus.PAUSED }.forEach { item ->
                val row = rows.first { it.fileName == item.fileName }
                val hasRecord = resumeStore.stateFor(item.fileName) != null
                if (DownloadRestore.restartsFromZero(item.status, isTorrent = row.torrentFileIndex != null, hasResumeRecord = hasRecord)) {
                    downloadProgressTracker.setRestartsFromZero(item.fileName, true)
                }
            }
            items.filter { it.status == DownloadStatus.STOPPED }.forEach { item ->
                val row = rows.first { it.fileName == item.fileName }
                if (row.status != item.status.name || row.finishedAt != item.finishedAt) {
                    downloadProgressTracker.persist { updateStatus(item.fileName, item.status.name, item.finishedAt) }
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
            downloadQueue.batch {
                synchronized(lifecycle) { interrupted.forEach { relaunchLocked(it, keepPlace = true, RunKind.Resumed) } }
            }
            interrupted = emptyList()
            resumed.complete(savedRetries)
        }
    }

    /**
     * Waits until the app has resumed the queue ([resumeInterrupted]) and returns the automatic
     * retries that were pending when the process died (see [DownloadAutoRetry]).
     */
    suspend fun awaitResume(): List<DownloadRestore.SavedRetry> = resumed.await()

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
        synchronized(lifecycle) { startLocked(file) }
    }

    /**
     * Repeated taps are ignored (an in-flight download is never launched twice); a start while
     * the previous run winds down (stopped or deleted a moment ago) goes once that run is over.
     */
    private fun startLocked(file: DownloadableFileEntity) {
        val start = runs.start(file.fileName, PendingStart.Fresh(file))
        if (start !is DownloadRuns.Start.Go) return
        val item = downloadFileManager.createDownloadItem(file)
        downloadProgressTracker.addDownload(item)
        downloadEntities[file.fileName] = file
        // Started by hand: a new chain of automatic retries (the row is written anew below).
        retryChains.remove(file.fileName)
        // Its place in line is taken now, so downloads started together start in that order.
        val ticket = downloadQueue.enqueue(file)
        downloadProgressTracker.persist { upsert(DownloadHistoryEntity.from(file, item).copy(queueOrder = ticket.order)) }
        startForegroundService()
        launchRun(file, start.run, ticket)
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

    /**
     * Stops a download for good: its job is cancelled and its torrent session (or the debrid
     * service's torrent) let go; a web download keeps its partial file for a later retry. A file
     * already complete and being put in place (copied, checked) is too late to stop.
     */
    fun cancelDownload(fileName: String) {
        synchronized(lifecycle) {
            val status = downloadProgressTracker.statusOf(fileName) ?: return
            if (status == DownloadStatus.COPYING || status == DownloadStatus.COMPLETED) return
            // A paused row has no job any more: what it keeps (a torrent session) is let go here.
            when (val stop = runs.stop(fileName, DownloadRuns.Stop.STOP, cleanUpWhenIdle = status == DownloadStatus.PAUSED)) {
                is DownloadRuns.StopResult.Cancel -> downloadJobs[fileName]?.cancel()
                is DownloadRuns.StopResult.CleanUp -> launchCleanUp(fileName, stop.run)
                else -> Unit
            }
        }
    }

    /**
     * Parks a download. A torrent keeps its cache and handle, so retry resumes from disk; a web
     * or debrid transfer keeps its partial file, and retry continues it with a `Range` request.
     * Only a transfer pauses (or a row waiting for its turn, or for the debrid service): a file
     * being copied or extracted into place finishes first.
     */
    fun pauseDownload(fileName: String) {
        synchronized(lifecycle) {
            val status = downloadProgressTracker.statusOf(fileName)
            if (status != DownloadStatus.DOWNLOADING && status != DownloadStatus.QUEUED) return
            // Nothing running (it ended a moment ago): nothing to pause, and nothing left behind.
            val stop = runs.stop(fileName, DownloadRuns.Stop.PAUSE, cleanUpWhenIdle = false)
            if (stop is DownloadRuns.StopResult.Cancel) downloadJobs[fileName]?.cancel()
        }
    }

    /** A paused download resumes in its place in line; anything else goes to the back. Started by hand. */
    fun retryDownload(fileName: String) {
        synchronized(lifecycle) {
            relaunchLocked(fileName, keepPlace = downloadProgressTracker.statusOf(fileName) == DownloadStatus.PAUSED, RunKind.Manual)
        }
    }

    /**
     * An automatic retry, [attempt] into its chain (see [DownloadAutoRetry]). From the background
     * Android (12+) refuses the foreground service a transfer needs: then nothing starts and false
     * comes back, so the retry stays due until the app is open again.
     */
    fun retryAutomatically(fileName: String, attempt: Int): Boolean {
        synchronized(lifecycle) {
            // Retried by hand, paused, stopped or deleted meanwhile (even a moment ago): nothing left to do.
            if (downloadProgressTracker.statusOf(fileName) != DownloadStatus.FAILED || runs.isRunning(fileName)) return true
            if (!startForegroundService()) return false
            relaunchLocked(fileName, keepPlace = false, RunKind.Automatic(attempt))
        }
        return true
    }

    /** Automatic retries spent in [fileName]'s current chain (0 once the user started it). */
    fun retriesSpent(fileName: String): Int = retryChains[fileName] ?: 0

    /** Saves when [fileName]'s next automatic retry is due (null = none), so a restart keeps it. */
    fun saveRetryAt(fileName: String, at: Long?) {
        downloadProgressTracker.persist { setRetryAt(fileName, at) }
    }

    private fun relaunchLocked(fileName: String, keepPlace: Boolean, kind: RunKind) {
        if (!downloadProgressTracker.canRetryDownload(fileName)) return
        val entity = downloadEntities[fileName] ?: return
        val start = runs.start(fileName, PendingStart.Relaunch(keepPlace, kind))
        if (start !is DownloadRuns.Start.Go) return
        // Reset the existing list entry in place — calling startDownload would add a duplicate.
        downloadProgressTracker.resetDownloadForRetry(fileName)
        val chain = when (kind) {
            RunKind.Manual -> 0
            is RunKind.Automatic -> kind.attempt
            RunKind.Resumed -> retryChains[fileName] ?: 0
        }
        if (chain > 0) retryChains[fileName] = chain else retryChains.remove(fileName)
        val ticket = downloadQueue.enqueue(entity, keepPlace)
        val startedAt = System.currentTimeMillis()
        downloadProgressTracker.persist { markRestarted(fileName, DownloadStatus.DOWNLOADING.name, startedAt, chain) }
        // A row held before a restart waits without the service; letting it go starts it.
        if (!ticket.held) startForegroundService()
        launchRun(entity, start.run, ticket)
    }

    /**
     * Takes the row off the list. Its job is cancelled first; the clean-up (torrent session, the
     * partial file and its resume record, the history row) runs once the job is over, and with
     * [deleteFile] the finished file goes to the trash. A partial never stays behind.
     */
    fun deleteDownload(fileName: String, deleteFile: Boolean = false) {
        synchronized(lifecycle) {
            val status = downloadProgressTracker.statusOf(fileName)
            val entity = downloadEntities.remove(fileName)
            val extracted = extractedFilesMap.remove(fileName) ?: emptyList()
            if (entity != null) deletions[fileName] = Deletion(entity, deleteFile, extracted, completed = status == DownloadStatus.COMPLETED)
            downloadProgressTracker.removeDownload(fileName)
            when (val stop = runs.stop(fileName, DownloadRuns.Stop.DELETE, cleanUpWhenIdle = true)) {
                is DownloadRuns.StopResult.Cancel -> downloadJobs[fileName]?.cancel()
                is DownloadRuns.StopResult.CleanUp -> launchCleanUp(fileName, stop.run)
                else -> Unit
            }
        }
    }

    fun cancelAllDownloads() {
        // Held rows wait without the foreground service (it stops when only they are left) and
        // must keep their place for when they are let go.
        synchronized(lifecycle) {
            downloadJobs.forEach { (fileName, job) -> if (!downloadQueue.isHeld(fileName)) job.cancel() }
        }
    }

    /** The job of one run: its turn in line, the transfer, then [settle], whatever ended it. Under [lifecycle]. */
    private fun launchRun(entity: DownloadableFileEntity, run: Long, ticket: QueueTicket) {
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            var error: Exception? = null
            try {
                downloadQueue.withSlot(ticket) {
                    // Brief delay to allow the foreground service and initial UI state to settle
                    // before network/torrent activity begins.
                    delay(1000L)
                    perform(entity)
                }
            } catch (e: Exception) {
                // Cancellation included: a pause, stop or delete, or the service going away.
                error = e
            } finally {
                withContext(NonCancellable) { settle(entity.fileName, entity, run, error) }
            }
        }
        // Registered before it starts, so a pause or stop always finds it.
        downloadJobs[entity.fileName] = job
        job.start()
    }

    /** A run with no job: the clean-up a stop or delete asked for on a row that was not running. Under [lifecycle]. */
    private fun launchCleanUp(fileName: String, run: Long) {
        val entity = downloadEntities[fileName] ?: deletions[fileName]?.entity
        serviceScope.launch { withContext(NonCancellable) { settle(fileName, entity, run, null) } }
    }

    /**
     * The end of a run: the wind-down a pause, stop or delete asked for (again while a stronger
     * one arrives), then the final status, set under [lifecycle] in the same step that ends the
     * run, so nothing can start in between. A start that waited for the run goes then.
     */
    private suspend fun settle(fileName: String, entity: DownloadableFileEntity?, run: Long, error: Exception?) {
        var handled: DownloadRuns.Stop? = null
        while (true) {
            val end = synchronized(lifecycle) {
                runs.end(fileName, run, handled).also { if (it is DownloadRuns.End.Done) finishRunLocked(fileName, it, error) }
            }
            when (end) {
                is DownloadRuns.End.Again -> {
                    windDown(fileName, entity, end.stop)
                    handled = end.stop
                }
                is DownloadRuns.End.Done -> {
                    reportIfFailed(fileName)
                    break
                }
                DownloadRuns.End.Stale -> break
            }
        }
        // A stopped or failed last download must take the notification away too.
        checkServiceLifecycle()
    }

    private fun finishRunLocked(fileName: String, done: DownloadRuns.End.Done<PendingStart>, error: Exception?) {
        downloadJobs.remove(fileName)
        val status = downloadProgressTracker.statusOf(fileName)
        // A file already complete stays complete, whatever arrived after it.
        val completed = status == DownloadStatus.COMPLETED
        when (done.stop) {
            null -> when {
                completed -> Unit
                error is kotlinx.coroutines.CancellationException -> updateStatusNow(fileName, DownloadStatus.STOPPED)
                error != null -> {
                    Log.e(TAG, "Download failed for $fileName: ${error.message}")
                    markFailed(fileName, error)
                }
                // Ended without an outcome (never expected): it must not look like it is still running.
                status != null && status != DownloadStatus.FAILED && status != DownloadStatus.STOPPED ->
                    updateStatusNow(fileName, DownloadStatus.STOPPED)
            }
            DownloadRuns.Stop.PAUSE -> if (!completed) updateStatusNow(fileName, DownloadStatus.PAUSED)
            DownloadRuns.Stop.STOP -> if (!completed) updateStatusNow(fileName, DownloadStatus.STOPPED)
            DownloadRuns.Stop.DELETE -> Unit
        }
        val after = downloadProgressTracker.statusOf(fileName)
        if (after == DownloadStatus.COMPLETED) torrentSessions.remove(fileName)
        // Completed, stopped, paused or deleted: the chain of automatic retries is over.
        if (after != DownloadStatus.FAILED && retryChains.remove(fileName) != null && done.stop != DownloadRuns.Stop.DELETE) {
            downloadProgressTracker.persist { clearRetries(fileName) }
        }
        when (val next = done.next) {
            is PendingStart.Fresh -> startLocked(next.file)
            is PendingStart.Relaunch -> if (done.stop != DownloadRuns.Stop.DELETE) {
                relaunchLocked(fileName, next.keepPlace || after == DownloadStatus.PAUSED, next.kind)
            }
            null -> Unit
        }
    }

    /** What a pause, stop or delete leaves to do once the job is over (or when there was none). Never cancelled. */
    private suspend fun windDown(fileName: String, entity: DownloadableFileEntity?, stop: DownloadRuns.Stop) {
        // The torrent the debrid service holds goes back (the job may not have got that far).
        if (stop != DownloadRuns.Stop.PAUSE) debridTorrents.remove(fileName)?.let { (client, id) -> runCatching { client.delete(id) } }
        when (stop) {
            DownloadRuns.Stop.PAUSE ->
                if (entity != null && fileName in torrentSessions) runCatching { torrentDownloadService.pauseDownload(entity) }
            DownloadRuns.Stop.STOP -> {
                if (entity != null && torrentSessions.remove(fileName)) runCatching { torrentDownloadService.cancelDownload(entity) }
                // A later retry must not pick the given-back debrid torrent up again.
                if (entity?.isTorrent == true) downloadProgressTracker.persist { setDebrid(fileName, null, null, null) }
            }
            DownloadRuns.Stop.DELETE -> {
                if (entity != null && torrentSessions.remove(fileName)) runCatching { torrentDownloadService.cancelDownload(entity) }
                val deletion = synchronized(lifecycle) { deletions.remove(fileName) }
                if (deletion != null) {
                    // What an unfinished row left goes with it: its partial, and the file under the final
                    // name its resume record covers (written before 1.4, or whole but not extracted).
                    val total = if (deletion.completed) null else resumeStore.stateFor(fileName)?.totalBytes
                    runCatching { downloadFileManager.deleteDownloadFiles(deletion.entity, deletion.deleteFile, deletion.extracted, total) }
                        .onFailure { Log.w(TAG, "Could not delete the files of $fileName: ${it.message}") }
                }
                resumeStore.finished(fileName)
                downloadProgressTracker.persist { delete(fileName) }
            }
        }
    }

    private fun updateStatusNow(fileName: String, status: DownloadStatus) =
        downloadProgressTracker.updateDownloadStatus(fileName, status)

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
        // From here a pause parks the session and a stop or delete lets it go (see windDown).
        torrentSessions.add(file.fileName)
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
                // Throws ArchiveExtractionException with the reason; nothing of the attempt is left in the folder.
                extractedFilesMap[file.fileName] = archiveExtractorService.extractArchiveFile(
                    context, internalFile, downloadDirUri, subPath
                )
            } else {
                // Non-archive or auto-unzip disabled: copy directly from cache to SAF, under the
                // partial name until the copy is whole (one cut short never looks like the game).
                val part = downloadFileManager.createPartFile(file, downloadDirUri.toString(), subPath)
                    ?: throw Exception("Failed to create destination file in storage.")
                Log.d(TAG, "Copying torrent file to SAF: ${part.uri}")
                updateStatus(file.fileName, DownloadStatus.COPYING)
                try {
                    context.contentResolver.openOutputStream(part.uri)?.use { out ->
                        BufferedOutputStream(out, Constants.EXTRACTION_BUFFER_SIZE).use { buffOut ->
                            internalFile.inputStream().use { it.copyTo(buffOut, Constants.EXTRACTION_BUFFER_SIZE) }
                        }
                    } ?: throw Exception("Could not open output stream for ${part.uri}")
                    downloadFileManager.promote(part, file, downloadDirUri.toString(), subPath)
                } catch (e: Exception) {
                    // The torrent is released below, so a retry copies from a fresh download: drop the partial.
                    withContext(NonCancellable) { runCatching { part.delete() } }
                    throw e
                }
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
            // Paused or stopped meanwhile (an extraction stops at once): the job lands it on PAUSED / STOPPED.
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Error processing torrent file for ${file.fileName}: ${e.message}", e)
            markFailed(file.fileName, e)
            if (e is ArchiveExtractionException) {
                // The torrent arrived whole: keep its data and handle, parked like a pause, so a retry
                // in this process unpacks it again without downloading it (deleting the row releases it).
                runCatching { torrentDownloadService.pauseDownload(file) }
            } else {
                // Untrack and, if nothing else uses the torrent, release it (deletes the cached data);
                // a retry re-fetches the metadata and starts clean instead of leaving a partial behind.
                // Never seed a failed move: the release is what lets the retry start clean.
                runCatching { torrentDownloadService.finishDownload(file, allowSeeding = false) }
            }
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
        // Through the history's ordered writer: the clean-up of an earlier stop has landed by now.
        val previous = downloadProgressTracker.read { getByFileName(file.fileName) }
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
            downloadProgressTracker.persist { setDebrid(file.fileName, null, null, null) }
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
        val ids = torrentId to remote.id
        downloadProgressTracker.persist { setDebrid(file.fileName, client.provider.name, ids.first, ids.second) }
        awaitFinished(torrentId)

        updateStatus(file.fileName, DownloadStatus.DOWNLOADING)
        downloadProgressTracker.updateDownloadProgress(file.fileName, 0f, 0f, 0L)
        // Links expire, so each HTTP attempt asks for a fresh one; attempts resume from the partial file.
        performHttpDownload(file, resumable = true, origin = DownloadOrigins.forDebrid(client.provider.name)) {
            client.requestDownload(torrentId, remote.id)
        }
        downloadProgressTracker.persist { setDebrid(file.fileName, null, null, null) }
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
                // The file arrived whole and only unpacking failed: another attempt would not change that.
                if (e is ArchiveExtractionException) throw e
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
            // Its server could not carry it on (no length, no Accept-Ranges): what the last attempt
            // left is no use, so it goes before anything is asked, and no Range / If-Range is sent.
            val fromZero = resumable && downloadProgressTracker.restartsFromZero(file.fileName)
            if (fromZero) {
                resumeStore.finished(file.fileName)
                downloadFileManager.findPartialFile(file, downloadDirUri.toString(), subPath, null)
                    ?.let { downloadFileManager.deleteFile(it) }
            }
            // Only a transfer this app started and has not finished is continued: a file that was
            // finished and is fetched again, or one already in the folder, starts from zero.
            val resume = if (resumable && !fromZero) resumeStore.stateFor(file.fileName) else null
            // Written as `<name>.dogmatix-part` and renamed once complete (see DownloadFileManager.promote).
            val partial = resume?.let { downloadFileManager.findPartialFile(file, downloadDirUri.toString(), subPath, it.totalBytes) }
            val partialBytes = partial?.length() ?: 0L
            val origin = debridOrigin ?: DownloadOrigins.forWeb(downloadUrl)
            // If-Range only to the server that gave the validator. A debrid file is fixed by the
            // torrent's hashes, so its length is check enough when the link changes.
            val ifRange = resume?.validator?.takeIf { debridOrigin == null && resume.host == origin }
            val expectedTotal = resume?.totalBytes?.takeIf { it > 0 }
            val name = FileParsingUtils.decodeUrlEncodedFileName(file.fileName)
            // Files served by the RomM library need the account's credentials; archive.org gets our
            // User-Agent and, when signed in, the session, carried by hand to its data node.
            val fromArchive = iaAuth.appliesTo(downloadUrl)
            val headers = when {
                RommSource.isDownloadFrom(rommClient.configuredBaseUrl(), downloadUrl) -> rommClient.downloadHeaders()
                fromArchive -> iaAuth.headers(downloadUrl)
                else -> emptyMap()
            }
            var connection: HttpURLConnection = downloadHttpClient.createConnection(
                downloadUrl, rangeStart = partialBytes, headers = headers, ifRange = ifRange, configure = rommClient::secure, authRedirects = fromArchive
            )
            // Decided before the partial is touched.
            var response = connection.resumeResponse()
            var plan = HttpResume.plan(partialBytes, expectedTotal, response, name)
            if (plan == HttpResume.Plan.Refetch) {
                Log.d(TAG, "Cannot continue ${file.fileName} from $partialBytes bytes; fetching it whole")
                connection.disconnect()
                connection = downloadHttpClient.createConnection(downloadUrl, headers = headers, configure = rommClient::secure, authRedirects = fromArchive)
                response = connection.resumeResponse()
                plan = HttpResume.plan(0L, null, response, name)
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
                    target = downloadFileManager.createPartFile(file, downloadDirUri.toString(), subPath)
                        ?: throw Exception("Failed to create file in storage.")
                    outputStream = downloadFileManager.getOutputStream(target)
                        ?: throw Exception("Failed to open output stream for ${target.uri}")
                    startOffset = 0L
                    total = plan.total
                    if (resumable) {
                        // A transfer its server cannot carry on keeps no resume record: a pause or a
                        // stop starts it again from zero, and the paused row says so.
                        val canResume = HttpResume.canResume(response)
                        if (canResume) {
                            val validator = HttpResume.validator(connection.getHeaderField("ETag"), connection.getHeaderField("Last-Modified"))
                            resumeStore.started(file.fileName, validator, origin, plan.total)
                        } else {
                            Log.d(TAG, "${file.fileName}: the server gave no length and no Accept-Ranges; a pause will start it again")
                            resumeStore.finished(file.fileName)
                        }
                        downloadProgressTracker.setRestartsFromZero(file.fileName, !canResume)
                    }
                }
                HttpResume.Plan.Complete -> {
                    connection.disconnect()
                    Log.d(TAG, "${file.fileName} was already complete on disk")
                    val whole = partial ?: throw UnexpectedContentException("Lost the partial file of ${file.fileName}")
                    handlePostDownload(file, downloadFileManager.promote(whole, file, downloadDirUri.toString(), subPath), subPath)
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

            // Without a length (archive.org's ZIP viewer extracts on the fly) the bar follows the indexed size.
            streamWithProgress(inputStream, outputStream, file, total ?: -1L, startOffset, meter, expectedBytes = total ?: file.fileSize.takeIf { it > 0 } ?: -1L)
            // All of it is on disk: close before the partial takes the final name.
            outputStream.close()
            handlePostDownload(file, downloadFileManager.promote(target, file, downloadDirUri.toString(), subPath), subPath)

        } catch (e: kotlinx.coroutines.CancellationException) {
            // The run's end sets the status (paused, stopped, deleted); a resumable partial stays.
            if (!resumable) withContext(NonCancellable) { documentFile?.let { downloadFileManager.deleteFile(it) } }
            throw e
        } catch (e: Exception) {
            if (!resumable) documentFile?.let { downloadFileManager.deleteFile(it) }
            // The next attempt sets it running again; after the last one the run's end marks it failed.
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
        meter: TransferMeter? = null,
        /** What the progress bar counts up to: [contentLength], or the indexed size when the server gave none. */
        expectedBytes: Long = contentLength
    ) {
        val buffer = ByteArray(Constants.BUFFER_SIZE)
        var downloaded = startOffset
        val startTime = System.currentTimeMillis()
        var lastUpdateTime = startTime
        var lastDownloaded = startOffset

        while (true) {
            val bytesRead = input.read(buffer)
            if (bytesRead == -1) break
            // Pause, stop and delete cancel the job: without a cancellation point the transfer would
            // run to the end (and a cancelled one must never be taken for complete).
            currentCoroutineContext().ensureActive()

            output.write(buffer, 0, bytesRead)
            downloaded += bytesRead
            // Shared with every other HTTP download, and re-read live from Settings.
            downloadSpeedController.throttle(bytesRead)

            val now = System.currentTimeMillis()
            meter?.let { it.bytes += bytesRead; it.millis = now - startTime }
            val progress = if (expectedBytes > 0)
                ArchiveExtractionUtils.calculateProgress(downloaded, expectedBytes) else 0f

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
        if (HttpResume.isTruncated(downloaded, contentLength)) {
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
        // A failure throws ArchiveExtractionException (the row goes FAILED with its reason) and
        // leaves the archive and its resume record as they are: retrying finds it complete on disk
        // (HttpResume.Plan.Complete) and unpacks it again without downloading it.
        val extracted = archiveExtractorService.extractArchive(
            context, documentFile.uri, downloadFileManager.getDownloadDirectoryUri(file), subPath)
        downloadFileManager.deleteFile(documentFile)
        extractedFilesMap[file.fileName] = extracted
        resumeStore.finished(file.fileName)
        updateStatus(file.fileName, DownloadStatus.COMPLETED)
        _finished.tryEmit(file.fileName)
        checkServiceLifecycle()
    }

    private suspend fun updateStatus(fileName: String, status: DownloadStatus) =
        downloadProgressTracker.updateDownloadStatus(fileName, status)

    /**
     * Android 12+ refuses a foreground service started from the background (an automatic retry
     * while the app is closed): false then. A download started anyway still runs while the
     * process lives; automatic retries wait for the app instead (see [retryAutomatically]).
     */
    private fun startForegroundService(): Boolean =
        runCatching {
            context.startForegroundService(Intent(context, DownloadForegroundService::class.java).apply {
                action = DownloadForegroundService.ACTION_START_SERVICE
            })
        }.onSuccess { foregroundServiceStarted = true }
            .onFailure { Log.w(TAG, "Foreground service not started: ${it.message}") }
            .isSuccess

    private fun checkServiceLifecycle() {
        if (foregroundServiceStarted && !downloadProgressTracker.hasActiveDownloads(ignoring = downloadQueue.heldNames()) && !seedingManager.isSeeding) {
            context.startService(Intent(context, DownloadForegroundService::class.java).apply {
                action = DownloadForegroundService.ACTION_STOP_SERVICE
            })
            foregroundServiceStarted = false
        }
    }
}
