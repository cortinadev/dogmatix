package com.cortinadev.dogmatix.ui.screens.download

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.service.QueueState
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.GamepadButton
import com.cortinadev.dogmatix.ui.common.Legend
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.util.DownloadBulk
import com.cortinadev.dogmatix.util.DownloadFilter
// busqueda
import com.cortinadev.dogmatix.data.search.SearchTarget
import com.cortinadev.dogmatix.ui.navigation.SearchTargetEffect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun DownloadScreen(
    navController: NavController,
    viewModel: DownloadViewModel = hiltViewModel()
) {
    val downloads by viewModel.downloads.collectAsState()
    val details by viewModel.downloadDetails.collectAsState()
    val games by viewModel.downloadGames.collectAsState()
    val showCovers by viewModel.showListCovers.collectAsState()
    val uploads by viewModel.uploads.collectAsState()
    val failures by viewModel.failures.collectAsState()
    val retries by viewModel.nextRetryAt.collectAsState()
    val restartsFromZero by viewModel.restartsFromZero.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val showDeleteConfirmation by viewModel.showDeleteConfirmation.collectAsState()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val selectionMode = selection.isNotEmpty()
    val selected = remember(downloads, selection) { downloads.filter { it.fileName in selection } }
    // dl-cola: the queue, the search and the status chips.
    val view by viewModel.view.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val query by viewModel.query.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val confirmClearCompleted by viewModel.confirmClearCompleted.collectAsState()
    val pendingFilter by viewModel.pendingFilter.collectAsState()
    LaunchedEffect(pendingFilter) { if (pendingFilter != null) viewModel.applyPendingFilter() }

    // Row under the D-pad cursor: SELECT ticks it, X deletes it (the in-row buttons are touch-only —
    // they sit inside the focused row's bounds, out of reach of directional focus search).
    var focusedRow by remember { mutableStateOf<DownloadItemModel?>(null) }
    val barFocus = remember { FocusRequester() }
    var barFocused by remember { mutableStateOf(false) }
    val chipFocus = remember { FocusRequester() }
    val firstActionFocus = remember { FocusRequester() }
    val firstRowFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    var chipsFocused by remember { mutableStateOf(false) }
    var focusedAction by remember { mutableStateOf<QueueAction?>(null) }
    var searchActive by remember { mutableStateOf(false) }
    // Reorder mode: the waiting row whose place ▲ ▼ change (START or its ⇅ button; A or B ends it).
    var moving by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val focusList = { if (runCatching { firstRowFocus.requestFocus() }.isFailure) focusManager.moveFocus(FocusDirection.Down) }
    // RB lands on the first row on screen: row 0 is not composed once the list has scrolled past it,
    // and a requester without a node does nothing.
    val firstVisibleRow by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    // Clearing the selection takes the bar away under the cursor (B clears it from a row instead,
    // and that row keeps the focus). Park focus on the section tab once the new tree is laid out,
    // or Compose leaves it on the shell's invisible sink and the ring disappears.
    LaunchedEffect(selectionMode) {
        if (selectionMode) moving = null
        if (!selectionMode && focusedRow == null && !chipsFocused && focusedAction == null) {
            withFrameNanos { }
            withFrameNanos { }
            runCatching { Gamepad.sectionFocus.requestFocus() }
        }
    }
    // The row started, was paused or deleted: nothing left to reorder.
    LaunchedEffect(moving, queue) { if (moving != null && moving !in queue.waitingSet) moving = null }

    val actions = queueActions(downloads, view.rows, queue, viewModel)
    val currentActions by rememberUpdatedState(actions)
    // A whole-list button can vanish under the cursor (Pause all leaves nothing to pause): the
    // next button, or the chips, take the focus instead of the shell's invisible sink. The buttons
    // may change twice in a row (Pause all goes, then Resume all comes as the rows land on PAUSED):
    // a pending hand-over survives the restart of this effect.
    var refocusActions by remember { mutableStateOf(false) }
    LaunchedEffect(actions.map { it.label }) {
        if (refocusActions || (focusedAction != null && actions.none { it.label == focusedAction?.label })) {
            refocusActions = true
            focusedAction = null
            withFrameNanos { }
            withFrameNanos { }
            runCatching { if (actions.isNotEmpty()) firstActionFocus.requestFocus() else chipFocus.requestFocus() }
            refocusActions = false
        }
    }

    LaunchedEffect(selectionMode) {
        Gamepad.presses.collect { button ->
            val reordering = moving
            when (button) {
                // Select ticks the row under the cursor; that is what opens selection mode with a pad.
                GamepadButton.FAVOURITE -> if (reordering == null) focusedRow?.let { viewModel.toggleSelection(it.fileName) }
                GamepadButton.Y -> when {
                    reordering != null -> viewModel.toggleHold(reordering)
                    selectionMode -> viewModel.toggleSelectAll()
                    // No list, no search box to open.
                    downloads.isNotEmpty() -> searchActive = true
                    else -> Unit
                }
                GamepadButton.X -> if (reordering != null) {
                    Unit
                } else if (selectionMode) {
                    viewModel.deleteSelected()
                } else {
                    // Re-read the live status: it may have changed since the row took focus.
                    downloads.find { it.fileName == focusedRow?.fileName }?.let { row ->
                        if (row.status.canDelete) {
                            viewModel.deleteDownloadWithConfirmation(row.fileName, row.status == DownloadStatus.COMPLETED)
                        }
                    }
                }
                // START puts the row under the cursor in reorder mode (only rows waiting in line).
                GamepadButton.START -> when {
                    reordering != null -> moving = null
                    !selectionMode -> focusedRow?.fileName?.takeIf { it in queue.waitingSet }?.let { moving = it }
                    else -> Unit
                }
                // LB: the chips (filters ⇄ list, like the library); while reordering, first in line.
                GamepadButton.PREV_PANEL -> if (reordering != null) viewModel.moveToFront(reordering) else runCatching { chipFocus.requestFocus() }
                GamepadButton.NEXT_PANEL -> if (reordering == null) focusList()
                else -> Unit
            }
        }
    }

    // B undoes one layer at a time: reorder mode, the selection, the search text, the chip.
    BackHandler(enabled = moving != null || selectionMode || query.isNotEmpty() || filter != DownloadFilter.ALL) {
        when {
            moving != null -> moving = null
            selectionMode -> viewModel.clearSelection()
            query.isNotEmpty() -> viewModel.setQuery("")
            else -> viewModel.setFilter(DownloadFilter.ALL)
        }
    }

    // The moved row stays on screen: scroll it back in when a move takes it past an edge.
    val movingIndex = moving?.let { name -> view.rows.indexOfFirst { it.fileName == name } } ?: -1
    LaunchedEffect(movingIndex) {
        if (movingIndex < 0) return@LaunchedEffect
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.index == movingIndex }
        when {
            item == null -> listState.scrollToItem((movingIndex - 1).coerceAtLeast(0))
            item.offset < layout.viewportStartOffset ->
                listState.animateScrollBy((item.offset - layout.viewportStartOffset).toFloat())
            item.offset + item.size > layout.viewportEndOffset ->
                listState.animateScrollBy((item.offset + item.size - layout.viewportEndOffset).toFloat())
        }
    }

    // busqueda: a library search result lands on its row.
    val targetRowFocus = remember { FocusRequester() }
    var searchRow by remember { mutableStateOf<String?>(null) }
    SearchTargetEffect<SearchTarget.Download> { target ->
        val wanted = { rows: List<DownloadItemModel> -> rows.any { it.fileName == target.fileName } }
        withTimeoutOrNull(3_000) { viewModel.downloads.first(wanted) } ?: return@SearchTargetEffect
        // The list's own search or chip may hide the row: drop them (and any reorder) first.
        moving = null
        if (!wanted(viewModel.view.value.rows)) {
            searchActive = false
            viewModel.setQuery("")
            viewModel.setFilter(DownloadFilter.ALL)
        }
        val rows = withTimeoutOrNull(1_000) { viewModel.view.first { wanted(it.rows) } }?.rows ?: return@SearchTargetEffect
        searchRow = target.fileName
        listState.scrollToItem(rows.indexOfFirst { it.fileName == target.fileName })
        // After the effect above that parks the focus on the tab when the screen opens.
        repeat(3) { withFrameNanos { } }
        runCatching { targetRowFocus.requestFocus() }
    }

    val section = LegendEntry("ZL · ZR", stringResource(R.string.pad_section))
    val search = LegendEntry("Y", stringResource(R.string.pad_search))
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
    val liveRow = focusedRow?.let { row -> downloads.find { it.fileName == row.fileName } }
    val rowQueued = liveRow != null && liveRow.fileName in queue.waitingSet
    val legend = when {
        moving != null -> listOf(
            LegendEntry("▲ ▼", stringResource(R.string.pad_move)),
            LegendEntry("LB", stringResource(R.string.pad_first)),
            LegendEntry("Y", stringResource(if (moving in queue.held) R.string.pad_release else R.string.pad_hold)),
            LegendEntry("A", stringResource(R.string.pad_done))
        )
        searchActive -> listOf(
            LegendEntry("A", stringResource(R.string.pad_keyboard)), LegendEntry("B", stringResource(R.string.pad_close_keyboard))
        )
        selectionMode -> selectionLegend
        focusedAction != null -> listOf(
            LegendEntry("A", focusedAction?.label.orEmpty()), LegendEntry("LB · RB", stringResource(R.string.pad_panels)), search, section
        )
        chipsFocused -> listOf(
            LegendEntry("A", stringResource(R.string.pad_select)), LegendEntry("◀ ▶", stringResource(R.string.pad_change)),
            LegendEntry("RB", stringResource(R.string.pad_panels)), search, section
        )
        // A row: what A does to it, then the rest; a queued row trades the section hint for START.
        else -> listOfNotNull(
            LegendEntry("A", primaryLabel(liveRow, queue)),
            // X deletes paused, stopped, failed and completed rows; a transfer is paused or stopped first.
            if (liveRow == null || liveRow.status.canDelete) LegendEntry("X", stringResource(R.string.pad_delete)) else null,
            if (rowQueued) LegendEntry("START", stringResource(R.string.pad_order)) else null,
            search,
            LegendEntry("SELECT", stringResource(R.string.pad_tick)),
            if (rowQueued) null else section
        )
    }
    // Only while this is the current section: leaving it (a search result in another section) the
    // focus goes and the legend changes while this screen is still composed, which would replace
    // the new section's legend, and the dispose that follows would leave none.
    val onThisTab = navController.currentBackStackEntryAsState().value?.destination?.route == NavRoutes.Downloads.route
    val published = remember(legend, onThisTab) { if (onThisTab) Legend(legend) else null }
    LaunchedEffect(published) { if (published != null) Gamepad.legendOverride.value = published }
    // Only clear our own legend: another screen may already have published its own during the transition.
    DisposableEffect(published) { onDispose { if (published != null && Gamepad.legendOverride.value === published) Gamepad.legendOverride.value = null } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp, vertical = 12.dp)
    ) {
        // The section name already lives in the tabs. The search gives way to the bulk
        // actions while rows are ticked; the chips stay.
        if (downloads.isNotEmpty()) {
            QueueHeader(
                landscape = isLandscape,
                selectionBar = if (!selectionMode) null else ({
                    SelectionBar(
                        focusRequester = barFocus,
                        onFocusChanged = { barFocused = it },
                        selected = selected,
                        details = details,
                        compact = isLandscape,
                        queue = queue,
                        onRetry = viewModel::retrySelected,
                        onPause = viewModel::pauseSelected,
                        onStop = viewModel::stopSelected,
                        onFirst = viewModel::moveSelectedToFront,
                        onHold = viewModel::holdSelected,
                        onRelease = viewModel::releaseSelected,
                        onDelete = viewModel::deleteSelected,
                        onClear = viewModel::clearSelection
                    )
                }),
                query = query,
                onQueryChange = viewModel::setQuery,
                searchActive = searchActive,
                onSearchActivate = { searchActive = true },
                onSearchDismiss = {
                    searchActive = false
                    if (view.rows.isNotEmpty()) focusList() else runCatching { chipFocus.requestFocus() }
                },
                searchFocus = searchFocus,
                filter = filter,
                counts = view.counts,
                onFilter = viewModel::setFilter,
                chipFocus = chipFocus,
                // Landscape: the buttons share the chips' line and the tabs are right above.
                chipsUp = when {
                    selectionMode -> barFocus
                    isLandscape -> null
                    else -> firstActionFocus.takeIf { actions.isNotEmpty() }
                },
                onChipsFocused = { chipsFocused = it },
                actions = actions,
                firstActionFocus = firstActionFocus,
                onActionFocused = { action, focused ->
                    // By label: the actions are rebuilt on every recomposition (each progress tick).
                    // A button that vanished under the cursor keeps it, so the effect above can
                    // hand the focus to the next one.
                    if (focused) focusedAction = action
                    else if (focusedAction?.label == action.label && currentActions.any { it.label == action.label }) focusedAction = null
                }
            )
        }
        if (downloads.isEmpty() || view.rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (downloads.isEmpty()) R.string.downloads_empty else R.string.downloads_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    // Reorder mode: ▲ ▼ move the row instead of the focus; A ends it (on release,
                    // so the row's own A never fires) and ◀ ▶ stay put.
                    .onPreviewKeyEvent { event ->
                        val name = moving ?: return@onPreviewKeyEvent false
                        val down = event.type == KeyEventType.KeyDown
                        when (event.key) {
                            Key.DirectionUp -> { if (down) viewModel.moveUp(name); true }
                            Key.DirectionDown -> { if (down) viewModel.moveDown(name); true }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA -> {
                                if (event.type == KeyEventType.KeyUp) moving = null
                                true
                            }
                            Key.DirectionLeft, Key.DirectionRight -> true
                            else -> false
                        }
                    },
                contentPadding = PaddingValues(top = 6.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(view.rows, key = { _, it -> it.fileName }) { index, item ->
                    val name = item.fileName
                    DownloadItem(
                        item = item,
                        details = details[item.fileName],
                        upload = uploads[item.fileName],
                        game = games[item.fileName],
                        showCover = showCovers,
                        failure = failures[item.fileName],
                        retryAt = retries[item.fileName],
                        restartsFromZero = item.fileName in restartsFromZero,
                        compact = isLandscape,
                        viewModel = viewModel,
                        modifier = (if (index == firstVisibleRow) Modifier.focusRequester(firstRowFocus) else Modifier)
                            .then(if (name == searchRow) Modifier.focusRequester(targetRowFocus) else Modifier),
                        selectionMode = selectionMode,
                        selected = item.fileName in selection,
                        onToggleSelection = { viewModel.toggleSelection(item.fileName) },
                        focusUp = if (index == 0) (if (selectionMode) barFocus else chipFocus) else null,
                        onRowFocused = { row, focused ->
                            if (focused) focusedRow = row else if (focusedRow?.fileName == row.fileName) focusedRow = null
                        },
                        queueTurn = queue.turns[name],
                        held = name in queue.held,
                        moving = moving == name,
                        queueActions = if (name in queue.waitingSet) QueueRowActions(
                            onReorder = { moving = name },
                            onUp = { viewModel.moveUp(name) },
                            onDown = { viewModel.moveDown(name) },
                            onFirst = { viewModel.moveToFront(name) },
                            onToggleHold = { viewModel.toggleHold(name) },
                            onDone = { moving = null }
                        ) else null
                    )
                }
            }
        }
    }

    if (confirmClearCompleted) {
        ClearCompletedDialog(
            count = DownloadBulk.completed(downloads).size,
            onConfirm = viewModel::confirmClearCompleted,
            onDismiss = viewModel::dismissClearCompleted
        )
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
 * The whole-list buttons that have rows to act on: the whole list (search and chips aside),
 * except "Retry failed", which only counts the rows on screen ([shown]).
 */
@Composable
private fun queueActions(downloads: List<DownloadItemModel>, shown: List<DownloadItemModel>, queue: QueueState, viewModel: DownloadViewModel): List<QueueAction> {
    val scheme = MaterialTheme.colorScheme
    val pauseAll = stringResource(R.string.downloads_pause_all)
    val resumeAll = stringResource(R.string.downloads_resume_all)
    val retryFailed = stringResource(R.string.downloads_retry_failed)
    val clearCompleted = stringResource(R.string.downloads_clear_completed)
    return buildList {
        if (DownloadBulk.pausable(downloads, queue.held).isNotEmpty()) add(QueueAction(R.drawable.ic_pause, pauseAll, scheme.onSurface, viewModel::pauseAll))
        if (DownloadBulk.resumable(downloads).isNotEmpty()) add(QueueAction(R.drawable.ic_play, resumeAll, scheme.primary, viewModel::resumeAll))
        if (DownloadBulk.failed(shown).isNotEmpty()) add(QueueAction(R.drawable.ic_retry, retryFailed, scheme.onSurface, viewModel::retryFailed))
        if (DownloadBulk.completed(downloads).isNotEmpty()) add(QueueAction(R.drawable.ic_clear_done, clearCompleted, scheme.onSurface, viewModel::requestClearCompleted))
    }
}

/** What A does to the row under the cursor, for the legend. */
@Composable
private fun primaryLabel(row: DownloadItemModel?, queue: QueueState): String = stringResource(
    when {
        row == null -> R.string.pad_retry
        row.fileName in queue.held -> R.string.pad_release
        row.status == DownloadStatus.DOWNLOADING -> R.string.pad_pause
        row.status == DownloadStatus.PAUSED -> R.string.download_resume
        row.status.canStop -> R.string.pad_stop
        row.status.canRetry -> R.string.pad_retry_only
        else -> R.string.pad_retry
    }
)

/**
 * Bulk actions for the ticked rows, in the search row's place. Each button only shows up when
 * some row in the selection accepts it, so nothing focusable is ever a no-op.
 */
@Composable
private fun SelectionBar(
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    selected: List<DownloadItemModel>,
    details: Map<String, DownloadableFileWithTags>,
    compact: Boolean,
    queue: QueueState,
    onRetry: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onFirst: () -> Unit,
    onHold: () -> Unit,
    onRelease: () -> Unit,
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
        if (selected.any { it.status == DownloadStatus.DOWNLOADING }) {
            add(BulkAction(R.drawable.ic_pause, stringResource(R.string.download_pause), scheme.onSurface, onPause))
        }
        if (selected.any { it.status.canStop }) {
            add(BulkAction(R.drawable.ic_stop, stringResource(R.string.download_cancel), scheme.onSurface, onStop))
        }
        // dl-cola: queue actions for the ticked rows waiting in line.
        if (selected.any { it.fileName in queue.waitingSet }) {
            add(BulkAction(R.drawable.ic_queue_top, stringResource(R.string.download_queue_next), scheme.onSurface, onFirst))
        }
        if (selected.any { it.fileName in queue.waitingSet && it.fileName !in queue.held }) {
            add(BulkAction(R.drawable.ic_hold, stringResource(R.string.download_queue_hold), scheme.onSurface, onHold))
        }
        if (selected.any { it.fileName in queue.held }) {
            add(BulkAction(R.drawable.ic_play, stringResource(R.string.download_queue_release), scheme.primary, onRelease))
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
