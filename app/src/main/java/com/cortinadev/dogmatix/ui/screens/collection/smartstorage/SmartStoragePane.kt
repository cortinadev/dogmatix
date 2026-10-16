package com.cortinadev.dogmatix.ui.screens.collection.smartstorage

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.ConsoleStorage
import com.cortinadev.dogmatix.data.service.StorageVolume
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.MoveProgressBlock
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.refocusOnChange
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.PaneText
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.SectionTitle
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.ToolButton
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.ToolButtons
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.ToolConfirmDialog
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.ToolRow
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.UsageBar
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.paneLegend
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.toolPane
import com.cortinadev.dogmatix.util.SmartStorage

/**
 * Collection → Smart storage: a second library root on the SD card. Shows both volumes, which
 * root each console's folder is on and how big it is, suggests moving the big consoles off a
 * tight internal storage, and moves a console with A (after a confirmation). Downloads that do
 * not fit go to the other root on their own. Moving folders breaks the frontends' paths, so the
 * pane offers to run their setup again.
 */
@Composable
fun SmartStoragePane(modifier: Modifier = Modifier, viewModel: SmartStorageViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val secondRoot by viewModel.secondRoot.collectAsState()
    val scan by viewModel.scan.collectAsState()
    val measuring by viewModel.measuring.collectAsState()
    val move by viewModel.move.collectAsState()
    val reconfiguring by viewModel.reconfiguring.collectAsState()
    var confirmMove by remember { mutableStateOf<ConsoleStorage?>(null) }
    var confirmSuggestions by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            viewModel.setSecondRoot(it.toString())
        }
    }

    confirmMove?.let { console ->
        val to = SmartStorage.other(console.place)
        ToolConfirmDialog(
            title = stringResource(R.string.storage_move_title, console.name, placeName(to, scan?.main, scan?.second)),
            message = stringResource(R.string.storage_move_message, formatBytes(console.bytes)),
            confirmText = stringResource(R.string.storage_move),
            onConfirm = { viewModel.moveToOther(console) },
            onDismiss = { confirmMove = null }
        )
    }
    if (confirmSuggestions) {
        ToolConfirmDialog(
            title = stringResource(R.string.storage_suggestions_title),
            message = (scan?.let { viewModel.suggestionSummary(it) }.orEmpty()) + "\n\n" + stringResource(R.string.storage_frontends_warning),
            confirmText = stringResource(R.string.storage_move),
            onConfirm = viewModel::applySuggestions,
            onDismiss = { confirmSuggestions = false }
        )
    }

    val legend = paneLegend(LegendEntry("A", stringResource(R.string.storage_pad_move)))
    val busy = move?.running == true
    LazyColumn(
        modifier = modifier.fillMaxSize().refocusOnChange(Triple(move?.running, move?.phase, measuring == null), first).toolPane(first, legend),
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        move?.let { progress ->
            item(key = "move") {
                SectionTitle(stringResource(R.string.storage_move_section))
                MoveProgressBlock(
                    progress = progress,
                    onCancel = viewModel::cancelMove,
                    onResume = viewModel::resumeMove,
                    onAbandon = viewModel::abandonMove,
                    onDismiss = viewModel::dismissMove,
                    firstAction = first
                )
            }
        }
        item(key = "volumes-title") { SectionTitle(stringResource(R.string.storage_volumes)) }
        if (secondRoot.isBlank()) {
            item(key = "intro") {
                PaneText(stringResource(R.string.storage_intro))
                ToolButtons { ToolButton(stringResource(R.string.storage_pick_second), { picker.launch(null) }, focusRequester = if (move == null) first else null) }
            }
        } else {
            item(key = "main") { VolumeRow(stringResource(R.string.storage_main_root), scan?.main) }
            item(key = "second") {
                VolumeRow(stringResource(R.string.storage_second_root), scan?.second, onChange = { picker.launch(null) }, focusRequester = if (move == null) first else null)
            }
        }
        item(key = "frontends") {
            SectionTitle(stringResource(R.string.storage_frontends))
            PaneText(stringResource(R.string.storage_frontends_warning))
            ToolButtons {
                ToolButton(stringResource(R.string.storage_frontends_reconfigure), viewModel::reconfigureFrontends, enabled = !reconfiguring && !busy)
                if (reconfiguring) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
        if (secondRoot.isNotBlank()) {
            item(key = "consoles-title") {
                SectionTitle(stringResource(R.string.storage_consoles))
                measuring?.let { (done, total) ->
                    PaneText(if (total > 0) stringResource(R.string.storage_measuring_progress, done, total) else stringResource(R.string.storage_measuring))
                }
                val summary = scan?.let { viewModel.suggestionSummary(it) }
                if (summary != null) {
                    PaneText(summary, color = MaterialTheme.colorScheme.primary)
                    ToolButtons { ToolButton(stringResource(R.string.storage_apply_suggestions), { confirmSuggestions = true }, enabled = !busy) }
                } else if (scan != null && measuring == null) {
                    PaneText(stringResource(R.string.storage_no_suggestions))
                }
            }
            val consoles = scan?.consoles.orEmpty()
            items(consoles, key = { it.consoleId }) { console ->
                val suggested = scan?.suggestions?.any { it.consoleId == console.consoleId } == true
                val parts = mutableListOf(placeName(console.place, scan?.main, scan?.second), formatBytes(console.bytes))
                if (console.strayBytes > 0) {
                    parts += stringResource(R.string.storage_stray, formatBytes(console.strayBytes), placeName(SmartStorage.other(console.place), scan?.main, scan?.second))
                }
                ToolRow(
                    title = console.name,
                    subtitle = parts.joinToString("  ·  "),
                    onClick = { confirmMove = console },
                    enabled = !busy
                ) {
                    if (suggested) {
                        Text(
                            stringResource(R.string.storage_suggested, placeName(SmartStorage.other(console.place), scan?.main, scan?.second)),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            item(key = "refresh") {
                ToolButtons { ToolButton(stringResource(R.string.storage_refresh), viewModel::refresh, enabled = measuring == null) }
            }
        }
    }
}

/** "Internal storage" / "SD card" for the root at [place]. */
@Composable
private fun placeName(place: SmartStorage.Place, main: StorageVolume?, second: StorageVolume?): String {
    val volume = if (place == SmartStorage.Place.MAIN) main else second
    return when (volume?.internal) {
        true -> stringResource(R.string.storage_internal)
        false -> stringResource(R.string.storage_sd)
        null -> stringResource(if (place == SmartStorage.Place.MAIN) R.string.storage_main_root else R.string.storage_second_root)
    }
}

@Composable
private fun VolumeRow(title: String, volume: StorageVolume?, onChange: (() -> Unit)? = null, focusRequester: FocusRequester? = null) {
    val free = volume?.freeBytes
    val total = volume?.totalBytes
    val subtitle = buildList {
        volume?.path?.let(::add)
        if (free != null && total != null) add(stringResource(R.string.storage_free_of, formatBytes(free), formatBytes(total)))
    }.joinToString("  ·  ")
    Column {
        ToolRow(
            title = title + (volume?.let { "  ·  " + stringResource(if (it.internal) R.string.storage_internal else R.string.storage_sd) } ?: ""),
            subtitle = subtitle.ifEmpty { null },
            onClick = { onChange?.invoke() },
            focusRequester = focusRequester
        ) {
            onChange?.let { ToolButton(stringResource(R.string.storage_change), it) }
        }
        if (free != null && total != null && total > 0) {
            UsageBar((total - free).toFloat() / total, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp))
        }
    }
}
