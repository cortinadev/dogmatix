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
import com.cortinadev.dogmatix.data.model.UrlEntry
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.repository.SourcesRepository
import com.cortinadev.dogmatix.data.service.ConsoleDownloadPathResolver
import com.cortinadev.dogmatix.data.service.DatabaseScrapingService
import com.cortinadev.dogmatix.data.service.RommClient
import com.cortinadev.dogmatix.data.service.RommPlatform
import com.cortinadev.dogmatix.data.service.DefaultSourcesLoader
import com.cortinadev.dogmatix.data.service.FolderMergeService
import com.cortinadev.dogmatix.data.service.LibraryIndexService
import com.cortinadev.dogmatix.data.state.RescanStateHolder
import com.cortinadev.dogmatix.util.SourcesJson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val databaseScrapingService: DatabaseScrapingService,
    private val defaultSourcesLoader: DefaultSourcesLoader,
    private val rescanStateHolder: RescanStateHolder,
    private val settingsRepository: SettingsRepository,
    private val pathResolver: ConsoleDownloadPathResolver,
    private val folderMergeService: FolderMergeService,
    private val libraryIndexService: LibraryIndexService,
    private val rommClient: RommClient
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
        data object ConfirmImport : Dialog
    }

    private val _dialog = MutableStateFlow<Dialog?>(null)
    val dialog: StateFlow<Dialog?> = _dialog.asStateFlow()

    /** Console whose custom download folder is being picked (SAF result arrives asynchronously). */
    private var pendingPathConsoleId: String? = null

    fun showAddConsoleDialog() { _dialog.value = Dialog.AddConsole }
    fun showEditConsoleDialog(console: Console) { _dialog.value = Dialog.EditConsole(console) }
    fun showAddUrlDialog(consoleId: String) { loadRommPlatforms(); _dialog.value = Dialog.AddUrl(consoleId) }
    fun showEditUrlDialog(consoleId: String, index: Int, entry: UrlEntry) { loadRommPlatforms(); _dialog.value = Dialog.EditUrl(consoleId, index, entry) }
    fun confirmDeleteConsole(console: Console) { _dialog.value = Dialog.ConfirmDeleteConsole(console.id, console.name) }
    fun confirmDeleteUrl(consoleId: String, index: Int, entry: UrlEntry) { _dialog.value = Dialog.ConfirmDeleteUrl(consoleId, index, entry.url) }
    fun confirmImport() { _dialog.value = Dialog.ConfirmImport }
    fun dismissDialog() { _dialog.value = null }

    fun beginPickingDownloadPath(consoleId: String) { pendingPathConsoleId = consoleId }
    fun finishPickingDownloadPath(uri: String) {
        pendingPathConsoleId?.let { updateConsoleDownloadDirectory(it, uri) }
        pendingPathConsoleId = null
    }

    // ---- Editing ------------------------------------------------------------------------------

    fun addConsole(manufacturerId: String, name: String, shortName: String, aliases: List<String>) {
        viewModelScope.launch { sources.addConsole(manufacturerId, name, shortName, aliases) }
    }

    fun addConsoleUnderNewManufacturer(manufacturerName: String, consoleName: String, shortName: String, aliases: List<String>) {
        viewModelScope.launch { sources.addConsole(sources.addManufacturer(manufacturerName), consoleName, shortName, aliases) }
    }

    fun updateConsole(consoleId: String, name: String, shortName: String, aliases: List<String>) {
        viewModelScope.launch { sources.updateConsole(consoleId, name, shortName, aliases) }
    }

    fun deleteConsole(consoleId: String) {
        viewModelScope.launch {
            sources.deleteConsole(consoleId)
            libraryIndexService.refresh()
        }
    }

    fun deleteManufacturer(manufacturerId: String) {
        viewModelScope.launch { sources.deleteManufacturer(manufacturerId) }
    }

    fun addUrl(consoleId: String, url: String, contentType: ContentType) {
        viewModelScope.launch {
            if (!sources.addUrl(consoleId, url, contentType)) {
                rescanStateHolder.setErrorMessage(context.getString(R.string.sources_url_not_added))
            }
        }
    }

    fun updateUrl(consoleId: String, index: Int, url: String, contentType: ContentType) {
        viewModelScope.launch {
            if (!sources.updateUrl(consoleId, index, url, contentType)) {
                rescanStateHolder.setErrorMessage(context.getString(R.string.sources_url_not_added))
            }
        }
    }

    fun deleteUrl(consoleId: String, index: Int) {
        viewModelScope.launch { sources.deleteUrl(consoleId, index) }
    }

    fun setUrlEnabled(consoleId: String, index: Int, enabled: Boolean) {
        viewModelScope.launch { sources.setUrlEnabled(consoleId, index, enabled) }
    }

    // ---- Scraping -----------------------------------------------------------------------------

    /**
     * Called once per app start. First run: seed from the bundled list and scrape it all.
     * Later runs: pull in anything a new app version added to the bundled list and scrape just that.
     */
    fun initializeSources() {
        viewModelScope.launch {
            if (rescanStateHolder.isRescanning.value) return@launch
            if (sources.isEmpty()) {
                defaultSourcesLoader.loadDefaultSourcesToDatabase()
                scrapeAll(R.string.sources_scrape_start)
            } else {
                val added = defaultSourcesLoader.syncNewDefaults()
                if (added.isEmpty()) return@launch
                withRescanState {
                    added.forEachIndexed { i, (entity, newUrls) ->
                        rescanStateHolder.setProgressMessage(
                            context.getString(R.string.sources_processing_console, i + 1, added.size, entity.name))
                        scrapeConsole(Console(entity.id, entity.name, newUrls), entity.manufacturerId)
                    }
                }
            }
        }
    }

    fun rescanAllSources() {
        if (rescanStateHolder.isRescanning.value) return
        viewModelScope.launch {
            databaseScrapingService.clearAllData()
            scrapeAll(R.string.sources_rescan_start)
        }
    }

    fun refreshConsole(consoleId: String) {
        if (rescanStateHolder.isRescanning.value) return
        viewModelScope.launch {
            val entity = sources.getConsoleEntity(consoleId) ?: return@launch
            withRescanState {
                rescanStateHolder.setProgressMessage(context.getString(R.string.sources_refreshing_console, entity.name))
                databaseScrapingService.clearConsoleData(consoleId)
                scrapeConsole(Console(entity.id, entity.name, SourcesJson.parseUrlEntries(entity.urls)), entity.manufacturerId)
            }
        }
    }

    private suspend fun scrapeAll(startMessage: Int) = withRescanState {
        val current = manufacturers.first()
        val total = current.sumOf { it.consoles.size }
        var processed = 0
        rescanStateHolder.setProgressMessage(context.getString(startMessage, total))
        current.forEach { manufacturer ->
            manufacturer.consoles.forEach { console ->
                processed++
                rescanStateHolder.setProgressMessage(
                    context.getString(R.string.sources_processing_console, processed, total, console.name))
                scrapeConsole(console, manufacturer.id)
            }
        }
    }

    private suspend fun scrapeConsole(console: Console, manufacturerId: String) {
        databaseScrapingService.scrapeManufacturer(
            Manufacturer(manufacturerId, manufacturerId, listOf(console)),
            onScrapeError = { rescanStateHolder.setErrorMessage(it) }
        )
    }

    private suspend fun withRescanState(block: suspend () -> Unit) {
        rescanStateHolder.setRescanning(true)
        try {
            block()
        } finally {
            rescanStateHolder.setRescanning(false)
            rescanStateHolder.clearProgressMessage()
            rescanStateHolder.clearTorrentFetchProgress()
        }
    }

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
            scrapeAll(R.string.sources_rescan_start)
            libraryIndexService.refresh()
        }
    }
}
