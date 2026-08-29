package com.cortinadev.dogmatix.ui.screens.settings

import android.content.Intent
import android.content.res.Configuration
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.GamepadButton
import com.cortinadev.dogmatix.ui.common.Legend
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.TruncatedText
import com.cortinadev.dogmatix.ui.components.legendFor
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.cortinadev.dogmatix.ui.screens.settings.components.ApiKeyDialog
import com.cortinadev.dogmatix.ui.screens.settings.components.FavoriteLanguagesDialog
import com.cortinadev.dogmatix.ui.screens.settings.components.maskedSecret
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.data.model.DebridProvider
import com.cortinadev.dogmatix.ui.theme.AccentPresets
import com.cortinadev.dogmatix.util.TorrentConstants
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens
import com.cortinadev.dogmatix.ui.theme.ThemeMode
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** Languages offered in Settings; [tag] is empty for "follow the device". */
enum class AppLanguage(val tag: String, val label: Int) {
    SYSTEM("", R.string.language_system),
    EN("en", R.string.language_en),
    ES("es", R.string.language_es);

    companion object {
        fun current(): AppLanguage {
            val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')
            return entries.firstOrNull { it.tag == tag && it.tag.isNotEmpty() } ?: SYSTEM
        }
    }
}

private const val SPEED_STEP = 250
private const val SPEED_MAX = 5000

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

    fun cycleTheme(delta: Int) {
        val modes = ThemeMode.entries
        viewModel.onThemeModeChanged(context, modes[((ui.themeMode.ordinal + delta) % modes.size + modes.size) % modes.size])
    }
    fun cycleAccent(delta: Int) {
        val presets = AccentPresets.all
        val current = presets.indexOf(ui.accent).coerceAtLeast(0)
        viewModel.onAccentChanged(context, presets[((current + delta) % presets.size + presets.size) % presets.size])
    }
    fun adjustConcurrent(delta: Int) =
        viewModel.onConcurrentDownloadsChanged(context, (ui.concurrentDownloads + delta).coerceIn(1, 10))
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

    // Portrait shows the rows in this order; landscape splits them into two columns (`right`),
    // so related rows (debrid service + its API key) stay stacked in the same column.
    val ordered: List<SettingsRow> = listOf(
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_download_directory),
                hint = ui.downloadDirectory.ifBlank { stringResource(R.string.settings_not_set) },
                onClick = { launcher.launch(null) }
            ) {
                PillButton(stringResource(R.string.settings_change)) { launcher.launch(null) }
            }
        },
        SettingsRow(right = true) {
            SettingRow(
                title = stringResource(R.string.settings_theme),
                hint = stringResource(R.string.settings_theme_hint),
                onClick = { cycleTheme(1) },
                onAdjust = ::cycleTheme
            ) {
                Stepper(themeLabel, onDecrement = { cycleTheme(-1) }, onIncrement = { cycleTheme(1) }, valueWidth = 110.dp)
            }
        },
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_language),
                hint = stringResource(R.string.settings_language_hint),
                onClick = { cycleLanguage(1) },
                onAdjust = ::cycleLanguage
            ) {
                Stepper(stringResource(appLanguage.label), onDecrement = { cycleLanguage(-1) }, onIncrement = { cycleLanguage(1) }, valueWidth = 96.dp)
            }
        },
        SettingsRow(right = true) {
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
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_concurrent_label),
                hint = null,
                onClick = { adjustConcurrent(1) },
                onAdjust = ::adjustConcurrent
            ) {
                Stepper(ui.concurrentDownloads.toString(), onDecrement = { adjustConcurrent(-1) }, onIncrement = { adjustConcurrent(1) })
            }
        },
        SettingsRow(right = true) {
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
                    valueWidth = 110.dp
                )
            }
        },
        SettingsRow(right = true) {
            SettingRow(
                title = stringResource(R.string.settings_metadata_timeout),
                hint = stringResource(R.string.settings_metadata_timeout_hint),
                onClick = { adjustMetadataTimeout(1) },
                onAdjust = ::adjustMetadataTimeout
            ) {
                Stepper(stringResource(R.string.seconds_short, ui.metadataTimeoutSeconds), onDecrement = { adjustMetadataTimeout(-1) }, onIncrement = { adjustMetadataTimeout(1) })
            }
        },
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_auto_unzip),
                hint = stringResource(R.string.settings_auto_unzip_hint),
                onClick = { viewModel.onAutoUnzipChanged(context, !ui.autoUnzip) },
                onAdjust = { viewModel.onAutoUnzipChanged(context, it > 0) }
            ) {
                ThemedSwitch(ui.autoUnzip) { viewModel.onAutoUnzipChanged(context, it) }
            }
        },
        SettingsRow(right = true) {
            SettingRow(
                title = stringResource(R.string.settings_separate_by_console),
                hint = stringResource(R.string.settings_separate_hint),
                onClick = { viewModel.onSeparateByConsoleChanged(context, !ui.separateByConsole) },
                onAdjust = { viewModel.onSeparateByConsoleChanged(context, it > 0) }
            ) {
                ThemedSwitch(ui.separateByConsole) { viewModel.onSeparateByConsoleChanged(context, it) }
            }
        },
        SettingsRow(right = true) {
            SettingRow(
                title = stringResource(R.string.settings_debrid),
                hint = stringResource(R.string.settings_debrid_hint),
                onClick = { cycleDebrid(1) },
                onAdjust = ::cycleDebrid
            ) {
                Stepper(debrid.label, onDecrement = { cycleDebrid(-1) }, onIncrement = { cycleDebrid(1) }, valueWidth = 110.dp)
            }
        },
        SettingsRow(right = true) {
            if (debrid != DebridProvider.NONE) SettingRow(
                title = debridKeyTitle,
                hint = maskedSecret(debridKey),
                onClick = { showDebridKeyDialog = true }
            ) {
                PillButton(stringResource(R.string.settings_change)) { showDebridKeyDialog = true }
            }
        },
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_favorite_languages),
                hint = ui.favoriteLanguages.sorted().joinToString(" · ")
                    .ifBlank { stringResource(R.string.settings_favorite_languages_hint) },
                onClick = { showLanguagesDialog = true }
            ) {
                PillButton(stringResource(R.string.settings_change)) { showLanguagesDialog = true }
            }
        },
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_romm),
                hint = ui.rommUrl.ifBlank { stringResource(R.string.settings_romm_hint) },
                onClick = { navController.navigate(NavRoutes.Romm.route) }
            ) {
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        SettingsRow(right = false) {
            SettingRow(
                title = stringResource(R.string.settings_about),
                hint = stringResource(R.string.credits_fork_name) + " · " + stringResource(R.string.credits_original_name),
                onClick = { navController.navigate(NavRoutes.Contact.route) }
            ) {
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
    val rows: List<@Composable () -> Unit> = if (!isLandscape) ordered.map { it.content } else {
        val left = ordered.filter { !it.right }.map { it.content }
        val right = ordered.filter { it.right }.map { it.content }
        (0 until maxOf(left.size, right.size)).flatMap { i -> listOf(left.getOrNull(i) ?: {}, right.getOrNull(i) ?: {}) }
    }

    // LB / RB (landscape): hop between the two columns, staying on the same grid row.
    val columns = if (isLandscape) 2 else 1
    val rowFocus = remember(rows.size) { List(rows.size) { FocusRequester() } }
    var focusedIndex by remember { mutableStateOf(-1) }
    LaunchedEffect(isLandscape) {
        if (!isLandscape) return@LaunchedEffect
        Gamepad.presses.collect { button ->
            if (button != GamepadButton.PREV_PANEL && button != GamepadButton.NEXT_PANEL) return@collect
            val current = focusedIndex
            val target = when {
                current < 0 -> 0
                current % columns == 0 -> current + 1   // left column → right
                else -> current - 1                      // right column → left
            }.coerceIn(0, rows.lastIndex)
            runCatching { rowFocus[target].requestFocus() }
        }
    }
    if (isLandscape) {
        val base = legendFor(NavRoutes.Settings.route)
        val column = LegendEntry("LB · RB", stringResource(R.string.pad_column))
        val legend = remember(base, column) { Legend(base.toMutableList().also { it.add(it.lastIndex, column) }) }
        LaunchedEffect(legend) { Gamepad.legendOverride.value = legend }
        // Only clear our own legend: the previous screen's onDispose can run after ours is set.
        DisposableEffect(legend) { onDispose { if (Gamepad.legendOverride.value === legend) Gamepad.legendOverride.value = null } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp, vertical = 12.dp)
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(rows.size) { index ->
                Box(
                    modifier = Modifier
                        .focusRequester(rowFocus[index])
                        .onFocusChanged { if (it.hasFocus) focusedIndex = index }
                ) { rows[index]() }
            }
        }
    }
}

/** One Settings entry; [right] puts it in the right-hand column of the landscape grid. */
private class SettingsRow(val right: Boolean, val content: @Composable () -> Unit)

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
