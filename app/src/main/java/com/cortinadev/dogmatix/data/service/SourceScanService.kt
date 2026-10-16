package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.util.Log
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.dao.ScanReportRow
import com.cortinadev.dogmatix.data.local.dao.SourceScanDao
import com.cortinadev.dogmatix.data.local.entity.SourceScanEntity
import com.cortinadev.dogmatix.data.model.Console
import com.cortinadev.dogmatix.data.model.UrlEntry
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.repository.SourcesRepository
import com.cortinadev.dogmatix.data.state.RescanStateHolder
import com.cortinadev.dogmatix.util.MirrorUrls
import com.cortinadev.dogmatix.util.ScanFailure
import com.cortinadev.dogmatix.util.ScanProgressMath
import com.cortinadev.dogmatix.util.ScanRetry
import com.cortinadev.dogmatix.util.SourceKind
import com.cortinadev.dogmatix.util.SourceScanCheck
import com.cortinadev.dogmatix.util.SourcesJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Why a scan run happened; the report names it. */
enum class ScanKind {
    /** "Rescan all": incremental. */
    MANUAL,
    /** "Full rescan": every source read again, the listing checks ignored. */
    FULL,
    /** One console's refresh button: read again in full. */
    CONSOLE,
    /** The background scan (Settings → Automatic scan). */
    AUTO,
    /** "Retry failed" in the report. */
    RETRY,
    /** Sources a new app version added to the bundled list. */
    NEW_SOURCES;

    companion object {
        fun fromName(name: String?): ScanKind = entries.firstOrNull { it.name == name } ?: MANUAL
    }
}

enum class SourceScanStatus {
    UPDATED, UNCHANGED, FAILED;

    companion object {
        fun fromName(name: String?): SourceScanStatus = entries.firstOrNull { it.name == name } ?: FAILED
    }
}

/** How a scan run went (the background notification, and the notice when the report cannot be shown). */
data class ScanSummary(
    val kind: ScanKind,
    val sources: Int,
    val updated: Int,
    val unchanged: Int,
    val failed: Int,
    val files: Int,
    val newFiles: Int,
    val newGames: Int
)

/** One source in the "Last scan" report. */
data class ScanReportEntry(
    val consoleId: String,
    val consoleName: String,
    val url: String,
    val status: SourceScanStatus,
    val files: Int,
    val newFiles: Int,
    val failure: ScanFailure?,
    val httpCode: Int?,
    val detail: String?,
    val servedBy: String?
)

/** The latest scan run, source by source: failed first, then updated, then unchanged. */
data class ScanReport(val runId: Long, val kind: ScanKind, val finishedAt: Long, val entries: List<ScanReportEntry>) {
    val updated: Int get() = entries.count { it.status == SourceScanStatus.UPDATED }
    val unchanged: Int get() = entries.count { it.status == SourceScanStatus.UNCHANGED }
    val failed: Int get() = entries.count { it.status == SourceScanStatus.FAILED }
    val newFiles: Int get() = entries.sumOf { it.newFiles }
}

/**
 * Runs every source scan of the app — Sources (rescan, full rescan, one console, retry), the
 * first start, new bundled sources and the background scan — one run at a time, in the
 * application scope (a scan keeps going when the screen that started it is left).
 *
 * Sources are read side by side: up to [PARALLEL_WEB] web / RomM sources and [PARALLEL_TORRENTS]
 * torrents at once, at most [DatabaseScrapingService.SCAN_PER_HOST] per server. Each source
 * replaces only its own rows, in its own transaction, so one that fails keeps what it gave last
 * time; unless forced, one that did not change is not rewritten at all ([SourceScanCheck]).
 * A run over every source then drops the rows of sources that are gone or switched off.
 */
