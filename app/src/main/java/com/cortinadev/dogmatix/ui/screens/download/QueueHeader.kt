package com.cortinadev.dogmatix.ui.screens.download

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.screens.home.components.SearchField
import com.cortinadev.dogmatix.util.DownloadFilter

/** A whole-list button of the Downloads header (only listed when it has rows to act on). */
internal class QueueAction(val icon: Int, val label: String, val tint: Color, val onClick: () -> Unit)

/**
 * Top of the Downloads list: the search box (Y, out of the D-pad's way like the library's), the
 * status chips with their counts and the whole-list buttons. Landscape keeps them on one line,
 * so the list keeps its height on short screens; portrait puts the chips under the search.
 * While rows are ticked [selectionBar] takes the search's place and the chips stay.
 *
 * The buttons are icons; the legend names the focused one. The chip in use carries [chipFocus],
 * which LB and ▲ from the first row aim at; [chipsUp] is where ▲ goes from the chips (the
 * buttons sit off to the right, where focus search would not look).
 */
@Composable
internal fun QueueHeader(
    landscape: Boolean,
    selectionBar: (@Composable () -> Unit)?,
    query: String,
    onQueryChange: (String) -> Unit,
    searchActive: Boolean,
    onSearchActivate: () -> Unit,
    onSearchDismiss: () -> Unit,
    searchFocus: FocusRequester,
    filter: DownloadFilter,
    counts: Map<DownloadFilter, Int>,
    onFilter: (DownloadFilter) -> Unit,
    chipFocus: FocusRequester,
    chipsUp: FocusRequester?,
    onChipsFocused: (Boolean) -> Unit,
    actions: List<QueueAction>,
    firstActionFocus: FocusRequester,
    onActionFocused: (QueueAction, Boolean) -> Unit
) {
    val search = @Composable { modifier: Modifier ->
        SearchField(
            value = query,
            onValueChange = onQueryChange,
            active = searchActive,
            onActivate = onSearchActivate,
            onDismiss = onSearchDismiss,
            focusRequester = searchFocus,
            placeholder = stringResource(R.string.search_downloads),
            modifier = modifier
        )
    }
    val chips = @Composable { modifier: Modifier ->
        QueueChips(filter, counts, onFilter, chipFocus, chipsUp, onChipsFocused, modifier)
    }
    // ▼ from a button: the chips when they sit below (portrait), the list otherwise.
    // Keyed by action: a slot reused for another button (Pause all → Resume all) would keep the
    // focus without a focus change, and the legend would never learn the new action.
    val buttons = @Composable { down: FocusRequester? ->
        actions.forEachIndexed { index, action ->
            key(action.label) {
                ActionButton(
                    action.icon, action.label, if (landscape) 36.dp else 44.dp, action.tint,
                    modifier = Modifier
                        .then(if (index == 0) Modifier.focusRequester(firstActionFocus) else Modifier)
                        .focusProperties { down?.let { this.down = it } }
                        .onFocusChanged { onActionFocused(action, it.isFocused) },
                    onClick = action.onClick
                )
            }
        }
    }
    when {
        selectionBar != null -> {
            selectionBar()
            chips(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        }
        landscape -> Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            search(Modifier.width(220.dp))
            chips(Modifier.weight(1f))
            buttons(null)
        }
        else -> {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                search(Modifier.weight(1f))
                buttons(chipFocus)
            }
            chips(Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        }
    }
}

@Composable
private fun QueueChips(
    filter: DownloadFilter,
    counts: Map<DownloadFilter, Int>,
    onFilter: (DownloadFilter) -> Unit,
    chipFocus: FocusRequester,
    up: FocusRequester?,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier
) {
    Row(
        modifier = modifier
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        DownloadFilter.entries.forEach { chip ->
            QueueChip(
                label = stringResource(R.string.downloads_filter_count, stringResource(chip.labelRes), counts[chip] ?: 0),
                selected = chip == filter,
                modifier = Modifier
                    .then(if (chip == filter) Modifier.focusRequester(chipFocus) else Modifier)
                    .focusProperties { up?.let { this.up = it } },
                onClick = { onFilter(chip) }
            )
        }
    }
}

@Composable
private fun QueueChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) scheme.onPrimary else scheme.secondary,
        maxLines = 1,
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) scheme.primary else scheme.surfaceContainer)
            // The chip in use is filled with the accent, so an accent ring (where LB lands) would not
            // show: it gets the onSurface one the selected accent swatch uses in Settings.
            .focusRing(source, 18.dp, width = if (selected) 2.dp else 1.5.dp, ringColor = if (selected) scheme.onSurface else null)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp)
            .wrapContentHeight()
    )
}

internal val DownloadFilter.labelRes: Int
    get() = when (this) {
        DownloadFilter.ALL -> R.string.downloads_filter_all
        DownloadFilter.RUNNING -> R.string.downloads_filter_running
        DownloadFilter.WAITING -> R.string.downloads_filter_waiting
        DownloadFilter.HELD -> R.string.downloads_filter_held
        DownloadFilter.FAILED -> R.string.downloads_filter_failed
        DownloadFilter.COMPLETED -> R.string.downloads_filter_completed
    }

/** "Clear completed": the rows leave the list, the files stay. Focus starts on Cancel. */
@Composable
internal fun ClearCompletedDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.downloads_clear_completed)) },
        text = { Text(pluralStringResource(R.plurals.downloads_clear_message, count, count)) },
        confirmButton = { DialogButton(stringResource(R.string.downloads_clear_confirm), onClick = onConfirm) },
        dismissButton = {
            DialogButton(stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = rememberInitialFocus())
        }
    )
}
