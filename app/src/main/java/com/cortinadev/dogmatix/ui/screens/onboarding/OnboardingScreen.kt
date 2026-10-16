package com.cortinadev.dogmatix.ui.screens.onboarding

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.components.GamepadLegend
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.common.cycled
import com.cortinadev.dogmatix.ui.common.labelRes
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens
import com.cortinadev.dogmatix.util.FileParsingUtils

/**
 * First-run flow: what the app does → ROM root folder → preferred versions (region and
 * languages) → import a sources JSON → (when ES-DE is installed) connect ES-DE. Every step can be skipped; finishing sets `onboarding_done`
 * and the shell takes over.
 * Gamepad: focus starts on the primary action of each step, B goes one step back.
 *
 * [onImportSources] receives the picked document URI; the caller runs the import + rescan.
 */
@Composable
fun OnboardingScreen(
    onImportSources: (String) -> Unit,
    isRescanning: Boolean,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val downloadDirectory by viewModel.downloadDirectory.collectAsState()
    val gamepadConnected by Gamepad.connected.collectAsState()
    val esdeBusy by viewModel.esdeBusy.collectAsState()
    val preferredRegion by viewModel.preferredRegion.collectAsState()
    val favoriteLanguages by viewModel.favoriteLanguages.collectAsState()
    val totalSteps = if (viewModel.esdeInstalled) 5 else 4
    var step by rememberSaveable { mutableIntStateOf(0) }
    var importStarted by rememberSaveable { mutableStateOf(false) }
    val primaryFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    // After the sources step: the ES-DE step when it exists, otherwise the tour is over.
    fun leaveSourcesStep() {
        if (viewModel.esdeInstalled) step = 4 else viewModel.finish()
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.updateDownloadDirectory(it.toString())
        }
    }
    val jsonPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importStarted = true; onImportSources(it.toString()) }
    }
    val esdePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.configureEsde(context, it.toString())
        }
    }

    // Once the import kicked off and its rescan is running (or already over), move on: the
    // consoles are already in Room, so the ES-DE step can use them while the rescan continues.
    LaunchedEffect(importStarted, isRescanning) {
        if (importStarted && isRescanning && step == 3) leaveSourcesStep()
    }
    // Focus the step's main action once the new content is laid out; if that fails, keep focus
    // on the root so B / Back are still caught here instead of leaving the app.
    LaunchedEffect(step) {
        repeat(2) { withFrameNanos { } }
        if (runCatching { primaryFocus.requestFocus() }.isFailure) runCatching { rootFocus.requestFocus() }
    }
    BackHandler(enabled = step > 0) { step-- }

    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.ButtonB || event.key == Key.Back) && step > 0) {
                    step--; true
                } else false
            }
    ) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                StepDots(step, totalSteps)
                when (step) {
                    0 -> {
                        Text(stringResource(R.string.onboarding_welcome_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.onboarding_welcome_body), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                        Bullet(stringResource(R.string.onboarding_welcome_point_1))
                        Bullet(stringResource(R.string.onboarding_welcome_point_2))
                        Bullet(stringResource(R.string.onboarding_welcome_point_3))
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OnboardingButton(stringResource(R.string.onboarding_start), primary = true, focus = primaryFocus) { step = 1 }
                        }
                    }
                    1 -> {
                        val chosen = downloadDirectory.isNotBlank()
                        Text(stringResource(R.string.onboarding_folder_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.onboarding_folder_body), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                        if (chosen) {
                            Text(
                                stringResource(R.string.onboarding_folder_current, FileParsingUtils.toUserReadablePath(downloadDirectory)),
                                style = MaterialTheme.typography.bodyMedium, color = scheme.primary
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (chosen) {
                                OnboardingButton(stringResource(R.string.onboarding_continue), primary = true, focus = primaryFocus) { step = 2 }
                                OnboardingButton(stringResource(R.string.onboarding_folder_change)) { folderPicker.launch(null) }
                            } else {
                                OnboardingButton(stringResource(R.string.onboarding_folder_choose), primary = true, focus = primaryFocus) { folderPicker.launch(null) }
                                OnboardingButton(stringResource(R.string.onboarding_skip)) { step = 2 }
                            }
                        }
                    }
                    2 -> {
                        Text(stringResource(R.string.onboarding_versions_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.onboarding_versions_body), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                        Text(stringResource(R.string.onboarding_versions_region), style = MaterialTheme.typography.titleSmall)
                        RegionPicker(stringResource(preferredRegion.labelRes)) { viewModel.setPreferredRegion(preferredRegion.cycled(it)) }
                        Text(stringResource(R.string.onboarding_versions_languages), style = MaterialTheme.typography.titleSmall)
                        LanguageChips((ONBOARDING_LANGUAGES + favoriteLanguages).distinct(), favoriteLanguages, viewModel::toggleLanguage)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OnboardingButton(stringResource(R.string.onboarding_continue), primary = true, focus = primaryFocus) { step = 3 }
                        }
                    }
                    3 -> {
                        Text(stringResource(R.string.onboarding_sources_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.onboarding_sources_body), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        if (importStarted) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.sources_rescanning), style = MaterialTheme.typography.bodyMedium)
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OnboardingButton(stringResource(R.string.onboarding_sources_import), primary = true, focus = primaryFocus) {
                                    jsonPicker.launch(arrayOf("application/json", "application/octet-stream", "text/*"))
                                }
                                OnboardingButton(
                                    stringResource(if (viewModel.esdeInstalled) R.string.onboarding_continue else R.string.onboarding_finish)
                                ) { leaveSourcesStep() }
                            }
                        }
                    }
                    else -> {
                        Text(stringResource(R.string.onboarding_esde_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.onboarding_esde_body), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        if (esdeBusy) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.onboarding_esde_configuring), style = MaterialTheme.typography.bodyMedium)
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OnboardingButton(stringResource(R.string.onboarding_esde_choose), primary = true, focus = primaryFocus) {
                                    esdePicker.launch(null)
                                }
                                OnboardingButton(stringResource(R.string.onboarding_finish)) { viewModel.finish() }
                            }
                        }
                    }
                }
            }
        }
        if (gamepadConnected) {
            GamepadLegend(
                entries = buildList {
                    add(LegendEntry("A", stringResource(R.string.onboarding_select)))
                    if (step > 0) add(LegendEntry("B", stringResource(R.string.onboarding_back)))
                }
            )
        }
    }
}

