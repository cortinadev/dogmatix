package com.cortinadev.dogmatix.ui.screens.sources

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.Console
import com.cortinadev.dogmatix.data.model.ContentType
import com.cortinadev.dogmatix.data.model.Manufacturer
import com.cortinadev.dogmatix.data.model.ResolvedDownloadPath
import com.cortinadev.dogmatix.data.model.ScrapeRecipe
import com.cortinadev.dogmatix.data.model.UrlEntry
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.repository.SourcesRepository
import com.cortinadev.dogmatix.data.service.ConsoleDownloadPathResolver
import com.cortinadev.dogmatix.data.service.DatabaseScrapingService
import com.cortinadev.dogmatix.data.service.ScanReport
import com.cortinadev.dogmatix.data.service.ScanSummary
import com.cortinadev.dogmatix.data.service.ScrapePreviewRow
import com.cortinadev.dogmatix.data.service.SourceScanService
import com.cortinadev.dogmatix.data.service.RommClient
import com.cortinadev.dogmatix.data.service.RommPlatform
import com.cortinadev.dogmatix.data.service.FolderMergeService
import com.cortinadev.dogmatix.data.service.LibraryIndexService
import com.cortinadev.dogmatix.data.state.RescanStateHolder
import com.cortinadev.dogmatix.data.state.ScanProgress
import com.cortinadev.dogmatix.util.RecipeProblem
import com.cortinadev.dogmatix.util.ScanFailure
import com.cortinadev.dogmatix.util.ScanRetry
import com.cortinadev.dogmatix.util.ScrapeRecipes
import com.cortinadev.dogmatix.util.SourcesJson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SourcesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sources: SourcesRepository,
    private val sourceScanService: SourceScanService,
    private val rescanStateHolder: RescanStateHolder,
    private val settingsRepository: SettingsRepository,
    private val pathResolver: ConsoleDownloadPathResolver,
    private val folderMergeService: FolderMergeService,
    private val libraryIndexService: LibraryIndexService,
    private val rommClient: RommClient,
    private val scraping: DatabaseScrapingService
) : ViewModel() {

    /** RomM platforms offered in the source dialog; empty when RomM is not configured or unreachable. */
    private val _rommPlatforms = MutableStateFlow<List<RommPlatform>>(emptyList())
    val rommPlatforms: StateFlow<List<RommPlatform>> = _rommPlatforms.asStateFlow()

    private fun loadRommPlatforms() {
        viewModelScope.launch {
            _rommPlatforms.value = runCatching { rommClient.platforms().sortedBy { it.label.lowercase() } }.getOrDefault(emptyList())
        }
    }

    val manufacturers: Flow<List<Manufacturer>> = sources.manufacturers

    val isRescanning: StateFlow<Boolean> = rescanStateHolder.isRescanning
    val rescanErrorMessage: StateFlow<String?> = rescanStateHolder.errorMessage

    val downloadDirectory: StateFlow<String> = settingsRepository.downloadDirectory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")

    // ---- Download paths & folder merge --------------------------------------------------------

    /** Bumped after a folder merge so the resolved paths re-read what is on disk. */
    private val diskVersion = MutableStateFlow(0)

    /** Consoles whose duplicate-folder notice the user chose to leave alone (this session). */
    private val _ignoredMerges = MutableStateFlow<Set<String>>(emptySet())

    /** Effective download location per console (custom > detected folder > default), always available. */
    val consoleDownloadPaths: StateFlow<Map<String, ResolvedDownloadPath>> = combine(
        sources.manufacturers.map { ms -> ms.flatMap { it.consoles }.map { it.id } },
        settingsRepository.downloadDirectory,
        settingsRepository.separateByConsole,
        settingsRepository.consoleDownloadDirectories,
        diskVersion
    ) { consoleIds, downloadDir, separate, customDirs, _ ->
        pathResolver.resolveAll(consoleIds, downloadDir, separate, customDirs)
    }.combine(_ignoredMerges) { paths, ignored ->
        paths.mapValues { (id, path) -> if (id in ignored) path.copy(alternatives = emptyList()) else path }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyMap())

    private val _mergeConsoleId = MutableStateFlow<String?>(null)
    val mergeConsoleId: StateFlow<String?> = _mergeConsoleId.asStateFlow()
    private val _mergeInProgress = MutableStateFlow(false)
    val mergeInProgress: StateFlow<Boolean> = _mergeInProgress.asStateFlow()
    private val _mergeResult = MutableStateFlow<FolderMergeService.Result?>(null)
    val mergeResult: StateFlow<FolderMergeService.Result?> = _mergeResult.asStateFlow()

    fun showMergeDialog(consoleId: String) {
        _mergeResult.value = null
        _mergeConsoleId.value = consoleId
    }

    fun hideMergeDialog() {
        if (_mergeInProgress.value) return
        _mergeConsoleId.value = null
        _mergeResult.value = null
    }

    fun keepFoldersAsIs(consoleId: String) {
        _ignoredMerges.value += consoleId
        hideMergeDialog()
    }

    fun mergeFolders(consoleId: String, targetFolder: String) {
        val path = consoleDownloadPaths.value[consoleId] ?: return
        val folders = listOf(path.subPath) + path.alternatives
        if (targetFolder !in folders) return
        viewModelScope.launch {
            _mergeInProgress.value = true
            try {
                _mergeResult.value = folderMergeService.merge(
                    rootUri = settingsRepository.downloadDirectory.first(),
                    targetFolder = targetFolder,
                    sourceFolders = folders - targetFolder
                )
            } finally {
                _mergeInProgress.value = false
                diskVersion.value++
                libraryIndexService.refresh()
            }
        }
    }

    fun updateDownloadDirectory(path: String) {
        viewModelScope.launch { settingsRepository.updateDownloadDirectory(path) }
    }

    fun updateConsoleDownloadDirectory(consoleId: String, path: String) {
        viewModelScope.launch { settingsRepository.updateConsoleDownloadDirectory(consoleId, path) }
    }

    // ---- Dialog state -------------------------------------------------------------------------

    /** Which dialog is open; only one at a time so B always closes the top layer. */
    sealed interface Dialog {
        data object AddConsole : Dialog
        data class EditConsole(val console: Console) : Dialog
        data class AddUrl(val consoleId: String) : Dialog
        data class EditUrl(val consoleId: String, val index: Int, val entry: UrlEntry) : Dialog
        data class ConfirmDeleteConsole(val consoleId: String, val name: String) : Dialog
        data class ConfirmDeleteUrl(val consoleId: String, val index: Int, val url: String) : Dialog
        data class ConfirmDeleteManufacturer(val manufacturerId: String, val name: String) : Dialog
        data object ConfirmImport : Dialog
    }

    private val _dialog = MutableStateFlow<Dialog?>(null)
    val dialog: StateFlow<Dialog?> = _dialog.asStateFlow()

    /** Console whose custom download folder is being picked (SAF result arrives asynchronously). */
    private var pendingPathConsoleId: String? = null

    fun showAddConsoleDialog() { _dialog.value = Dialog.AddConsole }
    fun showEditConsoleDialog(console: Console) { _dialog.value = Dialog.EditConsole(console) }
    fun showAddUrlDialog(consoleId: String) { loadRommPlatforms(); resetScrapeTest(); _dialog.value = Dialog.AddUrl(consoleId) }
    fun showEditUrlDialog(consoleId: String, index: Int, entry: UrlEntry) { loadRommPlatforms(); resetScrapeTest(); _dialog.value = Dialog.EditUrl(consoleId, index, entry) }
    fun confirmDeleteConsole(console: Console) { _dialog.value = Dialog.ConfirmDeleteConsole(console.id, console.name) }
    fun confirmDeleteUrl(consoleId: String, index: Int, entry: UrlEntry) { _dialog.value = Dialog.ConfirmDeleteUrl(consoleId, index, entry.url) }
    fun confirmDeleteManufacturer(manufacturer: Manufacturer) { _dialog.value = Dialog.ConfirmDeleteManufacturer(manufacturer.id, manufacturer.name) }
    fun confirmImport() { _dialog.value = Dialog.ConfirmImport }
    fun dismissDialog() { resetScrapeTest(); _dialog.value = null }

    fun beginPickingDownloadPath(consoleId: String) { pendingPathConsoleId = consoleId }
    fun finishPickingDownloadPath(uri: String) {
        pendingPathConsoleId?.let { updateConsoleDownloadDirectory(it, uri) }
        pendingPathConsoleId = null
    }

    // ---- Editing ------------------------------------------------------------------------------

    /** [key] is the console part of the id for a predefined console; empty = derived from [name]. */
    fun addConsole(manufacturerId: String, name: String, shortName: String, aliases: List<String>, color: String, key: String = "") {
        viewModelScope.launch { sources.addConsole(manufacturerId, name, shortName, aliases, color, key) }
    }

    fun addConsoleUnderNewManufacturer(manufacturerName: String, consoleName: String, shortName: String, aliases: List<String>, color: String, key: String = "") {
        viewModelScope.launch { sources.addConsole(sources.addManufacturer(manufacturerName), consoleName, shortName, aliases, color, key) }
    }

    fun updateConsole(consoleId: String, name: String, shortName: String, aliases: List<String>, color: String) {
        viewModelScope.launch { sources.updateConsole(consoleId, name, shortName, aliases, color) }
    }

    fun deleteConsole(consoleId: String) {
        viewModelScope.launch {
            sources.deleteConsole(consoleId)
            libraryIndexService.refresh()
        }
    }

    /**
     * Only a manufacturer left without consoles goes from this screen (consoles are deleted one by
     * one, with their own confirmation): one that got a console meanwhile is left alone.
     */
    fun deleteManufacturer(manufacturerId: String) {
        viewModelScope.launch {
            val manufacturer = sources.manufacturers.first().firstOrNull { it.id == manufacturerId } ?: return@launch
            if (manufacturer.consoles.isEmpty()) sources.deleteManufacturer(manufacturerId)
        }
    }

    fun addUrl(consoleId: String, url: String, contentType: ContentType, mirrors: List<String> = emptyList(), scrape: ScrapeRecipe? = null) {
        viewModelScope.launch {
            if (!sources.addUrl(consoleId, url, contentType, mirrors = mirrors, scrape = scrape)) {
                rescanStateHolder.setErrorMessage(context.getString(R.string.sources_url_not_added))
            }
        }
    }

    fun updateUrl(consoleId: String, index: Int, url: String, contentType: ContentType, mirrors: List<String> = emptyList(), scrape: ScrapeRecipe? = null) {
        viewModelScope.launch {
            if (!sources.updateUrl(consoleId, index, url, contentType, mirrors, scrape)) {
                rescanStateHolder.setErrorMessage(context.getString(R.string.sources_url_not_added))
            }
        }
    }

    // ---- Page recipe test ("Test" in the source dialog) ----------------------------------------

    private val _scrapeTest = MutableStateFlow<ScrapeTestState>(ScrapeTestState.Idle)
    val scrapeTest: StateFlow<ScrapeTestState> = _scrapeTest.asStateFlow()
    private var scrapeTestJob: Job? = null

    /** Reads the first page of [url] with [recipe]; a new test (or any edit) drops the one under way. */
    fun testScrape(url: String, recipe: ScrapeRecipe) {
        scrapeTestJob?.cancel()
        ScrapeRecipes.validate(recipe, url)?.let {
            _scrapeTest.value = ScrapeTestState.Error(it, null, null, null)
            return
        }
        _scrapeTest.value = ScrapeTestState.Running
        scrapeTestJob = viewModelScope.launch {
            _scrapeTest.value = try {
                val preview = scraping.preview(url, recipe)
                ScrapeTestState.Result(preview.matched, preview.total, preview.rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ScrapeTestState.Error(null, ScanRetry.classify(e), ScanRetry.httpCodeOf(e), e.message)
            }
        }
    }

    fun resetScrapeTest() {
        scrapeTestJob?.cancel()
        scrapeTestJob = null
        _scrapeTest.value = ScrapeTestState.Idle
    }

    fun deleteUrl(consoleId: String, index: Int) {
        viewModelScope.launch { sources.deleteUrl(consoleId, index) }
    }

    /** Off: the source's games leave the library at once. On: the source is read again (after a scan under way, if any). */
    fun setUrlEnabled(consoleId: String, index: Int, enabled: Boolean) {
        viewModelScope.launch {
            val url = sources.setUrlEnabled(consoleId, index, enabled) ?: return@launch
            if (enabled) sourceScanService.scanSource(consoleId, url)
        }
    }

    // ---- Scraping -----------------------------------------------------------------------------
    // The scans themselves run in SourceScanService (application scope), so leaving this screen
    // never cancels one; this only forwards the requests and exposes their progress and report.

    /** Called once per app start: first-run scan, or just the sources a new app version bundled. */
    fun initializeSources() {
        sourceScanService.initialize()
    }

    /** "Rescan all" (incremental) or, with [force], "Full rescan" (every source read again). */
    fun rescanAllSources(force: Boolean = false) {
        if (rescanStateHolder.isRescanning.value) return
        sourceScanService.scanAll(force)
    }

    /** While a scan runs, the console is read right after it (the button is not greyed out like the global ones). */
    fun refreshConsole(consoleId: String) {
        sourceScanService.scanConsole(consoleId)
    }

    fun retryFailedSources() {
        if (rescanStateHolder.isRescanning.value) return
        sourceScanService.retryFailed()
    }

    val scanProgress: StateFlow<ScanProgress?> = rescanStateHolder.progress
    /** What is being read: a torrent's or RomM's own step when there is one, else the console. */
    val scanMessage: StateFlow<String> = combine(rescanStateHolder.torrentFetchProgress, rescanStateHolder.progressMessage) { step, console ->
        step.ifEmpty { console }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** The latest scan, source by source ("Last scan"). */
    val lastReport: StateFlow<ScanReport?> = sourceScanService.lastReport
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** A scan started from the app ended: the screen collects this only while visible, to show the report. */
    val scanFinished: SharedFlow<ScanSummary> = sourceScanService.finished

    fun clearRescanError() {
        rescanStateHolder.setErrorMessage(null)
    }

    // ---- Export / import ----------------------------------------------------------------------

    /** Writes the export file and hands back a shareable content URI (FileProvider). */
    fun exportSources(onReady: (Uri) -> Unit) {
        viewModelScope.launch {
            try {
                val file = sources.exportToFile()
                onReady(FileProvider.getUriForFile(context, "${context.packageName}.provider", file))
            } catch (e: Exception) {
                rescanStateHolder.setErrorMessage(context.getString(R.string.sources_export_failed, e.message ?: ""))
            }
        }
    }

    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()
    fun clearImportMessage() { _importMessage.value = null }

    /** Replaces all sources with the picked document, then rescans everything and re-indexes the library. */
    fun importSources(uri: String) {
        if (rescanStateHolder.isRescanning.value) return
        viewModelScope.launch {
            val count = try {
                sources.importFromUri(uri)
            } catch (e: Exception) {
                _importMessage.value = context.getString(R.string.sources_import_failed, e.message ?: "")
                return@launch
            }
            _importMessage.value = context.getString(R.string.sources_import_done, count)
            sourceScanService.scanAll(force = true).join()
            libraryIndexService.refresh()
        }
    }
}

/** Where "Test" in the source dialog stands. */
sealed interface ScrapeTestState {
    data object Idle : ScrapeTestState
    data object Running : ScrapeTestState
    /** [matched] elements on the first page, [total] of them with a file link; [sample] = the first few. */
    data class Result(val matched: Int, val total: Int, val sample: List<ScrapePreviewRow>) : ScrapeTestState
    /** [problem] = the recipe itself is wrong; otherwise the page could not be read. */
    data class Error(val problem: RecipeProblem?, val failure: ScanFailure?, val httpCode: Int?, val detail: String?) : ScrapeTestState
}
