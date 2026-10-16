package com.cortinadev.dogmatix.ui.screens.collection.librarymove

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.Stepper
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.MoveProgressBlock
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.refocusOnChange
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.PaneText
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.SectionTitle
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.ToolButton
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.ToolButtons
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.ToolConfirmDialog
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.ToolRow
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.paneLegend
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.toolPane
import com.cortinadev.dogmatix.util.FileParsingUtils

/**
 * Collection → Move the library: the whole library folder, or the consoles picked here, to a
 * folder chosen with the system picker. Shows progress and can be cancelled; an interrupted
 * move is resumed from here. Downloads pause meanwhile, the settings follow the move, the
 * library is re-indexed and the history can move it all back.
 */
@Composable
fun LibraryMovePane(modifier: Modifier = Modifier, viewModel: LibraryMoveViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val root by viewModel.root.collectAsState()
    val ownFolders by viewModel.ownFolders.collectAsState()
    val destination by viewModel.destination.collectAsState()
    val destinationFree by viewModel.destinationFree.collectAsState()
    val whole by viewModel.whole.collectAsState()
    val consoles by viewModel.consoles.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val move by viewModel.move.collectAsState()
    var confirmStart by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            viewModel.setDestination(it.toString())
        }
    }

    if (confirmStart) {
        val what = if (whole) stringResource(R.string.move_what_whole)
        else consoles.orEmpty().filter { it.consoleId in selected }.let { chosen ->
            stringResource(R.string.move_what_consoles_summary, chosen.joinToString(", ") { it.name }, formatBytes(chosen.sumOf { it.bytes }))
        }
        ToolConfirmDialog(
            title = stringResource(R.string.move_confirm_title),
            message = stringResource(R.string.move_confirm_message, what, FileParsingUtils.toUserReadablePath(destination.orEmpty())),
            confirmText = stringResource(R.string.move_start),
            onConfirm = viewModel::start,
            onDismiss = { confirmStart = false }
        )
    }

    val running = move?.running == true
    val legend = paneLegend(LegendEntry("A", stringResource(R.string.pad_select)), LegendEntry("◀ ▶", stringResource(R.string.pad_change)))
    LazyColumn(
        modifier = modifier.fillMaxSize().refocusOnChange(move?.let { it.running to it.phase }, first).toolPane(first, legend),
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        move?.let { progress ->
            item(key = "move") {
                SectionTitle(stringResource(R.string.storage_move_section))
                MoveProgressBlock(
                    progress = progress,
                    onCancel = viewModel::cancel,
                    onResume = viewModel::resume,
                    onAbandon = viewModel::abandon,
                    onDismiss = viewModel::dismiss,
                    firstAction = first
                )
            }
        }
        item(key = "from") {
            SectionTitle(stringResource(R.string.move_from))
            ToolRow(
                title = FileParsingUtils.toUserReadablePath(root).ifEmpty { stringResource(R.string.settings_not_set) },
                subtitle = if (ownFolders > 0) pluralStringResource(R.plurals.move_own_folders, ownFolders, ownFolders) else null,
                onClick = {}
            )
        }
        item(key = "to") {
            SectionTitle(stringResource(R.string.move_to))
            ToolRow(
                title = destination?.let { FileParsingUtils.toUserReadablePath(it) } ?: stringResource(R.string.move_to_none),
                subtitle = destinationFree?.takeIf { destination != null }?.let { stringResource(R.string.move_to_free, formatBytes(it)) },
                onClick = { picker.launch(null) },
                enabled = !running,
                focusRequester = if (move == null) first else null
            ) {
                ToolButton(stringResource(R.string.move_choose), { picker.launch(null) }, enabled = !running)
            }
        }
        if (destination != null) {
            item(key = "what") {
                ToolRow(
                    title = stringResource(R.string.move_what),
                    subtitle = stringResource(R.string.move_what_hint),
                    onClick = { viewModel.setWhole(!whole) },
                    onAdjust = { viewModel.setWhole(!whole) }
                ) {
                    Stepper(
                        stringResource(if (whole) R.string.move_what_whole else R.string.move_what_consoles),
                        onDecrement = { viewModel.setWhole(!whole) },
                        onIncrement = { viewModel.setWhole(!whole) },
                        valueWidth = 140.dp
                    )
                }
            }
            if (!whole) {
                val list = consoles
                if (list == null) {
                    item(key = "measuring") { PaneText(stringResource(R.string.storage_measuring)) }
                } else {
                    items(list, key = { it.consoleId }) { console ->
                        val ticked = console.consoleId in selected
                        ToolRow(
                            title = console.name,
                            subtitle = console.path + "  ·  " + formatBytes(console.bytes),
                            onClick = { viewModel.toggle(console.consoleId) }
                        ) {
                            if (ticked) Icon(painterResource(R.drawable.ic_check), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            item(key = "start") {
                PaneText(stringResource(R.string.storage_frontends_warning))
                ToolButtons {
                    ToolButton(
                        stringResource(R.string.move_start),
                        { confirmStart = true },
                        enabled = !running && (whole || selected.isNotEmpty())
                    )
                    if (running) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}
