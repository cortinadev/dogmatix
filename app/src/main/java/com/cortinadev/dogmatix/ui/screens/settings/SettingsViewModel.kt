package com.cortinadev.dogmatix.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.repository.DownloadableFileRepository
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.model.DebridProvider
import com.cortinadev.dogmatix.data.model.SeedingMode
import com.cortinadev.dogmatix.data.model.PreferredRegion
import com.cortinadev.dogmatix.util.AutoRetryPolicy
import com.cortinadev.dogmatix.data.service.DaijishoConfigService
import com.cortinadev.dogmatix.data.service.DebridClient
import com.cortinadev.dogmatix.data.service.EsdeConfigService
import com.cortinadev.dogmatix.data.service.FrontendSetupException
import com.cortinadev.dogmatix.data.service.IisuConfigService
import com.cortinadev.dogmatix.data.service.FrontendShortcutService
import com.cortinadev.dogmatix.data.service.EsdeFavouriteSyncService
import com.cortinadev.dogmatix.data.service.FrontendCheckService
import com.cortinadev.dogmatix.ui.screens.settings.components.FrontendCheckUi
import com.cortinadev.dogmatix.util.FrontendCheck
import kotlinx.coroutines.flow.update
import com.cortinadev.dogmatix.data.service.RealDebridClient
import com.cortinadev.dogmatix.data.service.TorBoxClient
import com.cortinadev.dogmatix.ui.common.GamepadLayout
import com.cortinadev.dogmatix.ui.common.executeWithToast
import com.cortinadev.dogmatix.util.Constants
import com.cortinadev.dogmatix.util.TagColorKind
import com.cortinadev.dogmatix.util.ToastUtil
import com.cortinadev.dogmatix.util.TorrentConstants
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import androidx.compose.ui.graphics.Color
import com.cortinadev.dogmatix.ui.theme.AccentPresets
import com.cortinadev.dogmatix.ui.theme.ThemeMode
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject

