package com.cortinadev.dogmatix.ui.screens.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.local.entity.FavouriteEntity
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.model.VersionPrefs
import com.cortinadev.dogmatix.data.repository.ConsoleRepository
import com.cortinadev.dogmatix.data.repository.FavouritesRepository
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.CollectionFileOps
import com.cortinadev.dogmatix.data.service.DownloadService
import com.cortinadev.dogmatix.data.service.LibraryScanService
import com.cortinadev.dogmatix.data.service.ScanProgress
import com.cortinadev.dogmatix.data.state.LibraryFilterRequest
import com.cortinadev.dogmatix.data.state.PendingLibraryFilters
import com.cortinadev.dogmatix.util.ActionTypes
import com.cortinadev.dogmatix.util.CollectionDuplicates
import com.cortinadev.dogmatix.util.CollectionGames
import com.cortinadev.dogmatix.util.CollectionHealth
import com.cortinadev.dogmatix.util.CollectionReclaim
import com.cortinadev.dogmatix.util.CollectionRepair
import com.cortinadev.dogmatix.util.CollectionStats
import com.cortinadev.dogmatix.util.ConsoleUsage
import com.cortinadev.dogmatix.util.DiskGame
import com.cortinadev.dogmatix.util.DuplicateGroup
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.HealthFinding
import com.cortinadev.dogmatix.util.HealthInput
import com.cortinadev.dogmatix.util.HealthKind
import com.cortinadev.dogmatix.util.LibrarySnapshot
import com.cortinadev.dogmatix.util.ReclaimCandidate
import com.cortinadev.dogmatix.util.ReclaimKind
import com.cortinadev.dogmatix.util.RepairAction
import com.cortinadev.dogmatix.util.RepairKind
import com.cortinadev.dogmatix.util.SourcesJson
import com.cortinadev.dogmatix.util.TagCount
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

/** Everything the five analysis tools show, worked out once per snapshot (off the main thread). */
data class CollectionAnalysis(
    val snapshot: LibrarySnapshot,
    val games: List<DiskGame>,
    val gameCount: Int,
    val consoles: List<ConsoleUsage>,
    val biggest: List<DiskGame>,
    val regions: List<TagCount>,
    val languages: List<TagCount>,
    val findings: List<HealthFinding>,
    val duplicates: List<DuplicateGroup>,
    val repairs: List<RepairAction>
) {
    val bytes: Long get() = games.sumOf { it.size }
    val files: Int get() = snapshot.files.count { CollectionGames.isGameFile(it) }
    val catalogGames: Int get() = snapshot.catalogGames.values.sum()
    val ownedCatalogGames: Int get() = consoles.sumOf { it.ownedCatalogGames }
}

/** A finished download for the Summary's "latest downloads". */
data class RecentDownload(val name: String, val consoleId: String?, val finishedAt: Long?)

/** Favourites and recent downloads: Summary facts that do not come from the disk walk. */
data class SummaryExtras(val favourites: Int, val favouritesOnDisk: Int, val recent: List<RecentDownload>)

