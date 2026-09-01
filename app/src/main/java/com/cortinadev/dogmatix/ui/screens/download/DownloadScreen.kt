package com.cortinadev.dogmatix.ui.screens.download

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.GamepadButton
import com.cortinadev.dogmatix.ui.common.Legend
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus

@Composable
fun DownloadScreen(
    navController: NavController,
    viewModel: DownloadViewModel = hiltViewModel()
) {
    val downloads by viewModel.downloads.collectAsState()
    val details by viewModel.downloadDetails.collectAsState()
    val uploads by viewModel.uploads.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val showDeleteConfirmation by viewModel.showDeleteConfirmation.collectAsState()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val active = downloads.count { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }
    val completed = downloads.count { it.status == DownloadStatus.COMPLETED }
    val selectionMode = selection.isNotEmpty()
    val selected = remember(downloads, selection) { downloads.filter { it.fileName in selection } }

    // Row under the D-pad cursor: SELECT ticks it, X deletes it (the in-row buttons are touch-only —
    // they sit inside the focused row's bounds, out of reach of directional focus search).
    var focusedRow by remember { mutableStateOf<DownloadItemModel?>(null) }
    val barFocus = remember { FocusRequester() }
    var barFocused by remember { mutableStateOf(false) }
    // Clearing the selection takes the bar away under the cursor (B clears it from a row instead,
    // and that row keeps the focus). Park focus on the section tab once the new tree is laid out,
    // or Compose leaves it on the shell's invisible sink and the ring disappears.
    LaunchedEffect(selectionMode) {
        if (!selectionMode && focusedRow == null) {
            withFrameNanos { }
            withFrameNanos { }
            runCatching { Gamepad.sectionFocus.requestFocus() }
        }
    }
    LaunchedEffect(selectionMode) {
        Gamepad.presses.collect { button ->
            when (button) {
                // Select ticks the row under the cursor; that is what opens selection mode with a pad.
                GamepadButton.FAVOURITE -> focusedRow?.let { viewModel.toggleSelection(it.fileName) }
                GamepadButton.Y -> if (selectionMode) viewModel.toggleSelectAll()
                GamepadButton.X -> if (selectionMode) {
                    viewModel.deleteSelected()
                } else {
                    // Re-read the live status: it may have changed since the row took focus.
                    downloads.find { it.fileName == focusedRow?.fileName }?.let { row ->
                        if (row.status.canDelete) {
                            viewModel.deleteDownloadWithConfirmation(row.fileName, row.status == DownloadStatus.COMPLETED)
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    // B drops the selection before it hands focus back to the tabs.
    BackHandler(enabled = selectionMode) { viewModel.clearSelection() }

    val section = LegendEntry("ZL · ZR", stringResource(R.string.pad_section))
    val selectionLegend = if (barFocused) listOf(
        LegendEntry("A", stringResource(R.string.pad_apply)),
        LegendEntry("◀ ▶", stringResource(R.string.pad_change)),
        LegendEntry("B", stringResource(R.string.pad_cancel)), section
    ) else listOf(
        LegendEntry("A", stringResource(R.string.pad_tick)),
        LegendEntry("Y", stringResource(R.string.pad_select_all)),
        LegendEntry("X", stringResource(R.string.pad_delete)),
        LegendEntry("B", stringResource(R.string.pad_cancel)), section
    )
    val published = remember(selectionMode, selectionLegend) { if (selectionMode) Legend(selectionLegend) else null }
    LaunchedEffect(published) { Gamepad.legendOverride.value = published }
    // Only clear our own legend: another screen may already have published its own during the transition.
    DisposableEffect(published) { onDispose { if (Gamepad.legendOverride.value === published) Gamepad.legendOverride.value = null } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp, vertical = 12.dp)
    ) {
        // The section name already lives in the tabs; only the summary stays — and it gives
        // way to the bulk actions while rows are ticked.
        if (selectionMode) {
            SelectionBar(
                focusRequester = barFocus,
                onFocusChanged = { barFocused = it },
                selected = selected,
                details = details,
                compact = isLandscape,
                onRetry = viewModel::retrySelected,
                onPause = viewModel::pauseSelected,
                onStop = viewModel::stopSelected,
                onDelete = viewModel::deleteSelected,
                onClear = viewModel::clearSelection
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    stringResource(R.string.downloads_summary, active, completed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (downloads.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.downloads_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 6.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(downloads, key = { _, it -> it.fileName }) { index, item ->
                    DownloadItem(
                        item = item,
                        details = details[item.fileName],
                        upload = uploads[item.fileName],
                        compact = isLandscape,
                        viewModel = viewModel,
                        selectionMode = selectionMode,
                        selected = item.fileName in selection,
                        onToggleSelection = { viewModel.toggleSelection(item.fileName) },
                        focusUp = barFocus.takeIf { selectionMode && index == 0 },
                        onRowFocused = { row, focused ->
                            if (focused) focusedRow = row else if (focusedRow?.fileName == row.fileName) focusedRow = null
                        }
                    )
                }
            }
        }
    }

    showDeleteConfirmation?.let { fileNames ->
        AlertDialog(
            modifier = Modifier.closeOnGamepadB { viewModel.cancelDeleteConfirmation() },
            onDismissRequest = { viewModel.cancelDeleteConfirmation() },
            title = {
                Text(
                    text = if (fileNames.size > 1) stringResource(R.string.delete_downloads_title)
                    else stringResource(R.string.delete_download_title)
                )
            },
            text = {
                Text(
                    text = if (fileNames.size > 1) stringResource(R.string.delete_downloads_message, fileNames.size)
                    else stringResource(R.string.delete_download_message)
                )
            },
            confirmButton = {
                val delete = if (fileNames.size > 1) R.string.delete_files else R.string.delete_file
                DialogButton(stringResource(delete), onClick = { viewModel.confirmDeleteRemoveFile(fileNames) })
            },
            dismissButton = {
                val keep = if (fileNames.size > 1) R.string.keep_files else R.string.keep_file
                DialogButton(stringResource(keep), onClick = { viewModel.confirmDeleteKeepFile(fileNames) }, initialFocus = rememberInitialFocus())
            }
        )
    }
}

/**
 * Bulk actions for the ticked rows, in the summary's place. Each button only shows up when
 * some row in the selection accepts it, so nothing focusable is ever a no-op.
 */
@Composable
private fun SelectionBar(
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    selected: List<DownloadItemModel>,
    details: Map<String, DownloadableFileWithTags>,
    compact: Boolean,
    onRetry: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val size = if (compact) 36.dp else 44.dp
    // Only the actions some ticked row accepts, so nothing focusable is ever a no-op.
    val actions = buildList {
        if (selected.any { it.status.canRetry }) {
            add(BulkAction(R.drawable.ic_retry, stringResource(R.string.download_retry), scheme.onSurface, onRetry))
        }
        if (selected.any { it.status == DownloadStatus.DOWNLOADING && details[it.fileName]?.file?.isTorrent == true }) {
            add(BulkAction(R.drawable.ic_pause, stringResource(R.string.download_pause), scheme.onSurface, onPause))
        }
        if (selected.any { it.status.canStop }) {
            add(BulkAction(R.drawable.ic_stop, stringResource(R.string.download_cancel), scheme.onSurface, onStop))
        }
        if (selected.any { it.status.canDelete }) {
            add(BulkAction(R.drawable.ic_trash, stringResource(R.string.download_delete), scheme.error, onDelete))
        }
        add(BulkAction(R.drawable.ic_close, stringResource(R.string.selection_clear), scheme.onSurfaceVariant, onClear))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            .clip(RoundedCornerShape(8.dp))
            .background(scheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            pluralStringResource(R.plurals.downloads_selected, selected.size, selected.size),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(start = 6.dp)
        )
        actions.forEachIndexed { index, action ->
            ActionButton(
                action.icon, action.description, size, action.tint,
                // ▲ from the list lands on the first action, never on "clear selection".
                modifier = if (index == 0) Modifier.focusRequester(focusRequester) else Modifier,
                onClick = action.onClick
            )
        }
    }
}

private class BulkAction(val icon: Int, val description: String, val tint: Color, val onClick: () -> Unit)
