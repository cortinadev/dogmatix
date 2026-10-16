package com.cortinadev.dogmatix.ui.screens.collection.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.entity.ActionHistoryEntity
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.Stepper
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.PaneText
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.SectionTitle
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.ToolButton
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.ToolConfirmDialog
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.ToolRow
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.paneLegend
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.toolPane
import com.cortinadev.dogmatix.util.ActionState
import com.cortinadev.dogmatix.util.ActionTypes
import com.cortinadev.dogmatix.util.UndoKinds
import java.text.DateFormat
import java.util.Date

/**
 * Collection → History: what the app deleted, moved or replaced, newest first. A on an entry
 * undoes it (after a confirmation that starts on Cancel); entries that can no longer be undone
 * are dimmed. On top, the trash: what it holds, when it empties itself (◀ ▶) and "Empty trash".
 */
@Composable
fun ActionHistoryPane(modifier: Modifier = Modifier, viewModel: ActionHistoryViewModel = hiltViewModel()) {
    val rows by viewModel.rows.collectAsState()
    val trash by viewModel.trash.collectAsState()
    val retention by viewModel.retentionDays.collectAsState()
    val emptying by viewModel.emptying.collectAsState()
    var confirmUndo by remember { mutableStateOf<ActionHistoryEntity?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    val firstEntry = remember { FocusRequester() }
    val trashRow = remember { FocusRequester() }

    confirmUndo?.let { entry ->
        ToolConfirmDialog(
            title = stringResource(R.string.history_undo_title, entryTitle(entry)),
            message = stringResource(undoExplanation(entry)),
            confirmText = stringResource(R.string.history_undo),
            onConfirm = { viewModel.undo(entry.id) },
            onDismiss = { confirmUndo = null }
        )
    }
    if (confirmEmpty) {
        ToolConfirmDialog(
            title = stringResource(R.string.history_trash_empty_title),
            message = stringResource(R.string.history_trash_empty_message, trash.items, formatBytes(trash.bytes)),
            confirmText = stringResource(R.string.history_trash_empty),
            onConfirm = viewModel::emptyTrash,
            onDismiss = { confirmEmpty = false }
        )
    }

    val legend = paneLegend(LegendEntry("A", stringResource(R.string.history_undo)), LegendEntry("◀ ▶", stringResource(R.string.pad_change)))
    LazyColumn(
        modifier = modifier.fillMaxSize().toolPane(if (rows.isEmpty()) trashRow else firstEntry, legend),
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item(key = "trash-title") { SectionTitle(stringResource(R.string.history_trash)) }
        item(key = "trash") {
            ToolRow(
                title = if (trash.items == 0) stringResource(R.string.history_trash_none)
                else pluralStringResource(R.plurals.history_trash_items, trash.items, trash.items, formatBytes(trash.bytes)),
                subtitle = stringResource(R.string.history_trash_hint),
                onClick = { if (trash.items > 0) confirmEmpty = true },
                enabled = trash.items > 0 && !emptying,
                focusRequester = trashRow
            ) {
                if (emptying) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else ToolButton(stringResource(R.string.history_trash_empty), onClick = { confirmEmpty = true }, enabled = trash.items > 0)
            }
        }
        item(key = "retention") {
            ToolRow(
                title = stringResource(R.string.history_retention),
                subtitle = stringResource(R.string.history_retention_hint),
                onClick = { viewModel.cycleRetention(1) },
                onAdjust = viewModel::cycleRetention
            ) {
                Stepper(
                    pluralStringResource(R.plurals.history_days, retention, retention),
                    onDecrement = { viewModel.cycleRetention(-1) },
                    onIncrement = { viewModel.cycleRetention(1) },
                    valueWidth = 96.dp
                )
            }
        }
        item(key = "entries-title") { SectionTitle(stringResource(R.string.history_entries)) }
        if (rows.isEmpty()) {
            item(key = "empty") { PaneText(stringResource(R.string.history_empty)) }
        }
        items(rows, key = { it.entry.id }) { row ->
            val entry = row.entry
            ToolRow(
                title = entryTitle(entry),
                subtitle = entryDetail(row),
                subtitleColor = if (ActionState.of(entry.state) == ActionState.UNDO_FAILED) MaterialTheme.colorScheme.error else null,
                onClick = { if (row.undoable) confirmUndo = entry },
                enabled = row.undoable,
                focusRequester = if (row == rows.first()) firstEntry else null
            ) {
                if (row.running || ActionState.of(entry.state) == ActionState.UNDOING) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun entryTitle(entry: ActionHistoryEntity): String {
    val label = when (entry.type) {
        ActionTypes.DOWNLOAD_DELETE -> R.string.history_type_download_delete
        ActionTypes.LIBRARY_DELETE -> R.string.history_type_library_delete
        ActionTypes.LIBRARY_MOVE -> R.string.history_type_library_move
        ActionTypes.SMART_STORAGE -> R.string.history_type_smart_storage
        ActionTypes.SOURCES_IMPORT -> R.string.history_type_sources_import
        ActionTypes.COLLECTION_DELETE -> R.string.history_type_collection_delete
        ActionTypes.DUPLICATES -> R.string.history_type_duplicates
        ActionTypes.REPAIR -> R.string.history_type_repair
        ActionTypes.FREE_SPACE -> R.string.history_type_free_space
        else -> null
    }
    return when {
        label == null -> entry.subject
        entry.subject.isBlank() -> stringResource(label)
        else -> stringResource(R.string.history_title_format, stringResource(label), entry.subject)
    }
}

@Composable
private fun entryDetail(row: HistoryRow): String {
    val entry = row.entry
    val parts = mutableListOf(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.createdAt)))
    if (entry.itemCount > 0) parts += pluralStringResource(R.plurals.history_items, entry.itemCount, entry.itemCount)
    if (entry.bytes > 0) parts += formatBytes(entry.bytes)
    val state = when (ActionState.of(entry.state)) {
        ActionState.UNDONE -> stringResource(R.string.history_state_undone)
        ActionState.UNDO_FAILED -> stringResource(R.string.history_state_failed)
        ActionState.UNDOING -> stringResource(R.string.history_state_undoing)
        ActionState.EXPIRED -> stringResource(R.string.history_state_expired)
        ActionState.SUPERSEDED -> stringResource(R.string.history_state_superseded)
        ActionState.DONE -> when {
            entry.undoKind == null -> stringResource(R.string.history_state_final)
            !row.undoable -> stringResource(R.string.history_state_unavailable)
            else -> null
        }
    }
    state?.let { parts += it }
    return parts.joinToString("  ·  ")
}

private fun undoExplanation(entry: ActionHistoryEntity): Int = when (entry.undoKind) {
    UndoKinds.TRASH -> R.string.history_undo_trash
    UndoKinds.MOVE -> R.string.history_undo_move
    UndoKinds.SOURCES -> R.string.history_undo_sources
    UndoKinds.COLLECTION -> R.string.history_undo_collection
    else -> R.string.history_undo_generic
}