/** The outcome of an action, for the snackbar. */
sealed interface CollectionMessage {
    data class Deleted(val done: Int, val failed: Int, val bytes: Long) : CollectionMessage
    data class Repaired(val done: Int, val failed: Int) : CollectionMessage
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class CollectionViewModel @Inject constructor(
    private val scanService: LibraryScanService,
    private val fileOps: CollectionFileOps,
    private val settingsRepository: SettingsRepository,
    favouritesRepository: FavouritesRepository,
    private val downloadService: DownloadService,
    consoleRepository: ConsoleRepository,
    private val pendingFilters: PendingLibraryFilters
) : ViewModel() {

    // Which tool is open: answered at once from memory while the D-pad moves through the list,
    // remembered in Settings once it settles. A tool written to Settings from elsewhere (the
    // app-wide search) opens too.
    private val chosenTool = MutableSharedFlow<CollectionTool>(extraBufferCapacity = 8)
    /** When the list last chose a tool: our own saves, landing a moment later, must not pull it back. */
    @Volatile private var chosenAt = 0L
    val tool: StateFlow<CollectionTool> = merge(
        settingsRepository.collectionTool
            .map { saved -> CollectionTool.entries.firstOrNull { it.name == saved } ?: CollectionTool.SUMMARY }
            .filter { System.currentTimeMillis() - chosenAt > TOOL_SAVE_DELAY_MS * 2 }
            .distinctUntilChanged(),
        chosenTool.onEach { chosenAt = System.currentTimeMillis() }
    ).stateIn(viewModelScope, SharingStarted.Eagerly, CollectionTool.SUMMARY)

    fun selectTool(tool: CollectionTool) {
        chosenTool.tryEmit(tool)
        if (tool == CollectionTool.FREE_SPACE) refreshCaches()
    }

    val snapshot: StateFlow<LibrarySnapshot?> = scanService.snapshot
    val progress: StateFlow<ScanProgress?> = scanService.progress

    fun analyze() = scanService.analyze()
    fun cancelAnalysis() = scanService.cancel()

    /** X: stops a whole-library analysis under way, or starts one. */
    fun toggleAnalysis() = if (progress.value?.partial == false) cancelAnalysis() else analyze()

    /** A ROMs folder is picked (Settings, or a console's own in Sources): there is something to analyse. */
    val libraryConfigured: StateFlow<Boolean> = combine(settingsRepository.downloadDirectory, settingsRepository.consoleDownloadDirectories) { root, custom ->
        root.isNotBlank() || custom.values.any { it.isNotBlank() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val versionPrefs = combine(settingsRepository.preferredRegion, settingsRepository.favoriteLanguages) { region, languages ->
        VersionPrefs(region, languages)
    }

    /** Status per file name only (the progress ticks do not count), so the analysis is not redone on every tick. */
    private val downloadStatus = downloadService.downloads.map { list ->
        list.associate { leaf(it.fileName) to it.status.name }
    }.distinctUntilChanged()

    private data class AnalysisContext(val prefs: VersionPrefs, val multiDisc: Boolean, val lastRescanAt: Long, val consoleIds: List<String>, val sourcesConfigured: Boolean)

    private val analysisContext = combine(
        versionPrefs,
        settingsRepository.multiDiscPlaylists,
        // When every source was last scanned to the end (manual or automatic scan).
        settingsRepository.lastFullScanAt,
        consoleRepository.getAllConsoles()
    ) { prefs, multiDisc, lastRescan, consoles ->
        AnalysisContext(prefs, multiDisc, lastRescan, consoles.map { it.id }, consoles.any { console -> SourcesJson.parseUrlEntries(console.urls).any { it.enabled } })
    }

    val analysis: StateFlow<CollectionAnalysis?> = combine(snapshot, analysisContext, downloadStatus) { snapshot, context, downloads ->
        Triple(snapshot, context, downloads)
    }.mapLatest { (snapshot, context, downloads) ->
        snapshot?.let { analyze(it, context, downloads) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun analyze(snapshot: LibrarySnapshot, context: AnalysisContext, downloads: Map<String, String>): CollectionAnalysis {
        val games = CollectionGames.group(snapshot.files)
        val active = downloads.values.any { it in ACTIVE_STATUSES }
        val findings = CollectionHealth.check(
            HealthInput(
                snapshot = snapshot,
                consoleIds = context.consoleIds,
                downloads = downloads,
                downloadsActive = active,
                multiDiscPlaylists = context.multiDisc,
                sourcesConfigured = context.sourcesConfigured,
                lastRescanAt = context.lastRescanAt,
                now = System.currentTimeMillis()
            )
        )
        return CollectionAnalysis(
            snapshot = snapshot,
            games = games,
            gameCount = CollectionStats.gameCount(games),
            consoles = CollectionStats.byConsole(snapshot, games),
            biggest = CollectionStats.biggest(games, BIGGEST_COUNT),
            regions = CollectionStats.regions(games),
            languages = CollectionStats.languages(games),
            findings = findings,
            duplicates = CollectionDuplicates.find(games, context.prefs, context.multiDisc),
            repairs = CollectionRepair.plan(findings)
        )
    }

    val summaryExtras: StateFlow<SummaryExtras> = combine(favouritesRepository.keys, snapshot, downloadService.downloads.map { list ->
        list.filter { it.status == DownloadStatus.COMPLETED }.sortedByDescending { it.finishedAt ?: 0L }.take(RECENT_COUNT)
            .map { RecentDownload(it.name, downloadService.entityFor(it.fileName)?.consoleId, it.finishedAt) }
    }.distinctUntilChanged()) { favourites, snapshot, recent ->
        val onDisk = snapshot?.let { snap ->
            val rows = snap.catalog.associateBy { it.id }
            snap.files.mapNotNullTo(HashSet()) { file -> file.catalogId?.let(rows::get)?.let { FavouriteEntity.key(it.consoleId, it.fileName) } }
        }.orEmpty()
        SummaryExtras(favourites.size, favourites.count { it in onDisk }, recent)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, SummaryExtras(0, 0, emptyList()))

    private val _working = MutableStateFlow(false)
    /** A delete, move or repair is running: the action buttons wait. */
    val working: StateFlow<Boolean> = _working.asStateFlow()

    private val _messages = MutableSharedFlow<CollectionMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<CollectionMessage> = _messages.asSharedFlow()

    private fun run(block: suspend () -> CollectionMessage?) {
        if (_working.value) return
        _working.value = true
        viewModelScope.launch {
            try {
                block()?.let { _messages.emit(it) }
            } finally {
                _working.value = false
            }
        }
    }

    // Duplicates: the copy kept per group (the suggestion unless the user picked another).

    private val _keepChoices = MutableStateFlow<Map<String, String>>(emptyMap())
    val keepChoices: StateFlow<Map<String, String>> = _keepChoices.asStateFlow()

    fun keepOf(group: DuplicateGroup, choices: Map<String, String> = _keepChoices.value): String? = choices[group.id] ?: group.keep?.id

    fun chooseKeep(group: DuplicateGroup, game: DiskGame) = _keepChoices.update { it + (group.id to game.id) }

    /** Deletes every copy but the kept one in [groups]. */
    fun deleteDuplicates(groups: List<DuplicateGroup>) = run {
        val games = groups.flatMap { group -> group.removable(keepOf(group)) }
        if (games.isEmpty()) return@run null
        val result = fileOps.delete(games.flatMap { it.files }, games.mapNotNull { it.setFolderUri }, ActionTypes.DUPLICATES)
        CollectionMessage.Deleted(result.done, result.failed, result.bytes)
    }

    // Repair: every fix ticked until the user unticks it.

    private val _repairUnticked = MutableStateFlow<Set<String>>(emptySet())
    val repairUnticked: StateFlow<Set<String>> = _repairUnticked.asStateFlow()

    fun toggleRepair(id: String) = _repairUnticked.update { if (id in it) it - id else it + id }

    fun toggleAllRepairs() {
        val ids = analysis.value?.repairs?.map { it.id }.orEmpty()
        _repairUnticked.update { if (it.isEmpty()) ids.toSet() else emptySet() }
    }

    /** Health → "Repair these": only the fixes of [kind] stay ticked. */
    fun focusRepairs(kind: HealthKind) {
        val repairs = analysis.value?.repairs.orEmpty()
        val keep = CollectionRepair.idsFor(repairs, kind)
        _repairUnticked.value = repairs.map { it.id }.filterNot { it in keep }.toSet()
    }

    /** Health → one finding: only its fix stays ticked. */
    fun focusRepair(id: String) {
        _repairUnticked.value = analysis.value?.repairs.orEmpty().map { it.id }.filterNot { it == id }.toSet()
    }

    /**
     * Runs [actions] in their order (playlists move discs before folders are cleared), batched so
     * every move to one console and all the deletions go through one call each. Counts files.
     */
    fun applyRepairs(actions: List<RepairAction>) = run {
        if (actions.isEmpty()) return@run null
        val byKind = actions.groupBy { it.kind }
        var result = CollectionFileOps.Result(0, 0)
        byKind[RepairKind.CREATE_PLAYLIST].orEmpty().forEach { action ->
            val finding = action.finding
            val parent = finding.playlistParentUri
            val playlist = finding.playlist
            val ok = parent != null && playlist != null && fileOps.createPlaylist(parent, finding.setFolderUri, playlist, finding.moves, finding.consoleId)
            result += CollectionFileOps.Result(if (ok) 1 else 0, if (ok) 0 else 1)
        }
        byKind[RepairKind.MOVE_TO_CONSOLE].orEmpty().groupBy { it.finding.targetConsoleId }.forEach { (target, moves) ->
            if (target != null) result += fileOps.moveToConsole(moves.flatMap { it.finding.files }, target)
        }
        val deletions = listOf(RepairKind.DELETE_EMPTY_FILE, RepairKind.DELETE_PARTIAL, RepairKind.DELETE_EXTRACTED_ARCHIVE).flatMap { byKind[it].orEmpty() }
        if (deletions.isNotEmpty()) result += fileOps.delete(deletions.flatMap { it.finding.files }, type = ActionTypes.REPAIR)
        byKind[RepairKind.DELETE_EMPTY_FOLDER]?.let { folders -> result += fileOps.deleteEmptyFolders(folders.mapNotNull { it.finding.folder }) }
        CollectionMessage.Repaired(result.done, result.failed)
    }

    // Free up space: nothing ticked until the user ticks it.

    private val caches = MutableStateFlow<Pair<Long?, Long>>(null to 0L)

    fun refreshCaches() {
        viewModelScope.launch {
            caches.value = withContext(Dispatchers.IO) { fileOps.torrentCacheBytes() to fileOps.imageCacheBytes() }
        }
    }

    val reclaim: StateFlow<List<ReclaimCandidate>> = combine(analysis, _keepChoices, caches) { analysis, choices, (torrent, images) ->
        CollectionReclaim.candidates(
            analysis?.duplicates.orEmpty(), { keepOf(it, choices) }, analysis?.findings.orEmpty(),
            torrentCacheBytes = torrent, imageCacheBytes = images
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _reclaimTicked = MutableStateFlow<Set<String>>(emptySet())
    val reclaimTicked: StateFlow<Set<String>> = _reclaimTicked.asStateFlow()

    fun toggleReclaim(id: String) = _reclaimTicked.update { if (id in it) it - id else it + id }

    fun toggleAllReclaim() {
        val ids = reclaim.value.map { it.id }.toSet()
        _reclaimTicked.update { if (ids.isNotEmpty() && it.containsAll(ids)) emptySet() else ids }
    }

    /** Deleted for good, not to the trash: in the same volume the trash would free nothing until emptied. */
    fun deleteReclaim(candidates: List<ReclaimCandidate>) = run {
        if (candidates.isEmpty()) return@run null
        var done = 0
        var failed = 0
        var bytes = 0L
        val files = candidates.filter { it.kind == ReclaimKind.DUPLICATE || it.kind == ReclaimKind.EXTRACTED_ARCHIVE || it.kind == ReclaimKind.PARTIAL_DOWNLOAD }
        if (files.isNotEmpty()) {
            val result = fileOps.deleteForGood(files.flatMap { it.files }, files.mapNotNull { it.setFolderUri })
            done += result.done
            failed += result.failed
            bytes += result.bytes
        }
        if (candidates.any { it.kind == ReclaimKind.TORRENT_CACHE }) fileOps.clearTorrentCache().let { bytes += it; done++ }
        if (candidates.any { it.kind == ReclaimKind.IMAGE_CACHE }) fileOps.clearImageCache().let { bytes += it; done++ }
        // One entry for the run, without undo; a single game names it (the caches have no title).
        fileOps.recordFreed(candidates.singleOrNull()?.title.orEmpty(), done, bytes)
        _reclaimTicked.update { it - candidates.map { c -> c.id }.toSet() }
        refreshCaches()
        CollectionMessage.Deleted(done, failed, bytes)
    }

    /** Opens the Library on [consoleId], searching for [title] without its tags when given. */
    fun openInLibrary(consoleId: String?, title: String? = null) {
        val query = title?.let { FileParsingUtils.extractNameAndTags(it).first.ifBlank { it } }
        pendingFilters.submit(LibraryFilterRequest(consoles = setOfNotNull(consoleId), query = query))
    }

    /** Opens the Library filtered by a region or language tag. */
    fun openInLibraryTag(tag: String) = pendingFilters.submit(LibraryFilterRequest(tags = setOf(tag)))

    init {
        refreshCaches()
        viewModelScope.launch { chosenTool.debounce(TOOL_SAVE_DELAY_MS).collect { settingsRepository.setCollectionTool(it.name) } }
    }

    private fun leaf(fileName: String): String =
        FileParsingUtils.decodeUrlEncodedFileName(fileName).replace('\\', '/').substringAfterLast('/').lowercase(Locale.ROOT)

    private companion object {
        const val BIGGEST_COUNT = 10
        const val TOOL_SAVE_DELAY_MS = 600L
        const val RECENT_COUNT = 5
        val ACTIVE_STATUSES = setOf(
            DownloadStatus.QUEUED.name, DownloadStatus.DOWNLOADING.name, DownloadStatus.COPYING.name, DownloadStatus.UNZIPPING.name
        )
    }
}
