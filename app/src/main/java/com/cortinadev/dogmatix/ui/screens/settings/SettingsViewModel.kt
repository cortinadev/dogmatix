package com.cortinadev.dogmatix.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.repository.DownloadableFileRepository
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.model.DebridProvider
import com.cortinadev.dogmatix.data.service.DaijishoConfigService
import com.cortinadev.dogmatix.data.service.DebridClient
import com.cortinadev.dogmatix.data.service.EsdeConfigService
import com.cortinadev.dogmatix.data.service.FrontendSetupException
import com.cortinadev.dogmatix.data.service.IisuConfigService
import com.cortinadev.dogmatix.data.service.FrontendShortcutService
import com.cortinadev.dogmatix.data.service.RealDebridClient
import com.cortinadev.dogmatix.data.service.TorBoxClient
import com.cortinadev.dogmatix.ui.common.GamepadLayout
import com.cortinadev.dogmatix.ui.common.executeWithToast
import com.cortinadev.dogmatix.util.Constants
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
    val torboxApiKey: String = "",
    val realDebridApiKey: String = "",
    val rommUrl: String = "",
    val esdeDirectory: String = "",
    val iisuDirectory: String = "",
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
    private val daijishoConfigService: DaijishoConfigService
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
        repository.swapFaceButtons
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
            swapFaceButtons = values[17] as Boolean
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    fun onDebridProviderChanged(context: Context, provider: DebridProvider) {
        executeWithToast(context, "SettingsViewModel") { repository.setDebridProvider(provider) }
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
        viewModelScope.launch {
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
}
