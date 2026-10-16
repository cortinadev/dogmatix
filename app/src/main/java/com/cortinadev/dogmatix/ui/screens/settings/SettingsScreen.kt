package com.cortinadev.dogmatix.ui.screens.settings

import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.cortinadev.dogmatix.data.service.HostLimiter
import com.cortinadev.dogmatix.ui.common.cycled
import com.cortinadev.dogmatix.ui.common.labelRes
import com.cortinadev.dogmatix.util.AutoRetryPolicy
import com.cortinadev.dogmatix.util.AutoScanFrequency
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.GamepadButton
import com.cortinadev.dogmatix.ui.common.Legend
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.SidePanelWidth
import com.cortinadev.dogmatix.ui.components.TruncatedText
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.Stepper
import com.cortinadev.dogmatix.ui.components.SubTab
import com.cortinadev.dogmatix.ui.components.SubTabs
import com.cortinadev.dogmatix.ui.screens.settings.components.ApiKeyDialog
import com.cortinadev.dogmatix.ui.screens.settings.components.DaijishoSetupDialog
import com.cortinadev.dogmatix.ui.screens.settings.components.FrontendCheckDialog
import com.cortinadev.dogmatix.util.FrontendCheck
import com.cortinadev.dogmatix.ui.screens.settings.components.FavoriteLanguagesDialog
import com.cortinadev.dogmatix.ui.screens.settings.components.maskedSecret
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.data.model.DebridProvider
import com.cortinadev.dogmatix.data.model.SeedingMode
import com.cortinadev.dogmatix.ui.common.GamepadLayout
import com.cortinadev.dogmatix.ui.theme.AccentPresets
import com.cortinadev.dogmatix.util.TagColorKind
import com.cortinadev.dogmatix.util.Constants
import com.cortinadev.dogmatix.util.TorrentConstants
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens
import com.cortinadev.dogmatix.ui.theme.ThemeMode
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.runtime.withFrameNanos
import com.cortinadev.dogmatix.data.search.SearchTarget
import com.cortinadev.dogmatix.data.search.SettingsRowKeys
import com.cortinadev.dogmatix.ui.navigation.SearchTargetEffect
import kotlinx.coroutines.launch

/** Languages offered in Settings; [tag] is empty for "follow the device". */
enum class AppLanguage(val tag: String, val label: Int) {
    SYSTEM("", R.string.language_system),
    EN("en", R.string.language_en),
    ES("es", R.string.language_es),
    DE("de", R.string.language_de),
    FR("fr", R.string.language_fr),
    PT("pt", R.string.language_pt),
    IT("it", R.string.language_it);

    companion object {
        fun current(): AppLanguage {
            val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')
            return entries.firstOrNull { it.tag == tag && it.tag.isNotEmpty() } ?: SYSTEM
        }
    }
}

private const val SPEED_STEP = 250
private const val SPEED_MAX = 5000

/** One width for every stepper value, so the – and + of the single column line up. */
private val StepperWidth = 130.dp

