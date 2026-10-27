package com.cortinadev.dogmatix.data.service

import android.content.Context
import android.os.SystemClock
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
import com.cortinadev.dogmatix.util.ScanCleanup
import com.cortinadev.dogmatix.util.ScanFailure
import com.cortinadev.dogmatix.util.ScanPlan
import com.cortinadev.dogmatix.util.ScanProgressMath
import com.cortinadev.dogmatix.util.ScanRequest
import com.cortinadev.dogmatix.util.ScanRequests
import com.cortinadev.dogmatix.util.ScanRetry
import com.cortinadev.dogmatix.util.ScrapeRecipes
import com.cortinadev.dogmatix.util.SourceKind
import com.cortinadev.dogmatix.util.SourceLabels
import com.cortinadev.dogmatix.util.SourceRef
import com.cortinadev.dogmatix.util.SourceScanCheck
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
    NEW_SOURCES,
    /** Sources switched on again in Sources. */
    SOURCES;

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
 * Runs every source scan of the app — Sources (rescan, full rescan, one console, retry, a source
 * switched on), the first start, new bundled sources and the background scan — one run at a time,
 * in the application scope (a scan keeps going when the screen that started it is left).
 *
 * Never two runs at once, and never a request lost: whatever is asked while a run is under way
 * waits, together with anything else asked meanwhile, for one more run right after it
 * ([ScanRequests.merge]); that run reads the scan results afresh when it starts. A background
 * scan asked for while every source is already being read is not needed and returns null.
 *
 * Sources are read side by side: up to [PARALLEL_WEB] web / RomM / page-recipe / Internet Archive
 * sources and [PARALLEL_TORRENTS] torrents at once, at most [DatabaseScrapingService.SCAN_PER_HOST] per server. Each source
 * replaces only its own rows, in its own transaction, so one that fails keeps what it gave last
 * time; unless forced, one that did not change is not rewritten at all ([SourceScanCheck]). A
 * torrent indexed before with the same set-up is kept without reading its metadata: that is
 * decided when the run is planned, from the scan results and the library's row counts only. The
 * rows are written before the source's result: if writing the result fails, the source counts as
 * failed and the next run reads it in full again (its stored validators are those of the rows
 * before), which is safe. A run then drops the rows of sources that are gone or switched off, and
 * — only when every source of a console was read and answered — the console's legacy rows. A
 * cancelled run ([cancelCurrent], or the background job losing Wi-Fi) removes nothing.
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
    private val initializing = Mutex()
    private val webLimit = Semaphore(PARALLEL_WEB)
    private val torrentLimit = Semaphore(PARALLEL_TORRENTS)

    /** The latest run, source by source ("Last scan" in Sources); null before the first scan. */
    val lastReport: Flow<ScanReport?> = scanDao.lastRun().map(::toReport)

    private val _finished = MutableSharedFlow<ScanSummary>(extraBufferCapacity = 4)
    /** A scan started from the app just ended (not the background one); Sources shows its report. */
    val finished: SharedFlow<ScanSummary> = _finished.asSharedFlow()

    private data class Task(val console: Console, val entry: UrlEntry, val force: Boolean) {
        val key: String get() = RescanStateHolder.sourceKey(console.id, entry.url)
        val kind: SourceKind get() = SourceKind.of(entry)
    }

    private class Planned(
        val task: Task,
        val previous: SourceScanCheck.Previous?,
        /** Why the source is read again (for the log); null when it is checked as usual, or kept. */
        val reason: SourceScanCheck.Reread?,
        /** A torrent kept as it is, without its metadata. */
        val skip: Boolean,
        /** A torrent read from what is at hand (a session handle, the metadata cache, a local file), not the swarm. */
        val local: Boolean,
        val weight: Double
    ) {
        val label: String get() = "${task.console.id} · ${SourceLabels.short(task.entry.url)}"
    }

    private class Tally {
        val sources = AtomicInteger()
        val updated = AtomicInteger()
        val unchanged = AtomicInteger()
        val failed = AtomicInteger()
        val files = AtomicInteger()
        val newFiles = AtomicInteger()
        val newGames = AtomicInteger()
        /** Some source wrote or lost library rows: the Library looks again once the run ends. */
        val rowsChanged = AtomicBoolean()
        /** Sources per console that were read and recorded (the legacy clean-up needs them all). */
        private val read = ConcurrentHashMap<String, AtomicInteger>()

        fun markRead(consoleId: String) {
            read.getOrPut(consoleId) { AtomicInteger() }.incrementAndGet()
        }

        fun readPerConsole(): Map<String, Int> = read.mapValues { it.value.get() }

        fun summary(kind: ScanKind) = ScanSummary(
            kind, sources.get(), updated.get(), unchanged.get(), failed.get(), files.get(), newFiles.get(), newGames.get()
        )
    }

    // ---- Requests -----------------------------------------------------------------------------

    /** Requests served by one run; [waiting] callers still want its result. */
    private class Batch {
        val requests = ArrayList<ScanRequest>()
        var waiting = 0
        var plan: ScanPlan? = null
        var job: Job? = null
        val done = CompletableDeferred<ScanSummary?>()
    }

    private val queueLock = Any()
    /** The run under way, and the one waiting for it (at most one: later requests join it). */
    private var runningBatch: Batch? = null
    private var pendingBatch: Batch? = null

    /**
     * Once per app start. First run: seed from the bundled list and scan it all. Later runs:
     * pull in anything a new app version added to the bundled list and scan just that.
     * Also puts the background scan in step with Settings.
     */
    fun initialize(): Job = scope.launch {
        autoScanScheduler.start()
        val request = initializing.withLock {
            if (sources.isEmpty()) {
                defaultSourcesLoader.loadDefaultSourcesToDatabase()
                ScanRequest.All(full = false)
            } else {
                val added = defaultSourcesLoader.syncNewDefaults()
                if (added.isEmpty()) null
                else ScanRequest.Sources(
                    added.flatMapTo(LinkedHashSet()) { (entity, urls) -> urls.map { SourceRef(entity.id, it.url) } },
                    ScanKind.NEW_SOURCES
                )
            }
        } ?: return@launch
        submit(request)
    }

    /** Every source: incremental, or with [force] every one read again in full. */
    fun scanAll(force: Boolean): Job = scope.launch { submit(ScanRequest.All(full = force)) }

    /** The background job: an incremental scan of everything (null when one is already under way, or it was stopped). */
    suspend fun runAutoScan(): ScanSummary? = submit(ScanRequest.All(full = false, auto = true))

    /** One console, every source read again (the user asked for exactly this console). */
    fun scanConsole(consoleId: String): Job = scope.launch { submit(ScanRequest.Consoles(setOf(consoleId))) }

    /** The sources that failed in the latest run, and nothing else: servers often recover within a minute. */
    fun retryFailed(): Job = scope.launch { submit(ScanRequest.RetryFailed) }

    /** One source of [consoleId] (switched on again in Sources), read incrementally. */
    fun scanSource(consoleId: String, url: String): Job =
        scope.launch { submit(ScanRequest.Sources(setOf(SourceRef(consoleId, url)), ScanKind.SOURCES)) }

    /**
     * Stops the run under way and drops the one waiting for it. Sources read so far keep their new
     * rows; the others keep their old ones, and nothing is cleaned up. Callers waiting get null.
     */
    fun cancelCurrent() {
        synchronized(queueLock) {
            pendingBatch?.done?.complete(null)
            pendingBatch = null
            runningBatch?.job?.cancel()
        }
    }

    /**
     * Queues [request] for the next run (starting it when none is under way) and waits for that
     * run to end. A caller that stops waiting withdraws its request; a run nobody waits for any
     * more is cancelled (the background job losing its Wi-Fi).
     */
    private suspend fun submit(request: ScanRequest): ScanSummary? {
        val batch = synchronized(queueLock) {
            // A background scan while every source is being read already: that run is the one it wanted.
            if (request is ScanRequest.All && request.auto && runningBatch?.plan?.everything == true) return null
            val batch = pendingBatch ?: Batch().also { pendingBatch = it }
            batch.requests += request
            batch.waiting++
            if (runningBatch == null) startNext()
            batch
        }
        try {
            return batch.done.await()
        } catch (e: CancellationException) {
            withdraw(batch, request)
            throw e
        }
    }

    private fun withdraw(batch: Batch, request: ScanRequest) {
        synchronized(queueLock) {
            batch.waiting--
            if (batch === pendingBatch) {
                batch.requests.remove(request)
                if (batch.requests.isEmpty()) {
                    pendingBatch = null
                    batch.done.complete(null)
                }
            } else if (batch === runningBatch && batch.waiting <= 0) {
                batch.job?.cancel()
            }
        }
    }

    /** Starts the waiting batch, if any. Called with [queueLock] held. */
    private fun startNext() {
        val batch = pendingBatch ?: return
        pendingBatch = null
        val plan = ScanRequests.merge(batch.requests) ?: run { batch.done.complete(null); return }
        batch.plan = plan
        runningBatch = batch
        batch.job = scope.launch {
            var summary: ScanSummary? = null
            try {
                summary = execute(plan)
            } catch (e: CancellationException) {
                Log.i(TAG, "Scan ${plan.kind} stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Scan ${plan.kind} failed", e)
            } finally {
                batch.done.complete(summary)
                synchronized(queueLock) {
                    if (runningBatch === batch) runningBatch = null
                    startNext()
                }
            }
        }
    }

    /** Another run that someone asked for (not only the background job) is about to follow. */
    private fun userRunFollows(): Boolean = synchronized(queueLock) {
        pendingBatch?.requests?.any { !(it is ScanRequest.All && it.auto) } == true
    }

    // ---- Running ------------------------------------------------------------------------------

    private suspend fun execute(plan: ScanPlan): ScanSummary? {
        val failedBefore = if (plan.retryFailed) failedInLastRun() else emptySet()
        val consoles = if (plan.everything) {
            sources.manufacturers.first().flatMap { it.consoles }
        } else {
            (plan.consoles + plan.sources.map { it.consoleId } + failedBefore.map { it.consoleId }).mapNotNull { sources.getConsole(it) }
        }
        val wanted = plan.sources + failedBefore
        val tasks = consoles.flatMap { console ->
            enabledEntries(console)
                .filter { plan.everything || console.id in plan.consoles || SourceRef(console.id, it.url) in wanted }
                .map { Task(console, it, force = plan.forced(console.id)) }
        }
        val covered = consoles.map { it.id }.filterTo(HashSet()) { plan.coversConsole(it) }
        if (tasks.isEmpty() && covered.isEmpty()) return null

        val runId = System.currentTimeMillis()
        val startedAt = SystemClock.elapsedRealtime()
        val tally = Tally()
        state.setRescanning(true)
        scraping.beginRun()
        try {
            val records = scanDao.all().associateBy { RescanStateHolder.sourceKey(it.consoleId, it.sourceUrl) }
            val rows = scanDao.rowCounts().associate { RescanStateHolder.sourceKey(it.consoleId, it.sourceUrl) to it.rowCount }
            val planned = order(tasks.map { plan(it, records[it.key], rows[it.key] ?: 0) })
            logPlan(plan.kind, planned, startedAt)
            state.beginProgress(planned.associate { it.task.key to it.weight })
            coroutineScope {
                val ticker = launch { while (isActive) { delay(TICK_MS); state.tickProgress() } }
                planned.map { p -> launch { scanOne(p, runId, plan.kind, tally) } }.joinAll()
                ticker.cancel()
            }
            // A run stopped half-way cleans nothing up: the sources it did not get to are not "gone".
            currentCoroutineContext().ensureActive()
            val perConsole = tasks.groupingBy { it.console.id }.eachCount()
            val legacy = ScanCleanup.legacyConsoles(covered, perConsole, tally.readPerConsole())
            if (removeStale(plan, covered + perConsole.keys, legacy)) tally.rowsChanged.set(true)
            if (plan.everything) settingsRepository.setLastFullScanAt(runId)
            val summary = tally.summary(plan.kind)
            Log.i(TAG, "Scan ${plan.kind} finished in ${secondsSince(startedAt)}: $summary" +
                if (tally.rowsChanged.get()) "" else " (no library rows changed)")
            if (tasks.isNotEmpty() && !userRunFollows()) announce(summary)
            return summary
        } finally {
            state.endProgress()
            // A run that wrote nothing leaves the Library and its recommendations alone.
            state.setRescanning(false, libraryChanged = tally.rowsChanged.get())
            state.clearProgressMessage()
            state.clearTorrentFetchProgress()
        }
    }

    private suspend fun failedInLastRun(): Set<SourceRef> =
        toReport(scanDao.lastRun().first())?.entries.orEmpty()
            .filter { it.status == SourceScanStatus.FAILED }
            .mapTo(HashSet()) { SourceRef(it.consoleId, it.url) }

    private fun enabledEntries(console: Console): List<UrlEntry> = console.urls.filter { it.enabled }.distinctBy { it.url }

    private fun enabledUrls(console: Console): Set<String> = enabledEntries(console).mapTo(HashSet()) { it.url }

    /**
     * Kept torrents first (they need no turn), then the torrents that must ask the swarm (the
     * slowest), then those read from what is at hand; web sources take turns between servers.
     */
    private fun order(planned: List<Planned>): List<Planned> {
        val (torrents, others) = planned.partition { it.task.kind == SourceKind.TORRENT }
        val groups = others.groupBy { MirrorUrls.hostOf(it.task.entry.url) ?: it.task.kind.name }.values.map { ArrayDeque(it) }
        val interleaved = ArrayList<Planned>(others.size)
        while (interleaved.size < others.size) groups.forEach { group -> group.removeFirstOrNull()?.let(interleaved::add) }
        return torrents.sortedBy { if (it.skip) 0 else if (!it.local) 1 else 2 } + interleaved
    }

    /**
     * What the run does with [task]. A torrent is kept or read from the scan results and [rows]
     * alone; only one that gave no files last time asks whether its file list is at hand (the
     * session or the metadata cache), and only the ones to read ask it for their progress weight.
     */
    private fun plan(task: Task, record: SourceScanEntity?, rows: Int): Planned {
        val previous = record?.let {
            SourceScanCheck.Previous(it.fingerprint, it.etag, it.lastModified, it.servedBy, it.contentHash, it.fileCount, rows)
        }
        if (task.kind == SourceKind.TORRENT) {
            val local by lazy { metadataFetcher.hasMetadata(task.entry.url) }
            val reason = SourceScanCheck.torrentReread(task.entry, previous, task.force) { local }
                ?: return Planned(task, previous, reason = null, skip = true, local = false, weight = 0.0)
            val weight = if (local) ScanProgressMath.WEIGHT_TORRENT_KNOWN else ScanProgressMath.WEIGHT_TORRENT_FETCH
            return Planned(task, previous, reason, skip = false, local = local, weight = weight)
        }
        val weight = when (task.kind) {
            SourceKind.ROMM -> ScanProgressMath.WEIGHT_ROMM
            SourceKind.IA -> ScanProgressMath.WEIGHT_IA
            // Capped: the implicit 1-100 of a site with seven pages would weigh like a hundred.
            SourceKind.SCRAPE ->
                ScanProgressMath.WEIGHT_SCRAPE_PAGE * ScrapeRecipes.plan(task.entry.url, task.entry.scrape?.pages.orEmpty()).size.coerceAtMost(20)
            else -> ScanProgressMath.WEIGHT_WEB
        }
        return Planned(task, previous, SourceScanCheck.rereadReason(task.entry, previous, task.force), skip = false, local = false, weight = weight)
    }

    /** One line per run, and one per source to read with why: what a slow run spent its time on. */
    private fun logPlan(kind: ScanKind, planned: List<Planned>, startedAt: Long) {
        val kept = planned.count { it.skip }
        Log.i(TAG, "Scan $kind started: ${planned.size} sources, $kept torrents kept without reading, " +
            "${planned.size - kept} to read (planned in ${secondsSince(startedAt)})")
        planned.filterNot { it.skip }.forEach { p ->
            val how = when {
                p.task.kind != SourceKind.TORRENT -> p.task.kind.name.lowercase(Locale.ROOT)
                p.local -> "torrent, file list at hand"
                else -> "torrent, from the swarm"
            }
            Log.i(TAG, "  to read: ${p.label} ($how; ${p.reason?.name ?: "check"})")
        }
    }

    private fun secondsSince(startedAt: Long): String =
        String.format(Locale.ROOT, "%.1f s", (SystemClock.elapsedRealtime() - startedAt) / 1000.0)

    private suspend fun scanOne(p: Planned, runId: Long, kind: ScanKind, tally: Tally) {
        val previous = p.previous
        val consoleId = p.task.console.id
        try {
            if (p.skip && previous != null) {
                // A torrent indexed before with the same set-up: its rows stand, no metadata needed.
                recordSuccess(p.task, SourceOutcome(SourceWrite(previous.fileCount, 0, unchanged = true, contentHash = previous.contentHash)), runId, kind, tally)
                tally.markRead(consoleId)
                return
            }
            val limit = if (p.task.kind == SourceKind.TORRENT) torrentLimit else webLimit
            limit.withPermit {
                state.setProgressMessage(context.getString(R.string.scan_scanning_console, p.task.console.name))
                val startedAt = SystemClock.elapsedRealtime()
                val outcome = try {
                    // A torrent that gave no files is read from what is at hand, never from the swarm.
                    scraping.scanSource(p.task.console, p.task.entry, previous, p.task.force, swarm = p.reason != SourceScanCheck.Reread.NO_ROWS)
                } catch (e: CancellationException) {
                    // The run itself was stopped: stop here. Any other cancellation (a time-out
                    // inside a scraper) is a failure of this source like any other.
                    currentCoroutineContext().ensureActive()
                    Log.w(TAG, "Scan of ${p.task.entry.url} for $consoleId was cut short", e)
                    recordFailure(p.task, e, runId, kind, tally)
                    null
                } catch (e: Exception) {
                    Log.w(TAG, "Scan of ${p.task.entry.url} for $consoleId failed", e)
                    recordFailure(p.task, e, runId, kind, tally)
                    null
                } finally {
                    state.sourceDone(p.task.key)
                }
                val result = outcome?.write?.let { if (it.unchanged) "unchanged, ${it.files} files" else "${it.files} files written, ${it.newFiles} new" }
                Log.i(TAG, "Read ${p.label} in ${secondsSince(startedAt)}: ${result ?: "failed"}")
                if (outcome != null) {
                    if (!outcome.write.unchanged) tally.rowsChanged.set(true)
                    recordSuccess(p.task, outcome, runId, kind, tally)
                    tally.markRead(consoleId)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Writing the result itself failed: the source does not count as read, the others go on.
            Log.e(TAG, "Could not record the scan of ${p.task.entry.url}", e)
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

    /**
     * Rows and results of sources no longer set up, in [consoles] (every console with [ScanPlan.everything]),
     * and the legacy rows of [legacy]. What counts as set up is read now, not when the run began:
     * a source deleted or switched off while the run read it goes too. True when rows went.
     */
    private suspend fun removeStale(plan: ScanPlan, consoles: Set<String>, legacy: Set<String>): Boolean {
        val configured: Map<String, Set<String>> = if (plan.everything) {
            sources.manufacturers.first().flatMap { it.consoles }.associate { it.id to enabledUrls(it) }
        } else {
            consoles.associateWith { id -> sources.getConsole(id)?.let(::enabledUrls).orEmpty() }
        }
        val indexed = scanDao.indexedSources().map { SourceRef(it.consoleId, it.sourceUrl) }
        val stale = ScanCleanup.staleRows(indexed, configured, plan.everything, legacy)
        stale.forEach { scanDao.deleteSource(it.consoleId, it.url) }
        val results = scanDao.all().map { SourceRef(it.consoleId, it.sourceUrl) }
        ScanCleanup.staleResults(results, configured, plan.everything).forEach { scanDao.delete(it.consoleId, it.url) }
        if (stale.isNotEmpty()) Log.i(TAG, "Removed the rows of ${stale.size} sources no longer set up")
        return stale.isNotEmpty()
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