/** Languages offered on the versions step; whatever is already a favourite is added to them. */
private val ONBOARDING_LANGUAGES = listOf("EN", "ES", "FR", "DE", "IT", "PT", "NL", "SV", "JA", "ZH", "KO")

/** "◀ Spain ▶": ◀ ▶ step through the regions on the pad; A or a tap moves forward. */
@Composable
private fun RegionPicker(label: String, onStep: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(LocalDogmatixTokens.current.card)
            .focusRing(source)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onStep(-1); true }
                    Key.DirectionRight -> { onStep(1); true }
                    else -> false
                }
            }
            .clickable(interactionSource = source, indication = null) { onStep(1) }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("◀", color = scheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(min = 140.dp))
        Text("▶", color = scheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageChips(options: List<String>, selected: Set<String>, onToggle: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { tag ->
            val source = rememberFocusSource()
            val focused by source.collectIsFocusedAsState()
            val on = tag in selected
            // The accent ring would vanish on a ticked (accent) chip: draw it in the text colour there.
            val ring = if (!focused) Color.Transparent else if (on) scheme.onBackground else scheme.primary
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) scheme.primary else LocalDogmatixTokens.current.card)
                    .border(2.dp, ring, RoundedCornerShape(8.dp))
                    .clickable(interactionSource = source, indication = null) { onToggle(tag) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(tag, style = MaterialTheme.typography.labelLarge, color = if (on) scheme.onPrimary else scheme.onSurface)
            }
        }
    }
}

@Composable
private fun StepDots(current: Int, total: Int) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { i ->
            Box(
                modifier = Modifier
                    .size(width = if (i == current) 22.dp else 8.dp, height = 8.dp)
                    .clip(CircleShape)
                    .background(if (i == current) scheme.primary else scheme.surfaceContainerHighest)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.onboarding_step, current + 1, total),
            style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Bullet(text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.padding(top = 8.dp).size(6.dp).clip(CircleShape).background(scheme.primary))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
    }
}

@Composable
private fun OnboardingButton(
    label: String,
    primary: Boolean = false,
    focus: FocusRequester? = null,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val focused by source.collectIsFocusedAsState()
    // The accent ring would vanish on the accent-filled primary button: use the text colour there.
    val ring = if (!focused) Color.Transparent else if (primary) scheme.onBackground else scheme.primary
    Box(
        modifier = (focus?.let { Modifier.focusRequester(it) } ?: Modifier)
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (primary) scheme.primary else LocalDogmatixTokens.current.card)
            .border(2.dp, ring, RoundedCornerShape(8.dp))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (primary) scheme.onPrimary else scheme.onSurface
        )
    }
}