@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val ui by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.onDownloadDirChanged(context, it.toString())
        }
    }
    val esdeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.onEsdeDirPicked(context, it.toString())
        }
    }
    // First run asks for the ES-DE folder; later runs reuse it and reconfigure directly.
    fun runEsdeSetup() {
        if (ui.esdeDirectory.isBlank()) esdeLauncher.launch(null) else viewModel.onConfigureEsde(context)
    }
    val iisuLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.onIisuDirPicked(context, it.toString())
        }
    }
    fun runIisuSetup() {
        if (ui.iisuDirectory.isBlank()) iisuLauncher.launch(null) else viewModel.onConfigureIisu(context)
    }

    // frontends: "Check frontends"; a lost folder is picked again with the setup's own picker
    val frontendCheck by viewModel.frontendCheck.collectAsState()
    frontendCheck?.let { check ->
        FrontendCheckDialog(
            state = check,
            onRepair = { repair ->
                when (repair) {
                    FrontendCheck.Repair.PICK_ESDE_FOLDER -> esdeLauncher.launch(null)
                    FrontendCheck.Repair.PICK_IISU_FOLDER -> iisuLauncher.launch(null)
                    else -> viewModel.onFrontendRepair(context, repair)
                }
            },
            onRecheck = viewModel::onRecheckFrontends,
            onDismiss = viewModel::onFrontendCheckDismissed
        )
    }

    fun cycleTheme(delta: Int) {
        val modes = ThemeMode.entries
        viewModel.onThemeModeChanged(context, modes[((ui.themeMode.ordinal + delta) % modes.size + modes.size) % modes.size])
    }
    fun cycleAccent(delta: Int) {
        val presets = AccentPresets.all
        val current = presets.indexOf(ui.accent).coerceAtLeast(0)
        viewModel.onAccentChanged(context, presets[((current + delta) % presets.size + presets.size) % presets.size])
    }
    fun cycleGamepadLayout(delta: Int) {
        val layouts = GamepadLayout.entries
        viewModel.onGamepadLayoutChanged(context, layouts[((ui.gamepadLayout.ordinal + delta) % layouts.size + layouts.size) % layouts.size])
    }
    // Download retries, per-server limit, multi-disc playlists and the preferred region
    val features by viewModel.features.collectAsState()
    val batchNotification by viewModel.batchNotification.collectAsState()
    fun adjustAutoRetry(delta: Int) {
        val choices = AutoRetryPolicy.ATTEMPT_CHOICES
        val current = choices.indexOf(features.autoRetryAttempts).takeIf { it >= 0 } ?: choices.indexOf(AutoRetryPolicy.DEFAULT_ATTEMPTS)
        viewModel.onAutoRetryChanged(context, choices[(current + delta).coerceIn(0, choices.lastIndex)])
    }
    fun adjustPerHost(delta: Int) {
        val choices = HostLimiter.CHOICES
        val current = choices.indexOf(features.perHostDownloads).coerceAtLeast(0)
        viewModel.onPerHostDownloadsChanged(context, choices[(current + delta).coerceIn(0, choices.lastIndex)])
    }
    fun cycleRegion(delta: Int) = viewModel.onPreferredRegionChanged(context, features.preferredRegion.cycled(delta))

    fun adjustConcurrent(delta: Int) =
        viewModel.onConcurrentDownloadsChanged(context, (ui.concurrentDownloads + delta).coerceIn(1, 10))
    // Fixed choices instead of free steps: the useful jumps are coarse (50 → 100 → … → no limit).
    fun adjustMaxSearchResults(delta: Int) {
        val choices = Constants.MAX_SEARCH_RESULTS_CHOICES
        val current = choices.indexOf(ui.maxSearchResults).takeIf { it >= 0 }
            ?: choices.indexOf(Constants.DEFAULT_MAX_SEARCH_RESULTS)
        viewModel.onMaxSearchResultsChanged(context, choices[(current + delta).coerceIn(0, choices.lastIndex)])
    }
    fun adjustMetadataTimeout(delta: Int) = viewModel.onMetadataTimeoutChanged(
        context, (ui.metadataTimeoutSeconds + delta * 10).coerceIn(TorrentConstants.MIN_METADATA_TIMEOUT_S, TorrentConstants.MAX_METADATA_TIMEOUT_S)
    )
    val limitKb = if (ui.limitSpeed == Float.POSITIVE_INFINITY) 0 else ui.limitSpeed.toInt()
    fun adjustLimit(delta: Int) {
        val next = (limitKb + delta * SPEED_STEP).coerceIn(0, SPEED_MAX)
        viewModel.onLimitSpeedChanged(context, if (next == 0) Float.POSITIVE_INFINITY else next.toFloat())
    }

    val themeLabel = stringResource(ui.themeMode.labelRes) + if (ui.themeMode == ThemeMode.SYSTEM) {
        " · " + stringResource(if (LocalDogmatixTokens.current.isDark) R.string.theme_suffix_dark else R.string.theme_suffix_light)
    } else ""

    // App language: persisted by AppCompat (autoStoreLocales) and applied by recreating the activity.
    var appLanguage by remember { mutableStateOf(AppLanguage.current()) }
    fun cycleLanguage(delta: Int) {
        val entries = AppLanguage.entries
        appLanguage = entries[(appLanguage.ordinal + delta + entries.size) % entries.size]
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(appLanguage.tag))
    }

    var showLanguagesDialog by remember { mutableStateOf(false) }
    if (showLanguagesDialog) {
        val available by viewModel.availableLanguages.collectAsState()
        LaunchedEffect(Unit) { viewModel.loadAvailableLanguages() }
        FavoriteLanguagesDialog(
            available = available,
            favorites = ui.favoriteLanguages,
            onChange = { viewModel.onFavoriteLanguagesChanged(context, it) },
            onDismiss = { showLanguagesDialog = false }
        )
    }

    val seeding = ui.seedingMode
    fun cycleSeeding(delta: Int) {
        val modes = SeedingMode.entries
        viewModel.onSeedingModeChanged(context, modes[((seeding.ordinal + delta) % modes.size + modes.size) % modes.size])
    }

    val debrid = ui.debridProvider
    fun cycleDebrid(delta: Int) {
        val providers = DebridProvider.entries
        viewModel.onDebridProviderChanged(context, providers[((debrid.ordinal + delta) % providers.size + providers.size) % providers.size])
    }
    val debridKey = when (debrid) {
        DebridProvider.TORBOX -> ui.torboxApiKey
        DebridProvider.REAL_DEBRID -> ui.realDebridApiKey
        DebridProvider.NONE -> ""
    }
    val debridKeyTitle = stringResource(R.string.settings_debrid_key, debrid.label)
    val daijishoSetup by viewModel.daijishoSetup.collectAsState()
    daijishoSetup?.let { setup ->
        DaijishoSetupDialog(setup = setup, onDismiss = viewModel::onDaijishoSetupDismissed)
    }

    var showTagColorsDialog by remember { mutableStateOf(false) }
    if (showTagColorsDialog) {
        TagColorsDialog(
            onPick = { kind, argb -> viewModel.onTagColorChanged(context, kind, argb) },
            onReset = { viewModel.onResetTagColors(context) },
            onDismiss = { showTagColorsDialog = false }
        )
    }

    var showDebridKeyDialog by remember { mutableStateOf(false) }
    if (showDebridKeyDialog && debrid != DebridProvider.NONE) {
        ApiKeyDialog(
            title = debridKeyTitle,
            hint = stringResource(if (debrid == DebridProvider.TORBOX) R.string.settings_torbox_key_dialog_hint else R.string.settings_realdebrid_key_dialog_hint),
            value = debridKey,
            onTest = { viewModel.testDebridApiKey(context, debrid, it) },
            onSave = { viewModel.onDebridApiKeyChanged(context, debrid, it) },
            onDismiss = { showDebridKeyDialog = false }
        )
    }

    // escaneo: Settings → Automatic scan (background job, see AutoScanScheduler).
    val autoScanViewModel: AutoScanSettingsViewModel = hiltViewModel()
    val autoScan by autoScanViewModel.state.collectAsState()

    // RomM and About open a screen of their own; coming back, the focus returns to the row that opened it.
    var returnTo by rememberSaveable { mutableStateOf<String?>(null) }
    fun openScreen(route: NavRoutes, from: String) {
        returnTo = from
        navController.navigate(route.route)
    }

    // Each category lists its rows in reading order, in blocks under a heading.
    val categories: Map<SettingsCategory, List<SettingsItem>> = mapOf(
        SettingsCategory.GENERAL to listOf(
            SettingsHeading(R.string.settings_group_app),
            SettingsRow(key = SettingsRowKeys.LANGUAGE) {
                SettingRow(
                    title = stringResource(R.string.settings_language),
                    hint = stringResource(R.string.settings_language_hint),
                    onClick = { cycleLanguage(1) },
                    onAdjust = ::cycleLanguage
                ) {
                    Stepper(stringResource(appLanguage.label), onDecrement = { cycleLanguage(-1) }, onIncrement = { cycleLanguage(1) }, valueWidth = StepperWidth)
                }
            },
            // New versions from the GitHub releases (UpdateSettings.kt)
            SettingsRow(key = SettingsRowKeys.UPDATES) { UpdatesRow() },
            SettingsRow(key = SettingsRowKeys.UPDATE_AUTO) { AutoUpdateCheckRow() },
            SettingsHeading(R.string.settings_group_gamepad),
            SettingsRow(key = SettingsRowKeys.GAMEPAD_LAYOUT) {
                SettingRow(
                    title = stringResource(R.string.settings_gamepad_layout),
                    hint = stringResource(R.string.settings_gamepad_layout_hint),
                    onClick = { cycleGamepadLayout(1) },
                    onAdjust = ::cycleGamepadLayout
                ) {
                    Stepper(
                        stringResource(ui.gamepadLayout.labelRes),
                        onDecrement = { cycleGamepadLayout(-1) },
                        onIncrement = { cycleGamepadLayout(1) },
                        valueWidth = StepperWidth
                    )
                }
            },
            SettingsRow(key = SettingsRowKeys.SWAP_BUTTONS) {
                SettingRow(
                    title = stringResource(R.string.settings_swap_face_buttons),
                    hint = stringResource(R.string.settings_swap_face_buttons_hint),
                    onClick = { viewModel.onSwapFaceButtonsChanged(context, !ui.swapFaceButtons) },
                    onAdjust = { viewModel.onSwapFaceButtonsChanged(context, it > 0) }
                ) {
                    ThemedSwitch(ui.swapFaceButtons) { viewModel.onSwapFaceButtonsChanged(context, it) }
                }
            }
        ),
        SettingsCategory.LIBRARY to listOf(
            SettingsHeading(R.string.settings_group_folder),
            SettingsRow(key = SettingsRowKeys.DOWNLOAD_DIR) {
                SettingRow(
                    title = stringResource(R.string.settings_download_directory),
                    hint = ui.downloadDirectory.ifBlank { stringResource(R.string.settings_not_set) },
                    onClick = { launcher.launch(null) }
                ) {
                    PillButton(stringResource(R.string.settings_change)) { launcher.launch(null) }
                }
            },
            SettingsRow(key = SettingsRowKeys.SEPARATE_CONSOLE) {
                SettingRow(
                    title = stringResource(R.string.settings_separate_by_console),
                    hint = stringResource(R.string.settings_separate_hint),
                    onClick = { viewModel.onSeparateByConsoleChanged(context, !ui.separateByConsole) },
                    onAdjust = { viewModel.onSeparateByConsoleChanged(context, it > 0) }
                ) {
                    ThemedSwitch(ui.separateByConsole) { viewModel.onSeparateByConsoleChanged(context, it) }
                }
            },
            SettingsHeading(R.string.settings_group_sources),
            // escaneo
            SettingsRow(key = SettingsRowKeys.AUTO_SCAN) {
                SettingRow(
                    title = stringResource(R.string.settings_auto_scan),
                    hint = stringResource(R.string.settings_auto_scan_hint),
                    onClick = { autoScanViewModel.cycleFrequency(1) },
                    onAdjust = autoScanViewModel::cycleFrequency
                ) {
                    Stepper(
                        stringResource(autoScan.frequency.labelRes),
                        onDecrement = { autoScanViewModel.cycleFrequency(-1) },
                        onIncrement = { autoScanViewModel.cycleFrequency(1) },
                        valueWidth = StepperWidth
                    )
                }
            },
            SettingsRow(visible = autoScan.frequency != AutoScanFrequency.OFF, key = SettingsRowKeys.AUTO_SCAN_WIFI) {
                SettingRow(
                    title = stringResource(R.string.settings_auto_scan_wifi),
                    hint = stringResource(R.string.settings_auto_scan_wifi_hint),
                    onClick = { autoScanViewModel.setWifiOnly(!autoScan.wifiOnly) },
                    onAdjust = { autoScanViewModel.setWifiOnly(it > 0) }
                ) {
                    ThemedSwitch(autoScan.wifiOnly) { autoScanViewModel.setWifiOnly(it) }
                }
            },
            SettingsRow(visible = autoScan.frequency != AutoScanFrequency.OFF, key = SettingsRowKeys.AUTO_SCAN_CHARGING) {
                SettingRow(
                    title = stringResource(R.string.settings_auto_scan_charging),
                    hint = stringResource(R.string.settings_auto_scan_charging_hint),
                    onClick = { autoScanViewModel.setChargingOnly(!autoScan.chargingOnly) },
                    onAdjust = { autoScanViewModel.setChargingOnly(it > 0) }
                ) {
                    ThemedSwitch(autoScan.chargingOnly) { autoScanViewModel.setChargingOnly(it) }
                }
            },
            SettingsHeading(R.string.settings_group_preferences),
            SettingsRow(key = SettingsRowKeys.REGION) {
                SettingRow(
                    title = stringResource(R.string.settings_region),
                    hint = stringResource(R.string.settings_region_hint),
                    onClick = { cycleRegion(1) },
                    onAdjust = ::cycleRegion
                ) {
                    Stepper(
                        stringResource(features.preferredRegion.labelRes),
                        onDecrement = { cycleRegion(-1) },
                        onIncrement = { cycleRegion(1) },
                        valueWidth = StepperWidth
                    )
                }
            },
            SettingsRow(key = SettingsRowKeys.FAVORITE_LANGUAGES) {
                SettingRow(
                    title = stringResource(R.string.settings_favorite_languages),
                    hint = ui.favoriteLanguages.sorted().joinToString(" · ")
                        .ifBlank { stringResource(R.string.settings_favorite_languages_hint) },
                    onClick = { showLanguagesDialog = true }
                ) {
                    PillButton(stringResource(R.string.settings_change)) { showLanguagesDialog = true }
                }
            },
            SettingsRow(key = SettingsRowKeys.MAX_RESULTS) {
                SettingRow(
                    title = stringResource(R.string.settings_max_results),
                    hint = stringResource(R.string.settings_max_results_hint),
                    onClick = { adjustMaxSearchResults(1) },
                    onAdjust = ::adjustMaxSearchResults
                ) {
                    Stepper(
                        if (ui.maxSearchResults <= 0) stringResource(R.string.settings_unlimited) else ui.maxSearchResults.toString(),
                        onDecrement = { adjustMaxSearchResults(-1) },
                        onIncrement = { adjustMaxSearchResults(1) },
                        valueWidth = StepperWidth
                    )
                }
            }
        ),
        SettingsCategory.APPEARANCE to listOf(
            SettingsHeading(R.string.settings_group_colors),
            SettingsRow(key = SettingsRowKeys.THEME) {
                SettingRow(
                    title = stringResource(R.string.settings_theme),
                    hint = stringResource(R.string.settings_theme_hint),
                    onClick = { cycleTheme(1) },
                    onAdjust = ::cycleTheme
                ) {
                    Stepper(themeLabel, onDecrement = { cycleTheme(-1) }, onIncrement = { cycleTheme(1) }, valueWidth = StepperWidth)
                }
            },
            SettingsRow(key = SettingsRowKeys.ACCENT) {
                SettingRow(
                    title = stringResource(R.string.settings_accent),
                    hint = stringResource(R.string.settings_accent_hint),
                    onClick = { cycleAccent(1) },
                    onAdjust = ::cycleAccent
                ) {
                    AccentSwatches(selected = ui.accent, size = if (isLandscape) 22.dp else 36.dp) {
                        viewModel.onAccentChanged(context, it)
                    }
                }
            },
            SettingsHeading(R.string.settings_group_game_list),
            SettingsRow(key = SettingsRowKeys.LIST_COVERS) {
                SettingRow(
                    title = stringResource(R.string.settings_list_covers),
                    hint = stringResource(R.string.settings_list_covers_hint),
                    onClick = { viewModel.onShowListCoversChanged(context, !ui.showListCovers) },
                    onAdjust = { viewModel.onShowListCoversChanged(context, !ui.showListCovers) }
                ) {
                    ThemedSwitch(ui.showListCovers) { viewModel.onShowListCoversChanged(context, it) }
                }
            },
            SettingsRow(key = SettingsRowKeys.TAG_COLORS) {
                SettingRow(
                    title = stringResource(R.string.settings_tag_colors),
                    hint = stringResource(R.string.settings_tag_colors_hint),
                    onClick = { showTagColorsDialog = true }
                ) {
                    TagColorDots(ui.tagColors)
                }
            }
        ),
        SettingsCategory.DOWNLOADS to listOf(
            SettingsHeading(R.string.settings_group_queue),
            SettingsRow(key = SettingsRowKeys.CONCURRENT) {
                SettingRow(
                    title = stringResource(R.string.settings_concurrent_label),
                    hint = null,
                    onClick = { adjustConcurrent(1) },
                    onAdjust = ::adjustConcurrent
                ) {
                    Stepper(ui.concurrentDownloads.toString(), onDecrement = { adjustConcurrent(-1) }, onIncrement = { adjustConcurrent(1) }, valueWidth = StepperWidth)
                }
            },
            SettingsRow(key = SettingsRowKeys.PER_HOST) {
                SettingRow(
                    title = stringResource(R.string.settings_per_host),
                    hint = stringResource(R.string.settings_per_host_hint),
                    onClick = { adjustPerHost(1) },
                    onAdjust = ::adjustPerHost
                ) {
                    Stepper(
                        if (features.perHostDownloads <= 0) stringResource(R.string.settings_unlimited) else features.perHostDownloads.toString(),
                        onDecrement = { adjustPerHost(-1) },
                        onIncrement = { adjustPerHost(1) },
                        valueWidth = StepperWidth
                    )
                }
            },
            SettingsRow(key = SettingsRowKeys.SPEED_LIMIT) {
                SettingRow(
                    title = stringResource(R.string.settings_limit_label),
                    hint = stringResource(R.string.settings_limit_hint),
                    onClick = { adjustLimit(1) },
                    onAdjust = ::adjustLimit
                ) {
                    Stepper(
                        if (limitKb == 0) stringResource(R.string.settings_unrestricted) else "$limitKb KB/s",
                        onDecrement = { adjustLimit(-1) },
                        onIncrement = { adjustLimit(1) },
                        valueWidth = StepperWidth
                    )
                }
            },
            SettingsRow(key = SettingsRowKeys.AUTO_RETRY) {
                SettingRow(
                    title = stringResource(R.string.settings_auto_retry),
                    hint = stringResource(R.string.settings_auto_retry_hint),
                    onClick = { adjustAutoRetry(1) },
                    onAdjust = ::adjustAutoRetry
                ) {
                    Stepper(
                        if (features.autoRetryAttempts <= 0) stringResource(R.string.settings_off) else features.autoRetryAttempts.toString(),
                        onDecrement = { adjustAutoRetry(-1) },
                        onIncrement = { adjustAutoRetry(1) },
                        valueWidth = StepperWidth
                    )
                }
            },
            // dl-cola
            SettingsRow(key = SettingsRowKeys.BATCH_NOTICE) {
                SettingRow(
                    title = stringResource(R.string.settings_batch_notice),
                    hint = stringResource(R.string.settings_batch_notice_hint),
                    onClick = { viewModel.onBatchNotificationChanged(context, !batchNotification) },
                    onAdjust = { viewModel.onBatchNotificationChanged(context, it > 0) }
                ) {
                    ThemedSwitch(batchNotification) { viewModel.onBatchNotificationChanged(context, it) }
                }
            },
            SettingsHeading(R.string.settings_group_after_download),
            SettingsRow(key = SettingsRowKeys.AUTO_UNZIP) {
                SettingRow(
                    title = stringResource(R.string.settings_auto_unzip),
                    hint = stringResource(R.string.settings_auto_unzip_hint),
                    onClick = { viewModel.onAutoUnzipChanged(context, !ui.autoUnzip) },
                    onAdjust = { viewModel.onAutoUnzipChanged(context, it > 0) }
                ) {
                    ThemedSwitch(ui.autoUnzip) { viewModel.onAutoUnzipChanged(context, it) }
                }
            },
            SettingsRow(key = SettingsRowKeys.MULTI_DISC) {
                SettingRow(
                    title = stringResource(R.string.settings_multi_disc),
                    hint = stringResource(R.string.settings_multi_disc_hint),
                    onClick = { viewModel.onMultiDiscPlaylistsChanged(context, !features.multiDiscPlaylists) },
                    onAdjust = { viewModel.onMultiDiscPlaylistsChanged(context, it > 0) }
                ) {
                    ThemedSwitch(features.multiDiscPlaylists) { viewModel.onMultiDiscPlaylistsChanged(context, it) }
                }
            },
            // dl-motor: checksum / DAT check of finished downloads (DownloadChecksSettings.kt)
            SettingsRow(key = SettingsRowKeys.VERIFY_DOWNLOADS) { VerifyDownloadsRow() },
            SettingsRow(key = SettingsRowKeys.DAT_FILES) { DatFilesRow() },
            SettingsHeading(R.string.settings_group_torrents),
            SettingsRow(key = SettingsRowKeys.METADATA_TIMEOUT) {
                SettingRow(
                    title = stringResource(R.string.settings_metadata_timeout),
                    hint = stringResource(R.string.settings_metadata_timeout_hint),
                    onClick = { adjustMetadataTimeout(1) },
                    onAdjust = ::adjustMetadataTimeout
                ) {
                    Stepper(stringResource(R.string.seconds_short, ui.metadataTimeoutSeconds), onDecrement = { adjustMetadataTimeout(-1) }, onIncrement = { adjustMetadataTimeout(1) }, valueWidth = StepperWidth)
                }
            },
            SettingsRow(key = SettingsRowKeys.SEEDING) {
                SettingRow(
                    title = stringResource(R.string.settings_seeding),
                    hint = stringResource(R.string.settings_seeding_hint),
                    onClick = { cycleSeeding(1) },
                    onAdjust = ::cycleSeeding
                ) {
                    Stepper(
                        stringResource(seeding.labelRes),
                        onDecrement = { cycleSeeding(-1) },
                        onIncrement = { cycleSeeding(1) },
                        valueWidth = StepperWidth
                    )
                }
            },
            SettingsRow(key = SettingsRowKeys.DEBRID) {
                SettingRow(
                    title = stringResource(R.string.settings_debrid),
                    hint = stringResource(R.string.settings_debrid_hint),
                    onClick = { cycleDebrid(1) },
                    onAdjust = ::cycleDebrid
                ) {
                    Stepper(debrid.label, onDecrement = { cycleDebrid(-1) }, onIncrement = { cycleDebrid(1) }, valueWidth = StepperWidth)
                }
            },
            SettingsRow(visible = debrid != DebridProvider.NONE) {
                SettingRow(
                    title = debridKeyTitle,
                    hint = maskedSecret(debridKey),
                    onClick = { showDebridKeyDialog = true }
                ) {
                    PillButton(stringResource(R.string.settings_change)) { showDebridKeyDialog = true }
                }
            }
        ),
        SettingsCategory.INTEGRATIONS to listOf(
            SettingsHeading(R.string.settings_group_frontends),
            SettingsRow(key = SettingsRowKeys.FRONTEND_SHORTCUTS) {
                SettingRow(
                    title = stringResource(R.string.settings_frontend_shortcuts),
                    hint = stringResource(R.string.settings_frontend_shortcuts_hint),
                    onClick = { viewModel.onDeployFrontendShortcuts(context) }
                ) {
                    PillButton(stringResource(R.string.settings_frontend_shortcuts_action)) {
                        viewModel.onDeployFrontendShortcuts(context)
                    }
                }
            },
            SettingsRow(key = SettingsRowKeys.ESDE) {
                SettingRow(
                    title = stringResource(R.string.settings_esde),
                    hint = ui.esdeDirectory.ifBlank { stringResource(R.string.settings_esde_hint) },
                    onClick = ::runEsdeSetup
                ) {
                    PillButton(stringResource(R.string.settings_esde_action), ::runEsdeSetup)
                }
            },
            // frontends
            SettingsRow(visible = ui.esdeDirectory.isNotBlank(), key = SettingsRowKeys.ESDE_FAVOURITES) {
                SettingRow(
                    title = stringResource(R.string.settings_esde_favourites),
                    hint = stringResource(R.string.settings_esde_favourites_hint),
                    onClick = { viewModel.onSyncEsdeFavourites(context) }
                ) {
                    PillButton(stringResource(R.string.settings_esde_favourites_action)) { viewModel.onSyncEsdeFavourites(context) }
                }
            },
            SettingsRow(key = SettingsRowKeys.IISU) {
                SettingRow(
                    title = stringResource(R.string.settings_iisu),
                    hint = ui.iisuDirectory.ifBlank { stringResource(R.string.settings_iisu_hint) },
                    onClick = ::runIisuSetup
                ) {
                    PillButton(stringResource(R.string.settings_iisu_action), ::runIisuSetup)
                }
            },
            SettingsRow(key = SettingsRowKeys.DAIJISHO) {
                SettingRow(
                    title = stringResource(R.string.settings_daijisho),
                    hint = stringResource(R.string.settings_daijisho_hint),
                    onClick = { viewModel.onPrepareDaijisho(context) }
                ) {
                    PillButton(stringResource(R.string.settings_daijisho_action)) { viewModel.onPrepareDaijisho(context) }
                }
            },
            SettingsRow(key = SettingsRowKeys.FRONTEND_CHECK) {
                SettingRow(
                    title = stringResource(R.string.settings_frontend_check),
                    hint = stringResource(R.string.settings_frontend_check_hint),
                    onClick = viewModel::onCheckFrontends
                ) {
                    PillButton(stringResource(R.string.settings_frontend_check_action), viewModel::onCheckFrontends)
                }
            },
            SettingsHeading(R.string.settings_group_servers),
            SettingsRow(key = SettingsRowKeys.ROMM) {
                SettingRow(
                    title = stringResource(R.string.settings_romm),
                    hint = ui.rommUrl.ifBlank { stringResource(R.string.settings_romm_hint) },
                    onClick = { openScreen(NavRoutes.Romm, SettingsRowKeys.ROMM) }
                ) {
                    Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    )
    val latestCategories by rememberUpdatedState(categories)

    var category by rememberSaveable { mutableStateOf(SettingsCategory.GENERAL) }
    var contentHasFocus by remember { mutableStateOf(false) }
    // Landed on a row from a search result: B goes straight back to the search, not to the panel.
    var fromSearch by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val panelFocus = remember { SettingsCategory.entries.associateWith { FocusRequester() } }
    val aboutFocus = remember { FocusRequester() }
    // One requester per entry, headings included (never used). Rows hidden behind another setting
    // keep their place in the list, so an index always means the same row.
    val rowFocus = remember { categories.mapValues { (_, items) -> List(items.size) { FocusRequester() } } }
    val listStates = remember { SettingsCategory.entries.associateWith { LazyListState() } }
    // The row last used in each category: LB · RB and ▶ from the panel come back to it.
    val lastRow = remember { mutableMapOf<SettingsCategory, Int>() }

    fun firstRow(c: SettingsCategory) = latestCategories.getValue(c).indexOfFirst { it is SettingsRow && it.visible }
    fun locate(key: String): Pair<SettingsCategory, Int>? {
        latestCategories.forEach { (c, items) ->
            val index = items.indexOfFirst { it is SettingsRow && it.key == key }
            if (index >= 0) return c to index
        }
        return null
    }
    /** Scrolls row [index] of [c] into view and focuses it; false while it is hidden or not laid out. */
    suspend fun focusRow(c: SettingsCategory, index: Int): Boolean {
        val position = latestCategories.getValue(c).shownIndices().indexOf(index)
        if (position < 0) return false
        val state = listStates.getValue(c)
        if (state.layoutInfo.visibleItemsInfo.none { it.index == position }) {
            state.scrollToItem(position)
            withFrameNanos { }
        }
        return runCatching { rowFocus.getValue(c)[index].requestFocus() }.getOrDefault(false)
    }

    // Two frames: the target may only be laid out after the change that asked for it.
    val focusPanel: () -> Unit = {
        scope.launch {
            withFrameNanos { }
            withFrameNanos { }
            runCatching { panelFocus.getValue(category).requestFocus() }
        }
    }
    val focusContent: () -> Unit = {
        scope.launch {
            withFrameNanos { }
            withFrameNanos { }
            val c = category
            if (!focusRow(c, lastRow[c] ?: firstRow(c))) focusRow(c, firstRow(c))
        }
    }
    // By touch nothing holds the focus: a ring jumping into the category would only be noise.
    val openCategory: (SettingsCategory) -> Unit = { next ->
        category = next
        fromSearch = false
        if (Gamepad.connected.value) focusContent()
    }
    // The category under the cursor is the one shown (side panel or tabs).
    val onCategoryFocused: (SettingsCategory?) -> Unit = { focused ->
        if (focused != null && focused != category) {
            category = focused
            fromSearch = false
        }
    }

    LaunchedEffect(Unit) {
        Gamepad.presses.collect { button ->
            when (button) {
                GamepadButton.PREV_PANEL -> {
                    fromSearch = false
                    focusPanel()
                }
                GamepadButton.NEXT_PANEL -> focusContent()
                else -> Unit
            }
        }
    }

    // B: rows → their category in the panel or tabs → back to the previous section, as before the categories.
    BackHandler(enabled = !fromSearch && contentHasFocus) { focusPanel() }

    // busqueda: a search result opens its category, scrolls to its row and focuses it.
    SearchTargetEffect<SearchTarget.Setting> { target ->
        // After the shell parks the focus on the section tab.
        repeat(3) { withFrameNanos { } }
        fromSearch = true
        if (target.key == SettingsRowKeys.ABOUT) {
            runCatching { aboutFocus.requestFocus() }
            return@SearchTargetEffect
        }
        val (c, index) = locate(target.key) ?: return@SearchTargetEffect
        category = c
        // A row hidden behind another setting (auto-scan switches, ES-DE favourites) lands on that
        // one. The settings may still be loading and show or hide it, so the row is looked up
        // again until it stays put.
        fun wanted(): Int? = index.takeIf { (latestCategories.getValue(c)[it] as SettingsRow).visible }
            ?: SettingsRowKeys.shownWith[target.key]?.let { locate(it)?.second }
        var focused = -1
        for (attempt in 0 until 5) {
            withFrameNanos { }
            val row = wanted() ?: return@SearchTargetEffect
            if (row == focused) break
            if (focusRow(c, row)) focused = row
            withFrameNanos { }
        }
    }

    // Back from RomM or About: the focus goes to the row that opened it (not after a section switch,
    // where the ring stays hidden until the next D-pad press).
    LaunchedEffect(Unit) {
        val from = returnTo ?: return@LaunchedEffect
        returnTo = null
        if (!Gamepad.connected.value || Gamepad.pointerHidden.value) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        if (from == SettingsRowKeys.ABOUT) runCatching { aboutFocus.requestFocus() }
        else locate(from)?.let { (c, index) -> focusRow(c, index) }
    }

    val legend = buildList {
        if (contentHasFocus) {
            add(LegendEntry("A", stringResource(R.string.pad_change)))
            add(LegendEntry("◀ ▶", stringResource(R.string.pad_adjust)))
        } else {
            add(LegendEntry("A", stringResource(R.string.pad_open)))
        }
        add(LegendEntry("LB · RB", stringResource(R.string.pad_panels)))
        add(LegendEntry("B", stringResource(R.string.pad_back)))
        add(LegendEntry("ZL · ZR", stringResource(R.string.pad_section)))
    }
    // Only while Settings is the current screen: opening RomM, this one is still composed for a
    // moment, and publishing then would replace RomM's legend.
    val onThisScreen = navController.currentBackStackEntryAsState().value?.destination?.route == NavRoutes.Settings.route
    val published = remember(legend, onThisScreen) { if (onThisScreen) Legend(legend) else null }
    LaunchedEffect(published) { if (published != null) Gamepad.legendOverride.value = published }
    // Only clear our own legend: the next screen may already have published its own.
    DisposableEffect(published) { onDispose { if (published != null && Gamepad.legendOverride.value === published) Gamepad.legendOverride.value = null } }

    val content: @Composable (Modifier) -> Unit = { modifier ->
        val c = category
        // The group goes on a box around the list: set on the list itself, ▼ on the last row on
        // screen could not bring in the next one and jumped to the panel instead.
        Box(
            modifier = modifier
                .onFocusChanged { contentHasFocus = it.hasFocus }
                // ▶ / ▼ or RB from the panel or tabs lands on the row last used, not on whatever is nearest.
                // A request for a given row (FocusDirection.Enter: a search result) goes where it asked.
                .focusProperties {
                    onEnter = {
                        if (requestedFocusDirection != FocusDirection.Enter) {
                            runCatching { rowFocus.getValue(c)[lastRow[c] ?: firstRow(c)].requestFocus() }
                        }
                    }
                }
                .focusGroup()
        ) {
            key(c) {
                CategoryContent(
                    items = categories.getValue(c),
                    focus = rowFocus.getValue(c),
                    state = listStates.getValue(c),
                    onRowFocused = { lastRow[c] = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // ◀ from a row (or ▲ from the first one, in portrait) comes back to its category, never to a neighbour.
    val panelModifier = Modifier
        .focusProperties {
            onEnter = {
                if (requestedFocusDirection != FocusDirection.Enter) {
                    runCatching { panelFocus.getValue(category).requestFocus() }
                }
            }
        }
        .focusGroup()

    Box(
        modifier = Modifier
            .fillMaxSize()
            // From the section tabs the D-pad lands on the category in use, not on whatever is nearest.
            .focusProperties {
                onEnter = {
                    if (requestedFocusDirection != FocusDirection.Enter) {
                        runCatching { panelFocus.getValue(category).requestFocus() }
                    }
                }
            }
            .focusGroup()
    ) {
        if (isLandscape) {
            Row(modifier = Modifier.fillMaxSize()) {
                CategoryPanel(
                    active = category,
                    focus = panelFocus,
                    aboutFocus = aboutFocus,
                    onFocused = onCategoryFocused,
                    onOpen = openCategory,
                    onAbout = { openScreen(NavRoutes.Contact, SettingsRowKeys.ABOUT) },
                    modifier = Modifier
                        .width(SidePanelWidth)
                        .fillMaxHeight()
                        .then(panelModifier)
                )
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                content(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            // Portrait: the categories as tabs fixed at the top, About last.
            Column(modifier = Modifier.fillMaxSize()) {
                SubTabs(modifier = panelModifier) {
                    SettingsCategory.entries.forEach { c ->
                        SubTab(
                            title = stringResource(c.title),
                            selected = c == category,
                            focus = panelFocus.getValue(c),
                            onFocused = { onCategoryFocused(c) },
                            onClick = { openCategory(c) }
                        )
                    }
                    SubTab(
                        title = stringResource(R.string.settings_about),
                        selected = false,
                        focus = aboutFocus,
                        onFocused = { onCategoryFocused(null) },
                        onClick = { openScreen(NavRoutes.Contact, SettingsRowKeys.ABOUT) },
                        badge = "›"
                    )
                }
                content(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** The groups Settings is split into, in the order the side panel lists them. */
enum class SettingsCategory(@param:StringRes val title: Int) {
    GENERAL(R.string.settings_category_general),
    LIBRARY(R.string.settings_category_library),
    APPEARANCE(R.string.settings_category_appearance),
    DOWNLOADS(R.string.settings_category_downloads),
    INTEGRATIONS(R.string.settings_category_integrations)
}

/** One entry of a category: a heading over a block of rows, or a row. */
private sealed interface SettingsItem

private class SettingsHeading(@param:StringRes val title: Int) : SettingsItem

/**
 * One Settings entry. A row that does not apply is left out with [visible] = false, so the block
 * closes up instead of leaving a gap.
 */
private class SettingsRow(
    val visible: Boolean = true,
    /** Where a search result lands ([SettingsRowKeys]); null for rows the search does not list. */
    val key: String? = null,
    val content: @Composable () -> Unit
) : SettingsItem

/** Indices of the entries on screen: headings and the rows that apply. */
private fun List<SettingsItem>.shownIndices(): List<Int> = indices.filter { (this[it] as? SettingsRow)?.visible != false }

/** The rows of one category, one column, with a small heading over each block. */
@Composable
private fun CategoryContent(
    items: List<SettingsItem>,
    focus: List<FocusRequester>,
    state: LazyListState,
    onRowFocused: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val shown = items.shownIndices()
    LazyColumn(
        state = state,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
    ) {
        items(shown.size, key = { shown[it] }) { position ->
            val index = shown[position]
            when (val item = items[index]) {
                is SettingsHeading -> Text(
                    stringResource(item.title).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = if (position == 0) 0.dp else 16.dp, bottom = 4.dp)
                )
                is SettingsRow -> Box(
                    modifier = Modifier
                        .focusRequester(focus[index])
                        .onFocusChanged { if (it.hasFocus) onRowFocused(index) }
                ) { item.content() }
            }
        }
    }
}

/**
 * Landscape: the categories, with About at the foot. The category under the cursor is the one
 * shown and A moves into it (portrait shows the same as tabs, [SubTabs]).
 */
@Composable
private fun CategoryPanel(
    active: SettingsCategory,
    focus: Map<SettingsCategory, FocusRequester>,
    aboutFocus: FocusRequester,
    /** The category under the cursor; null on About. */
    onFocused: (SettingsCategory?) -> Unit,
    onOpen: (SettingsCategory) -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 12.dp)
        ) {
            SettingsCategory.entries.forEach { c ->
                PanelRow(
                    title = stringResource(c.title),
                    selected = c == active,
                    focus = focus.getValue(c),
                    onFocused = { onFocused(c) },
                    onClick = { onOpen(c) }
                )
            }
        }
        PanelRow(
            title = stringResource(R.string.settings_about),
            selected = false,
            focus = aboutFocus,
            onFocused = { onFocused(null) },
            onClick = onAbout,
            trailing = "›",
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun PanelRow(
    title: String,
    selected: Boolean,
    focus: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    trailing: String? = null,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .focusRequester(focus)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) scheme.surfaceContainer else scheme.surface.copy(alpha = 0f))
            .focusRing(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        trailing?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant) }
    }
}


/**
 * A focusable settings row. Click / A runs [onClick]; while focused, D-pad left/right
 * calls [onAdjust] with -1 / +1 so steppers, switches and swatches work from a gamepad.
 */
@Composable
internal fun SettingRow(
    title: String,
    hint: String?,
    onClick: () -> Unit,
    onAdjust: ((Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit
) {
    val source = rememberFocusSource()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(RoundedCornerShape(8.dp))
            .focusRing(source)
            .onPreviewKeyEvent { event ->
                if (onAdjust == null || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onAdjust(-1); true }
                    Key.DirectionRight -> { onAdjust(1); true }
                    else -> false
                }
            }
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            TruncatedText(title, style = MaterialTheme.typography.bodyLarge)
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        trailing()
    }
}

@Composable
internal fun PillButton(label: String, onClick: () -> Unit) {
    val source = rememberFocusSource()
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .focusRing(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

// escaneo
private val AutoScanFrequency.labelRes: Int
    get() = when (this) {
        AutoScanFrequency.OFF -> R.string.settings_off
        AutoScanFrequency.DAILY -> R.string.auto_scan_daily
        AutoScanFrequency.WEEKLY -> R.string.auto_scan_weekly
    }

private val SeedingMode.labelRes: Int
    get() = when (this) {
        SeedingMode.OFF -> R.string.seeding_mode_off
        SeedingMode.MINUTES_30 -> R.string.seeding_mode_30m
        SeedingMode.HOUR_1 -> R.string.seeding_mode_1h
        SeedingMode.RATIO_1 -> R.string.seeding_mode_ratio
        SeedingMode.UNLIMITED -> R.string.seeding_mode_unlimited
    }

@Composable
private fun AccentSwatches(selected: Color, size: androidx.compose.ui.unit.Dp, onPick: (Color) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(if (size > 30.dp) 8.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
        AccentPresets.all.forEach { color ->
            val source = rememberFocusSource()
            val isSelected = color == selected
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (isSelected) 2.dp else 0.dp,
                        color = if (isSelected) scheme.onSurface else Color.Transparent,
                        shape = CircleShape
                    )
                    .focusRing(source, size / 2)
                    .clickable(interactionSource = source, indication = null) { onPick(color) }
            )
        }
    }
}

/** The current colour of each tag group, as a preview in the Settings row (not focusable: the row opens the dialog). */
@Composable
private fun TagColorDots(picked: Map<TagColorKind, Int>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        TagColorKind.entries.forEach { kind ->
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color(picked[kind] ?: kind.defaultArgb))
            )
        }
    }
}

@Composable
internal fun ThemedSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = scheme.surface,
            checkedTrackColor = scheme.primary,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = scheme.surface,
            uncheckedTrackColor = LocalDogmatixTokens.current.knobOff,
            uncheckedBorderColor = Color.Transparent
        )
    )
}
