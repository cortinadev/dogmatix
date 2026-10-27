package com.cortinadev.dogmatix.ui.screens.collection.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.onFocusChanged
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
import com.cortinadev.dogmatix.util.DuplicateGroup
import com.cortinadev.dogmatix.util.DuplicateKind

/**
 * Duplicates: each game on disk more than once, with every copy. The suggested one to keep is
 * ticked; A on a copy keeps that one instead, X deletes the other copies of its group and the
 * button at the top deletes what every group suggests. Both ask first.
 */
@Composable
fun DuplicatesPane(viewModel: CollectionViewModel, active: Boolean, entryFocus: FocusRequester, nav: CollectionNav, modifier: Modifier = Modifier) {
    AnalysisGate(viewModel, active, entryFocus, nav) { analysis ->
        val choices by viewModel.keepChoices.collectAsState()
        val working by viewModel.working.collectAsState()
        val groups = analysis.duplicates
        var focusedGroup by remember { mutableStateOf<DuplicateGroup?>(null) }
        // What the confirmation is about: one group (X) or every group with a choice (the button).
        var confirm by remember { mutableStateOf<List<DuplicateGroup>?>(null) }
        val decided = groups.filter { viewModel.keepOf(it, choices) != null }
        val reclaimable = decided.sumOf { it.reclaimable(viewModel.keepOf(it, choices)) }

        val currentFocused by rememberUpdatedState(focusedGroup)
        LaunchedEffect(active) {
            if (!active) return@LaunchedEffect
            Gamepad.presses.collect { button ->
                if (button == GamepadButton.X) currentFocused?.takeIf { viewModel.keepOf(it) != null }?.let { confirm = listOf(it) }
            }
        }
        PublishLegend(
            listOf(LegendEntry("A", stringResource(R.string.pad_keep)), LegendEntry("X", stringResource(R.string.pad_delete_rest))) + paneLegendTail(),
            active
        )

        LazyColumn(modifier = modifier, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            item {
                PaneHeader(
                    if (groups.isEmpty()) stringResource(R.string.collection_duplicates_none)
                    else pluralStringResource(R.plurals.collection_duplicates_summary, groups.size, groups.size, formatBytes(reclaimable))
                ) {
                    if (decided.isNotEmpty()) {
                        CollectionButton(
                            stringResource(R.string.collection_duplicates_apply),
                            onClick = { confirm = decided },
                            enabled = !working,
                            destructive = true,
                            focus = entryFocus
                        )
                    }
                }
            }
            groups.forEach { group ->
                val keepId = viewModel.keepOf(group, choices)
                item(key = "h:" + group.id) {
                    Text(
                        listOf(group.title, consoleLabel(group.consoleId), stringResource(group.kind.labelRes)).joinToString("  ·  "),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 4.dp)
                    )
                }
                items(group.games, key = { "d:" + group.id + "|" + it.id }) { game ->
                    val kept = game.id == keepId
                    CollectionRow(
                        title = game.title,
                        subtitle = location(game.files.first()) + "  ·  " + pluralStringResource(R.plurals.collection_files, game.files.size, game.files.size),
                        selected = kept,
                        modifier = Modifier
                            .then(if (decided.isEmpty() && group == groups.first() && game == group.games.first()) Modifier.focusRequester(entryFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) focusedGroup = group },
                        leading = { TickBox(kept) },
                        trailing = {
                            Text(
                                when {
                                    keepId == null -> stringResource(R.string.collection_keep_choose)
                                    kept -> stringResource(R.string.collection_keep)
                                    else -> stringResource(R.string.collection_remove)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (keepId != null && !kept) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            RowFigure(formatBytes(game.size))
                        },
                        onClick = { viewModel.chooseKeep(group, game) }
                    )
                }
            }
        }

        confirm?.let { targets ->
            val games = targets.flatMap { it.removable(viewModel.keepOf(it, choices)) }
            // What really goes (tracks and playlists of these discs included): shown, then deleted as it is.
            val plan = remember(games) { viewModel.deletionPlan(games.flatMap { it.files }) }
            ConfirmDialog(
                title = pluralStringResource(R.plurals.collection_delete_copies_title, games.size, games.size),
                // A game of several files (a sheet and its tracks, a set) says so: they all go.
                lines = games.take(6).map { game ->
                    listOfNotNull(
                        game.title, location(game.files.first()),
                        game.files.size.takeIf { it > 1 }?.let { pluralStringResource(R.plurals.collection_files, it, it) }
                    ).joinToString("  ·  ")
                } +
                    listOfNotNull((games.size - 6).takeIf { it > 0 }?.let { stringResource(R.string.collection_and_more, it) }) +
                    takenAlongLines(plan) +
                    stringResource(R.string.collection_frees, formatBytes(plan.bytes)),
                confirmLabel = stringResource(R.string.collection_delete),
                onConfirm = {
                    confirm = null
                    viewModel.deleteDuplicates(targets, plan)
                },
                onDismiss = { confirm = null }
            )
        }
    }
}

private val DuplicateKind.labelRes: Int
    get() = when (this) {
        DuplicateKind.SAME_FILE -> R.string.collection_duplicate_same_file
        DuplicateKind.SAME_CONTENT -> R.string.collection_duplicate_same_content
        DuplicateKind.VERSIONS -> R.string.collection_duplicate_versions
    }