@Singleton
class SourceScanService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sources: SourcesRepository,
    private val scraping: DatabaseScrapingService,
    private val defaultSourcesLoader: DefaultSourcesLoader,
    private val state: RescanStateHolder,
    private val scanDao: SourceScanDao,
    private val settingsRepository: SettingsRepository,
    private val metadataFetcher: TorrentMetadataFetcher,
    private val autoScanScheduler: AutoScanScheduler
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = Mutex()
    private val webLimit = Semaphore(PARALLEL_WEB)
    private val torrentLimit = Semaphore(PARALLEL_TORRENTS)

    /** The latest run, source by source ("Last scan" in Sources); null before the first scan. */
    val lastReport: Flow<ScanReport?> = scanDao.lastRun().map(::toReport)

    private val _finished = MutableSharedFlow<ScanSummary>(extraBufferCapacity = 4)
    /** A scan started from the app just ended (not the background one); Sources shows its report. */
    val finished: SharedFlow<ScanSummary> = _finished.asSharedFlow()

    private data class Task(val console: Console, val entry: UrlEntry) {
        val key: String get() = RescanStateHolder.sourceKey(console.id, entry.url)
        val kind: SourceKind get() = SourceKind.of(entry.url)
    }

    private class Planned(val task: Task, val previous: SourceScanCheck.Previous?, val skip: Boolean, val weight: Double)

    private class Tally {
        val sources = AtomicInteger()
        val updated = AtomicInteger()
        val unchanged = AtomicInteger()
        val failed = AtomicInteger()
        val files = AtomicInteger()
        val newFiles = AtomicInteger()
        val newGames = AtomicInteger()

        fun summary(kind: ScanKind) = ScanSummary(
            kind, sources.get(), updated.get(), unchanged.get(), failed.get(), files.get(), newFiles.get(), newGames.get()
        )
    }

    /**
     * Once per app start. First run: seed from the bundled list and scan it all. Later runs:
     * pull in anything a new app version added to the bundled list and scan just that.
     * Also puts the background scan in step with Settings.
     */
    fun initialize(): Job = scope.launch {
        autoScanScheduler.start()
        if (state.isRescanning.value) return@launch
        if (sources.isEmpty()) {
            defaultSourcesLoader.loadDefaultSourcesToDatabase()
            scanEverything(force = false, kind = ScanKind.MANUAL)
        } else {
            val added = defaultSourcesLoader.syncNewDefaults()
            if (added.isEmpty()) return@launch
            run(ScanKind.NEW_SOURCES, added.flatMap { (entity, urls) -> tasksFor(Console(entity.id, entity.name, urls)) }, force = false)
        }
    }

    /** Every source: incremental, or with [force] every one read again in full. Ignored while a scan runs. */
    fun scanAll(force: Boolean): Job = scope.launch {
        scanEverything(force, if (force) ScanKind.FULL else ScanKind.MANUAL)
    }

    /** The background job: an incremental scan of everything, never alongside another one (null then). */
    suspend fun runAutoScan(): ScanSummary? = scanEverything(force = false, kind = ScanKind.AUTO)

    /** One console, every source read again (the user asked for exactly this console). */
    fun scanConsole(consoleId: String): Job = scope.launch {
        val entity = sources.getConsoleEntity(consoleId) ?: return@launch
        val console = Console(entity.id, entity.name, SourcesJson.parseUrlEntries(entity.urls))
        run(ScanKind.CONSOLE, tasksFor(console), force = true, cleanup = mapOf(console.id to enabledUrls(console)))
    }

    /** The sources that failed in the latest run, and nothing else: servers often recover within a minute. */
    fun retryFailed(): Job = scope.launch {
        val failed = toReport(scanDao.lastRun().first())?.entries?.filter { it.status == SourceScanStatus.FAILED }.orEmpty()
        if (failed.isEmpty()) return@launch
        val tasks = failed.groupBy { it.consoleId }.flatMap { (consoleId, entries) ->
            val entity = sources.getConsoleEntity(consoleId) ?: return@flatMap emptyList()
            val urls = entries.map { it.url }.toSet()
            tasksFor(Console(entity.id, entity.name, SourcesJson.parseUrlEntries(entity.urls))).filter { it.entry.url in urls }
        }
        run(ScanKind.RETRY, tasks, force = false)
    }

    private suspend fun scanEverything(force: Boolean, kind: ScanKind): ScanSummary? {
        val consoles = sources.manufacturers.first().flatMap { it.consoles }
        return run(kind, consoles.flatMap(::tasksFor), force, cleanup = consoles.associate { it.id to enabledUrls(it) }, everything = true)
    }

    private fun enabledUrls(console: Console): Set<String> = console.urls.filter { it.enabled }.map { it.url }.toSet()

    private fun tasksFor(console: Console): List<Task> =
        console.urls.filter { it.enabled }.distinctBy { it.url }.map { Task(console, it) }

    /**
     * One scan run. [cleanup] (console → its enabled URLs) removes, for those consoles, the rows
     * and results of sources no longer set up, and legacy rows no source claimed when every
     * source of the console answered; with [everything], consoles missing from it go as well.
     */
    private suspend fun run(
        kind: ScanKind,
        tasks: List<Task>,
        force: Boolean,
        cleanup: Map<String, Set<String>>? = null,
        everything: Boolean = false
    ): ScanSummary? {
        // Never two runs at once: a request while one runs is dropped, not queued.
        if (!running.tryLock()) return null
        try {
            if (tasks.isEmpty() && cleanup.isNullOrEmpty()) return null
            val runId = System.currentTimeMillis()
            state.setRescanning(true)
            scraping.beginRun()
            try {
                val records = scanDao.all().associateBy { RescanStateHolder.sourceKey(it.consoleId, it.sourceUrl) }
                val rows = scanDao.rowCounts().associate { RescanStateHolder.sourceKey(it.consoleId, it.sourceUrl) to it.rowCount }
                val planned = order(tasks).map { plan(it, records[it.key], rows[it.key] ?: 0, force) }
                state.beginProgress(planned.associate { it.task.key to it.weight })
                val tally = Tally()
                val consoleFailed = ConcurrentHashMap.newKeySet<String>()
                coroutineScope {
                    val ticker = launch { while (isActive) { delay(TICK_MS); state.tickProgress() } }
                    planned.map { p -> launch { scanOne(p, runId, kind, force, tally, consoleFailed) } }.joinAll()
                    ticker.cancel()
                }
                cleanup?.let { removeStale(it, everything, consoleFailed) }
                if (everything) settingsRepository.setLastFullScanAt(runId)
                val summary = tally.summary(kind)
                Log.i(TAG, "Scan $kind: $summary")
                if (tasks.isNotEmpty()) announce(summary)
                return summary
            } finally {
                state.endProgress()
                state.setRescanning(false)
                state.clearProgressMessage()
                state.clearTorrentFetchProgress()
            }
        } finally {
            running.unlock()
        }
    }

    /** Torrents that must ask the swarm first (the slowest); web sources take turns between servers. */
    private fun order(tasks: List<Task>): List<Task> {
        val (torrents, others) = tasks.partition { it.kind == SourceKind.TORRENT }
        val groups = others.groupBy { MirrorUrls.hostOf(it.entry.url) ?: it.kind.name }.values.map { ArrayDeque(it) }
        val interleaved = ArrayList<Task>(others.size)
        while (interleaved.size < others.size) groups.forEach { group -> group.removeFirstOrNull()?.let(interleaved::add) }
        return torrents.sortedBy { metadataFetcher.hasMetadata(it.entry.url) } + interleaved
    }

    private fun plan(task: Task, record: SourceScanEntity?, rows: Int, force: Boolean): Planned {
        val previous = record?.let {
            SourceScanCheck.Previous(it.fingerprint, it.etag, it.lastModified, it.servedBy, it.contentHash, it.fileCount, rows)
        }
        val skip = SourceScanCheck.canSkipTorrent(task.entry, previous, force)
        val weight = when {
            skip -> 0.0
            task.kind == SourceKind.TORRENT ->
                if (metadataFetcher.hasMetadata(task.entry.url)) ScanProgressMath.WEIGHT_TORRENT_KNOWN else ScanProgressMath.WEIGHT_TORRENT_FETCH
            task.kind == SourceKind.ROMM -> ScanProgressMath.WEIGHT_ROMM
            else -> ScanProgressMath.WEIGHT_WEB
        }
        return Planned(task, previous, skip, weight)
    }

    private suspend fun scanOne(p: Planned, runId: Long, kind: ScanKind, force: Boolean, tally: Tally, consoleFailed: MutableSet<String>) {
        val previous = p.previous
        try {
            if (p.skip && previous != null) {
                // A torrent indexed before with the same set-up: its rows stand, no metadata needed.
                recordSuccess(p.task, SourceOutcome(SourceWrite(previous.fileCount, 0, unchanged = true, contentHash = previous.contentHash)), runId, kind, tally)
                return
            }
            val limit = if (p.task.kind == SourceKind.TORRENT) torrentLimit else webLimit
            limit.withPermit {
                state.setProgressMessage(context.getString(R.string.scan_scanning_console, p.task.console.name))
                val outcome = try {
                    scraping.scanSource(p.task.console, p.task.entry, previous, force)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Scan of ${p.task.entry.url} for ${p.task.console.id} failed", e)
                    consoleFailed += p.task.console.id
                    recordFailure(p.task, e, runId, kind, tally)
                    null
                } finally {
                    state.sourceDone(p.task.key)
                }
                outcome?.let { recordSuccess(p.task, it, runId, kind, tally) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Writing the result itself failed: the source counts as failed, the others go on.
            Log.e(TAG, "Could not record the scan of ${p.task.entry.url}", e)
            consoleFailed += p.task.console.id
        }
    }

    private suspend fun recordSuccess(task: Task, outcome: SourceOutcome, runId: Long, kind: ScanKind, tally: Tally) {
        val write = outcome.write
        tally.sources.incrementAndGet()
        (if (write.unchanged) tally.unchanged else tally.updated).incrementAndGet()
        tally.files.addAndGet(write.files)
        tally.newFiles.addAndGet(write.newFiles)
        tally.newGames.addAndGet(write.newGames)
        scanDao.upsert(
            SourceScanEntity(
                consoleId = task.console.id, sourceUrl = task.entry.url,
                fingerprint = SourceScanCheck.fingerprint(task.entry),
                etag = outcome.etag, lastModified = outcome.lastModified, servedBy = outcome.servedBy,
                contentHash = write.contentHash, fileCount = write.files, newFiles = write.newFiles,
                status = (if (write.unchanged) SourceScanStatus.UNCHANGED else SourceScanStatus.UPDATED).name,
                failure = null, httpCode = null, detail = null,
                scannedAt = System.currentTimeMillis(), runId = runId, runKind = kind.name
            )
        )
    }

    /** The rows were not touched, so the validators of the last good scan still describe them and stay. */
    private suspend fun recordFailure(task: Task, error: Exception, runId: Long, kind: ScanKind, tally: Tally) {
        tally.sources.incrementAndGet()
        tally.failed.incrementAndGet()
        val previous = scanDao.get(task.console.id, task.entry.url) ?: SourceScanEntity(
            consoleId = task.console.id, sourceUrl = task.entry.url, fingerprint = "",
            etag = null, lastModified = null, servedBy = null, contentHash = null, fileCount = -1, newFiles = 0,
            status = "", failure = null, httpCode = null, detail = null, scannedAt = 0, runId = 0, runKind = ""
        )
        scanDao.upsert(
            previous.copy(
                newFiles = 0, status = SourceScanStatus.FAILED.name,
                failure = ScanRetry.classify(error).name, httpCode = ScanRetry.httpCodeOf(error),
                detail = (error.message ?: error.javaClass.simpleName).take(300),
                scannedAt = System.currentTimeMillis(), runId = runId, runKind = kind.name
            )
        )
    }

    private suspend fun removeStale(configured: Map<String, Set<String>>, everything: Boolean, consoleFailed: Set<String>) {
        fun scope(consoleId: String): Set<String>? = configured[consoleId] ?: if (everything) emptySet() else null
        scanDao.indexedSources().forEach { indexed ->
            val urls = scope(indexed.consoleId) ?: return@forEach
            val stale = if (indexed.sourceUrl.isEmpty()) indexed.consoleId !in consoleFailed else indexed.sourceUrl !in urls
            if (stale) scanDao.deleteSource(indexed.consoleId, indexed.sourceUrl)
        }
        scanDao.all().forEach { record ->
            val urls = scope(record.consoleId) ?: return@forEach
            if (record.sourceUrl !in urls) scanDao.delete(record.consoleId, record.sourceUrl)
        }
    }

    /**
     * Sources shows the report of a scan it started; when it is not on screen, a failure still
     * gets the app's notice dialog. The background scan says nothing here (it has its notification).
     */
    private fun announce(summary: ScanSummary) {
        if (summary.kind == ScanKind.AUTO) return
        if (_finished.subscriptionCount.value > 0) {
            _finished.tryEmit(summary)
        } else if (summary.failed > 0) {
            state.setErrorMessage(context.resources.getQuantityString(R.plurals.scan_failed_notice, summary.failed, summary.failed))
        }
    }

    private fun toReport(rows: List<ScanReportRow>): ScanReport? {
        val first = rows.firstOrNull() ?: return null
        val order = compareBy<ScanReportEntry>(
            {
                when (it.status) {
                    SourceScanStatus.FAILED -> 0
                    SourceScanStatus.UPDATED -> 1
                    SourceScanStatus.UNCHANGED -> 2
                }
            },
            { it.consoleName.lowercase() }
        )
        return ScanReport(
            runId = first.runId,
            kind = ScanKind.fromName(first.runKind),
            finishedAt = rows.maxOf { it.scannedAt },
            entries = rows.map {
                ScanReportEntry(
                    consoleId = it.consoleId, consoleName = it.consoleName, url = it.sourceUrl,
                    status = SourceScanStatus.fromName(it.status), files = it.fileCount.coerceAtLeast(0), newFiles = it.newFiles,
                    failure = ScanFailure.fromName(it.failure), httpCode = it.httpCode, detail = it.detail, servedBy = it.servedBy
                )
            }.sortedWith(order)
        )
    }

    companion object {
        private const val TAG = "SourceScanService"
        /** Web listings and RomM platforms read at the same time. */
        const val PARALLEL_WEB = 3
        /** Torrents indexed at the same time (their file lists can be large). */
        const val PARALLEL_TORRENTS = 2
        private const val TICK_MS = 2_000L
    }
}