data class SettingsUiState(
    val downloadDirectory: String = "",
    val separateByConsole: Boolean = true,
    val limitSpeed: Float = Float.POSITIVE_INFINITY,
    val autoUnzip: Boolean = true,
    val concurrentDownloads: Int = 3,
    val metadataTimeoutSeconds: Int = TorrentConstants.DEFAULT_METADATA_TIMEOUT_S,
    /** Rows a library search returns before "Load more"; 0 = no limit. */
    val maxSearchResults: Int = Constants.DEFAULT_MAX_SEARCH_RESULTS,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Only the names drawn in the gamepad legend; it never moves an action. */
    val gamepadLayout: GamepadLayout = GamepadLayout.XBOX,
    /** The pad reports its face buttons the other way round: act on A/B and X/Y swapped. */
    val swapFaceButtons: Boolean = false,
    val accent: Color = AccentPresets.default,
    val favoriteLanguages: Set<String> = emptySet(),
    val debridProvider: DebridProvider = DebridProvider.NONE,
    /** How long a finished torrent keeps uploading; OFF releases it right away. */
    val seedingMode: SeedingMode = SeedingMode.OFF,
    val torboxApiKey: String = "",
    val realDebridApiKey: String = "",
    val rommUrl: String = "",
    val esdeDirectory: String = "",
    val iisuDirectory: String = "",
    /** Square covers in front of the library rows. */
    val showListCovers: Boolean = true,
    /** Colours picked per tag group; a missing group uses its default. */
    val tagColors: Map<TagColorKind, Int> = emptyMap(),
    val isLoading: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val fileRepository: DownloadableFileRepository,
    private val torBoxClient: TorBoxClient,
    private val realDebridClient: RealDebridClient,
    private val frontendShortcuts: FrontendShortcutService,
    private val esdeConfigService: EsdeConfigService,
    private val iisuConfigService: IisuConfigService,
    private val daijishoConfigService: DaijishoConfigService,
    // frontends
    private val frontendCheckService: FrontendCheckService,
    private val esdeFavouriteSync: EsdeFavouriteSyncService
) : ViewModel() {

    /** Non-null while the Daijishō sheet with the values to type is open. */
    private val _daijishoSetup = MutableStateFlow<DaijishoConfigService.Setup?>(null)
    val daijishoSetup: StateFlow<DaijishoConfigService.Setup?> = _daijishoSetup.asStateFlow()

    /** Every language tag present in the library, so favorites can be picked from real data. */
    private val _availableLanguages = MutableStateFlow<List<String>>(emptyList())
    val availableLanguages: StateFlow<List<String>> = _availableLanguages.asStateFlow()

    fun loadAvailableLanguages() {
        viewModelScope.launch {
            _availableLanguages.value = runCatching { fileRepository.getCategorizedTags("*").languages.tags }
                .getOrDefault(emptyList())
        }
    }

    /** Null until DataStore answers; MainActivity shows nothing meanwhile to avoid a flash. */
    val onboardingDone: StateFlow<Boolean?> = repository.onboardingDone
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val uiState: StateFlow<SettingsUiState> = combine(
        repository.downloadDirectory,
        repository.separateByConsole,
        repository.limitSpeed,
        repository.autoUnzip,
        repository.concurrentDownloads,
        repository.themeMode,
        repository.accentColor,
        repository.favoriteLanguages,
        repository.debridProvider,
        repository.torboxApiKey,
        repository.realDebridApiKey,
        repository.rommUrl,
        repository.metadataTimeoutSeconds,
        repository.esdeDirectory,
        repository.maxSearchResults,
        repository.iisuDirectory,
        repository.gamepadLayout,
        repository.swapFaceButtons,
        repository.seedingMode,
        repository.showListCovers,
        repository.tagColors
    ) { values: Array<Any> ->
        SettingsUiState(
            downloadDirectory = values[0] as String,
            separateByConsole = values[1] as Boolean,
            limitSpeed = values[2] as Float,
            autoUnzip = values[3] as Boolean,
            concurrentDownloads = values[4] as Int,
            themeMode = ThemeMode.fromName(values[5] as String),
            accent = AccentPresets.fromHex(values[6] as String),
            favoriteLanguages = (values[7] as Set<*>).filterIsInstance<String>().toSet(),
            debridProvider = values[8] as DebridProvider,
            torboxApiKey = values[9] as String,
            realDebridApiKey = values[10] as String,
            rommUrl = values[11] as String,
            metadataTimeoutSeconds = values[12] as Int,
            esdeDirectory = values[13] as String,
            maxSearchResults = values[14] as Int,
            iisuDirectory = values[15] as String,
            gamepadLayout = GamepadLayout.fromName(values[16] as String),
            swapFaceButtons = values[17] as Boolean,
            seedingMode = values[18] as SeedingMode,
            showListCovers = values[19] as Boolean,
            tagColors = (values[20] as Map<*, *>).entries
                .mapNotNull { (k, v) -> (k as? TagColorKind)?.let { kind -> (v as? Int)?.let { kind to it } } }
                .toMap()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    fun onDebridProviderChanged(context: Context, provider: DebridProvider) {
        executeWithToast(context, "SettingsViewModel") { repository.setDebridProvider(provider) }
    }

    fun onShowListCoversChanged(context: Context, enabled: Boolean) {
        executeWithToast(context, "SettingsViewModel") { repository.setShowListCovers(enabled) }
    }

    /** [argb] null = back to the default colour of [kind]. */
    fun onTagColorChanged(context: Context, kind: TagColorKind, argb: Int?) {
        executeWithToast(context, "SettingsViewModel") { repository.setTagColor(kind, argb) }
    }

    fun onResetTagColors(context: Context) {
        executeWithToast(context, "SettingsViewModel") { repository.resetTagColors() }
    }

    fun onSeedingModeChanged(context: Context, mode: SeedingMode) {
        executeWithToast(context, "SettingsViewModel") { repository.setSeedingMode(mode) }
    }

    fun onDebridApiKeyChanged(context: Context, provider: DebridProvider, key: String) {
        executeWithToast(context, "SettingsViewModel") {
            when (provider) {
                DebridProvider.TORBOX -> repository.setTorboxApiKey(key)
                DebridProvider.REAL_DEBRID -> repository.setRealDebridApiKey(key)
                DebridProvider.NONE -> Unit
            }
        }
    }

    /** Calls the debrid service with [key]; the result (account or error) is reported through a toast. */
    fun testDebridApiKey(context: Context, provider: DebridProvider, key: String) {
        val client: DebridClient = when (provider) {
            DebridProvider.TORBOX -> torBoxClient
            DebridProvider.REAL_DEBRID -> realDebridClient
            DebridProvider.NONE -> return
        }
        viewModelScope.launch {
            runCatching { client.validateKey(key) }
                .onSuccess { ToastUtil.showSuccess(context, context.getString(R.string.debrid_key_valid, provider.label, it)) }
                .onFailure { ToastUtil.showError(context, context.getString(R.string.debrid_key_invalid, provider.label, it.message ?: "")) }
        }
    }

    /** Saves the ES-DE directory picked via SAF and runs the configuration right away. */
    fun onEsdeDirPicked(context: Context, uri: String) {
        viewModelScope.launch {
            runCatching { repository.setEsdeDirectory(uri) }
            configureEsde(context)
            recheckFrontendsIfOpen()
        }
    }

    /** One-button ES-DE setup: shortcuts + find rules + system overrides + gamelists + banner. */
    fun onConfigureEsde(context: Context) {
        viewModelScope.launch { configureEsde(context) }
    }

    private suspend fun configureEsde(context: Context) {
        runCatching { esdeConfigService.configure(repository.esdeDirectory.first()) }
            .onSuccess { result ->
                ToastUtil.showSuccess(
                    context,
                    context.getString(R.string.esde_configured, result.systems, result.shortcutsWritten)
                )
                if (result.foldersWithoutSystem > 0) {
                    ToastUtil.showInfo(context, context.getString(R.string.esde_folders_skipped, result.foldersWithoutSystem))
                }
                if (result.shortcutsFailed > 0) {
                    ToastUtil.showError(
                        context,
                        context.getString(R.string.frontend_shortcuts_partial, result.shortcutsWritten, result.shortcutsFailed)
                    )
                }
            }
            .onFailure { error ->
                ToastUtil.showError(context, frontendSetupMessage(context, error) {
                    if (it is EsdeConfigService.EsdeNotInstalledException) R.string.esde_not_installed else null
                })
            }
    }

    /** Saves the iiSU directory picked via SAF and runs the configuration right away. */
    fun onIisuDirPicked(context: Context, uri: String) {
        viewModelScope.launch {
            runCatching { repository.setIisuDirectory(uri) }
            configureIisu(context)
            recheckFrontendsIfOpen()
        }
    }

    /** One-button iiSU setup: shortcuts + a Dogmatix emulator in its `emuladores.json`. */
    fun onConfigureIisu(context: Context) {
        viewModelScope.launch { configureIisu(context) }
    }

    private suspend fun configureIisu(context: Context) {
        runCatching { iisuConfigService.configure(repository.iisuDirectory.first()) }
            .onSuccess { result ->
                ToastUtil.showSuccess(
                    context,
                    context.getString(R.string.iisu_configured, result.consoles, result.shortcutsWritten)
                )
                if (result.foldersWithoutConsole > 0) {
                    ToastUtil.showInfo(context, context.getString(R.string.iisu_folders_skipped, result.foldersWithoutConsole))
                }
                if (result.shortcutsFailed > 0) {
                    ToastUtil.showError(
                        context,
                        context.getString(R.string.frontend_shortcuts_partial, result.shortcutsWritten, result.shortcutsFailed)
                    )
                }
            }
            .onFailure { error ->
                ToastUtil.showError(context, frontendSetupMessage(context, error) {
                    if (it is IisuConfigService.IisuNotInstalledException) R.string.iisu_not_installed else null
                })
            }
    }

    /** Message for a failed frontend setup; [special] maps the service's own sentinel first. */
    private fun frontendSetupMessage(context: Context, error: Throwable, special: (Throwable) -> Int?): String {
        special(error)?.let { return context.getString(it) }
        return when (error) {
            is FrontendSetupException -> error.userMessage(context)
            is IOException -> context.getString(R.string.frontend_write_failed)
            else -> error.message ?: context.getString(R.string.msg_operation_failed)
        }
    }

    /** Deploys the shortcuts and opens the sheet with the values to type into Daijishō. */
    fun onPrepareDaijisho(context: Context) {
        viewModelScope.launch {
            runCatching { daijishoConfigService.prepare() }
                .onSuccess { _daijishoSetup.value = it }
                .onFailure { ToastUtil.showError(context, it.message ?: context.getString(R.string.msg_operation_failed)) }
        }
    }

    fun onDaijishoSetupDismissed() {
        _daijishoSetup.value = null
    }

    /** Drops a `.dgmtx` shortcut into every console's download folder; the result comes as a toast. */
    fun onDeployFrontendShortcuts(context: Context) {
        viewModelScope.launch { deployFrontendShortcuts(context) }
    }

    private suspend fun deployFrontendShortcuts(context: Context) {
        runCatching { frontendShortcuts.deployShortcuts() }
            .onSuccess { result ->
                when {
                    result.written == 0 && result.failed == 0 ->
                        ToastUtil.showInfo(context, context.getString(R.string.frontend_shortcuts_none))
                    result.failed == 0 ->
                        ToastUtil.showSuccess(context, context.getString(R.string.frontend_shortcuts_done, result.written))
                    else ->
                        ToastUtil.showError(
                            context,
                            context.getString(R.string.frontend_shortcuts_partial, result.written, result.failed)
                        )
                }
            }
            .onFailure {
                ToastUtil.showError(context, it.message ?: context.getString(R.string.frontend_shortcuts_none))
            }
    }

    fun onDownloadDirChanged(context: Context, path: String) {
        executeWithToast(context, "SettingsViewModel") {
            repository.updateDownloadDirectory(path)
        }
    }

    fun onSeparateByConsoleChanged(context: Context, enabled: Boolean) {
        executeWithToast(context, "SettingsViewModel") {
            repository.setSeparateByConsole(enabled)
        }
    }

    fun onLimitSpeedChanged(context: Context, limit: Float) {
        executeWithToast(context, "SettingsViewModel") {
            repository.setLimitSpeed(limit)
        }
    }

    fun onAutoUnzipChanged(context: Context, enabled: Boolean) {
        executeWithToast(context, "SettingsViewModel") {
            repository.setAutoUnzip(enabled)
        }
    }

    fun onMetadataTimeoutChanged(context: Context, seconds: Int) {
        executeWithToast(context, "SettingsViewModel") { repository.setMetadataTimeoutSeconds(seconds) }
    }

    fun onMaxSearchResultsChanged(context: Context, max: Int) {
        executeWithToast(context, "SettingsViewModel") { repository.setMaxSearchResults(max) }
    }

    fun onConcurrentDownloadsChanged(context: Context, count: Int) {
        executeWithToast(context, "SettingsViewModel") {
            repository.setConcurrentDownloads(count)
        }
    }

    fun onThemeModeChanged(context: Context, mode: ThemeMode) {
        executeWithToast(context, "SettingsViewModel") {
            repository.setThemeMode(mode.name)
        }
    }

    fun onGamepadLayoutChanged(context: Context, layout: GamepadLayout) {
        executeWithToast(context, "SettingsViewModel") {
            repository.setGamepadLayout(layout.name)
        }
    }

    fun onSwapFaceButtonsChanged(context: Context, enabled: Boolean) {
        executeWithToast(context, "SettingsViewModel") {
            repository.setSwapFaceButtons(enabled)
        }
    }

    fun onFavoriteLanguagesChanged(context: Context, tags: Set<String>) {
        executeWithToast(context, "SettingsViewModel") {
            repository.setFavoriteLanguages(tags)
        }
    }

    fun onAccentChanged(context: Context, accent: Color) {
        executeWithToast(context, "SettingsViewModel") {
            repository.setAccentColor(AccentPresets.toHex(accent))
        }
    }

    // Download retries, per-server limit, multi-disc playlists and the preferred region
    val features: StateFlow<FeatureSettingsUi> = combine(
        repository.autoRetryAttempts,
        repository.perHostDownloads,
        repository.multiDiscPlaylists,
        repository.preferredRegion
    ) { retries, perHost, multiDisc, region -> FeatureSettingsUi(retries, perHost, multiDisc, region) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FeatureSettingsUi())

    fun onAutoRetryChanged(context: Context, attempts: Int) =
        executeWithToast(context, "SettingsViewModel") { repository.setAutoRetryAttempts(attempts) }

    fun onPerHostDownloadsChanged(context: Context, max: Int) =
        executeWithToast(context, "SettingsViewModel") { repository.setPerHostDownloads(max) }

    fun onMultiDiscPlaylistsChanged(context: Context, enabled: Boolean) =
        executeWithToast(context, "SettingsViewModel") { repository.setMultiDiscPlaylists(enabled) }

    fun onPreferredRegionChanged(context: Context, region: PreferredRegion) =
        executeWithToast(context, "SettingsViewModel") { repository.setPreferredRegion(region) }

    // dl-cola
    val batchNotification: StateFlow<Boolean> = repository.batchNotification
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun onBatchNotificationChanged(context: Context, enabled: Boolean) =
        executeWithToast(context, "SettingsViewModel") { repository.setBatchNotification(enabled) }

    // frontends
    /** Non-null while "Check frontends" is open. */
    private val _frontendCheck = MutableStateFlow<FrontendCheckUi?>(null)
    val frontendCheck: StateFlow<FrontendCheckUi?> = _frontendCheck.asStateFlow()

    fun onCheckFrontends() {
        _frontendCheck.value = FrontendCheckUi(running = true)
        viewModelScope.launch { runFrontendCheck() }
    }

    fun onRecheckFrontends() {
        _frontendCheck.update { it?.copy(running = true) }
        viewModelScope.launch { runFrontendCheck() }
    }

    fun onFrontendCheckDismissed() {
        _frontendCheck.value = null
    }

    /** Runs the setup behind a warning, then checks again. Picking a folder is the screen's (it owns the pickers). */
    fun onFrontendRepair(context: Context, repair: FrontendCheck.Repair) {
        _frontendCheck.update { it?.copy(running = true) }
        viewModelScope.launch {
            when (repair) {
                FrontendCheck.Repair.CONFIGURE_ESDE -> configureEsde(context)
                FrontendCheck.Repair.CONFIGURE_IISU -> configureIisu(context)
                FrontendCheck.Repair.DEPLOY_SHORTCUTS -> deployFrontendShortcuts(context)
                FrontendCheck.Repair.PICK_ESDE_FOLDER, FrontendCheck.Repair.PICK_IISU_FOLDER, FrontendCheck.Repair.NONE -> Unit
            }
            recheckFrontendsIfOpen()
        }
    }

    private suspend fun recheckFrontendsIfOpen() {
        if (_frontendCheck.value != null) runFrontendCheck()
    }

    private suspend fun runFrontendCheck() {
        val findings = runCatching { frontendCheckService.check() }.getOrDefault(emptyList())
        _frontendCheck.update { it?.copy(running = false, findings = findings) }
    }

    /** Two-way favourites sync with ES-DE; the same one that runs after launch, reported in a toast. */
    fun onSyncEsdeFavourites(context: Context) {
        viewModelScope.launch {
            runCatching { esdeFavouriteSync.sync() }
                .onSuccess { result ->
                    when {
                        result == null -> ToastUtil.showInfo(context, context.getString(R.string.esde_favourites_not_set_up))
                        result.changedHere + result.changedInEsde == 0 ->
                            ToastUtil.showInfo(context, context.getString(R.string.esde_favourites_in_sync))
                        else -> ToastUtil.showSuccess(
                            context,
                            context.getString(R.string.esde_favourites_synced, result.changedHere, result.changedInEsde)
                        )
                    }
                }
                .onFailure { ToastUtil.showError(context, frontendSetupMessage(context, it) { null }) }
        }
    }
}

/** Download and version settings, kept out of [SettingsUiState]'s long combine. */
data class FeatureSettingsUi(
    val autoRetryAttempts: Int = AutoRetryPolicy.DEFAULT_ATTEMPTS,
    val perHostDownloads: Int = 0,
    val multiDiscPlaylists: Boolean = true,
    val preferredRegion: PreferredRegion = PreferredRegion.forLocale()
)
