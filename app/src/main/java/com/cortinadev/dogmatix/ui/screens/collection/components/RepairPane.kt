package com.cortinadev.dogmatix.ui.screens.collection.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.GamepadButton
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.screens.collection.CollectionNav
import com.cortinadev.dogmatix.ui.screens.collection.CollectionViewModel
import com.cortinadev.dogmatix.util.CollectionRepair
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.RepairAction
import com.cortinadev.dogmatix.util.RepairKind

/**
 * Repair: the Health findings that can be fixed without a choice, all ticked. A ticks or unticks
 * one, Y all of them, X (or the button) shows what will happen and asks, with Cancel focused.
 */
@Composable
fun RepairPane(viewModel: CollectionViewModel, active: Boolean, entryFocus: FocusRequester, nav: CollectionNav, modifier: Modifier = Modifier) {
    AnalysisGate(viewModel, active, entryFocus, nav) { analysis ->
        val unticked by viewModel.repairUnticked.collectAsState()
        val working by viewModel.working.collectAsState()
        val repairs = analysis.repairs
        val ticked = repairs.filter { it.id !in unticked }
        var confirm by remember { mutableStateOf<List<RepairAction>?>(null) }

        val currentTicked by rememberUpdatedState(ticked)
        LaunchedEffect(active) {
            if (!active) return@LaunchedEffect
            Gamepad.presses.collect { button ->
                when (button) {
                    GamepadButton.Y -> viewModel.toggleAllRepairs()
                    GamepadButton.X -> if (currentTicked.isNotEmpty() && !viewModel.working.value) confirm = currentTicked
                    else -> Unit
                }
            }
        }
        PublishLegend(
            listOf(
                LegendEntry("A", stringResource(R.string.pad_tick)),
                LegendEntry("Y", stringResource(R.string.pad_select_all)),
                LegendEntry("X", stringResource(R.string.pad_repair))
            ) + paneLegendTail(),
            active
        )

        LazyColumn(modifier = modifier, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            item {
                PaneHeader(
                    if (repairs.isEmpty()) stringResource(R.string.collection_repair_none)
                    else pluralStringResource(R.plurals.collection_repair_summary, repairs.size, repairs.size, ticked.size)
                ) {
                    if (repairs.isNotEmpty()) {
                        CollectionButton(
                            pluralStringResource(R.plurals.collection_repair_apply, ticked.size, ticked.size),
                            onClick = { if (ticked.isNotEmpty()) confirm = ticked },
                            enabled = ticked.isNotEmpty() && !working,
                            focus = entryFocus
                        )
                    }
                }
            }
            itemsIndexed(repairs, key = { _, it -> it.id }) { _, action ->
                CollectionRow(
                    title = action.subject.ifEmpty { action.finding.folder?.name.orEmpty() },
                    subtitle = repairDetail(action),
                    selected = action.id !in unticked,
                    leading = { TickBox(action.id !in unticked) },
                    onClick = { viewModel.toggleRepair(action.id) }
                )
            }
        }

        confirm?.let { actions ->
            ConfirmDialog(
                title = pluralStringResource(R.plurals.collection_repair_confirm_title, actions.size, actions.size),
                lines = CollectionRepair.counts(actions).map { (kind, n) -> repairCountLine(kind, n, actions.filter { it.kind == kind }.sumOf { it.bytes }) },
                confirmLabel = stringResource(R.string.collection_repair),
                onConfirm = {
                    confirm = null
                    viewModel.applyRepairs(actions)
                },
                onDismiss = { confirm = null }
            )
        }
    }
}

/** What one repair will do, in a line. */
@Composable
private fun repairDetail(action: RepairAction): String {
    val finding = action.finding
    val where = location(finding.files.firstOrNull())
    return when (action.kind) {
        RepairKind.CREATE_PLAYLIST -> stringResource(R.string.collection_repair_playlist, finding.playlist?.name.orEmpty(), finding.count)
        RepairKind.MOVE_TO_CONSOLE -> stringResource(R.string.collection_repair_move, where, finding.targetConsoleId?.let { ConsoleFormatter.getConsoleShortName(it) }.orEmpty())
        RepairKind.DELETE_EMPTY_FILE -> stringResource(R.string.collection_repair_delete_empty_file, where)
        RepairKind.DELETE_PARTIAL -> stringResource(R.string.collection_repair_delete_partial, where, formatBytes(action.bytes))
        RepairKind.DELETE_EXTRACTED_ARCHIVE -> stringResource(R.string.collection_repair_delete_archive, where, formatBytes(action.bytes))
        RepairKind.DELETE_EMPTY_FOLDER -> stringResource(R.string.collection_repair_delete_folder, consoleLabel(finding.consoleId, finding.folder?.topFolder.orEmpty()))
    }
}

/** The confirmation's preview, one line per kind: "Delete 3 empty folders". */
@Composable
private fun repairCountLine(kind: RepairKind, count: Int, bytes: Long): String {
    val line = pluralStringResource(
        when (kind) {
            RepairKind.CREATE_PLAYLIST -> R.plurals.collection_repair_count_playlist
            RepairKind.MOVE_TO_CONSOLE -> R.plurals.collection_repair_count_move
            RepairKind.DELETE_EMPTY_FILE -> R.plurals.collection_repair_count_empty_file
            RepairKind.DELETE_PARTIAL -> R.plurals.collection_repair_count_partial
            RepairKind.DELETE_EXTRACTED_ARCHIVE -> R.plurals.collection_repair_count_archive
            RepairKind.DELETE_EMPTY_FOLDER -> R.plurals.collection_repair_count_folder
        },
        count, count
    )
    return if (bytes > 0) "$line  ·  ${formatBytes(bytes)}" else line
}
