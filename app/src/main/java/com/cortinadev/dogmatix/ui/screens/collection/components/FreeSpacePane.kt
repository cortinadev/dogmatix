package com.cortinadev.dogmatix.ui.screens.collection.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.GamepadButton
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.screens.collection.CollectionNav
import com.cortinadev.dogmatix.ui.screens.collection.CollectionViewModel
import com.cortinadev.dogmatix.util.CollectionReclaim
import com.cortinadev.dogmatix.util.ReclaimCandidate
import com.cortinadev.dogmatix.util.ReclaimKind

/**
 * Free up space: what can go, biggest first (copies the Duplicates tool would delete, archives
 * already unpacked, leftovers of interrupted downloads, the torrent cache when nothing uses it,
 * the cover cache). A ticks, Y ticks all, X (or the button) deletes after asking. Deleted for
 * good, not to the trash, and the confirmation says so (focus on Cancel).
 */
@Composable
fun FreeSpacePane(viewModel: CollectionViewModel, active: Boolean, entryFocus: FocusRequester, nav: CollectionNav, modifier: Modifier = Modifier) {
    AnalysisGate(viewModel, active, entryFocus, nav) { _ ->
        val candidates by viewModel.reclaim.collectAsState()
        val ticked by viewModel.reclaimTicked.collectAsState()
        val working by viewModel.working.collectAsState()
        val selected = candidates.filter { it.id in ticked }
        var confirm by remember { mutableStateOf<List<ReclaimCandidate>?>(null) }

        val currentSelected by rememberUpdatedState(selected)
        LaunchedEffect(active) {
            if (!active) return@LaunchedEffect
            Gamepad.presses.collect { button ->
                when (button) {
                    GamepadButton.Y -> viewModel.toggleAllReclaim()
                    GamepadButton.X -> if (currentSelected.isNotEmpty() && !viewModel.working.value) confirm = currentSelected
                    else -> Unit
                }
            }
        }
        PublishLegend(
            listOf(
                LegendEntry("A", stringResource(R.string.pad_tick)),
                LegendEntry("Y", stringResource(R.string.pad_select_all)),
                LegendEntry("X", stringResource(R.string.pad_delete))
            ) + paneLegendTail(),
            active
        )

        LazyColumn(modifier = modifier, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            item {
                PaneHeader(
                    if (candidates.isEmpty()) stringResource(R.string.collection_free_space_none)
                    else stringResource(
                        R.string.collection_free_space_summary,
                        formatBytes(CollectionReclaim.total(candidates, ticked)),
                        formatBytes(candidates.sumOf { it.bytes })
                    )
                ) {
                    if (candidates.isNotEmpty()) {
                        CollectionButton(
                            stringResource(R.string.collection_delete_ticked),
                            onClick = { if (selected.isNotEmpty()) confirm = selected },
                            enabled = selected.isNotEmpty() && !working,
                            destructive = true,
                            focus = entryFocus
                        )
                    }
                }
            }
            items(candidates, key = { it.id }) { candidate ->
                CollectionRow(
                    title = candidateTitle(candidate),
                    subtitle = candidateDetail(candidate),
                    selected = candidate.id in ticked,
                    leading = { TickBox(candidate.id in ticked) },
                    trailing = { RowFigure(formatBytes(candidate.bytes)) },
                    onClick = { viewModel.toggleReclaim(candidate.id) }
                )
            }
        }

        confirm?.let { chosen ->
            // What really goes (tracks and playlists of these discs included): shown, then deleted as it is.
            val plan = remember(chosen) { viewModel.deletionPlan(viewModel.reclaimFiles(chosen)) }
            val caches = chosen.filter { it.kind == ReclaimKind.TORRENT_CACHE || it.kind == ReclaimKind.IMAGE_CACHE }.sumOf { it.bytes }
            ConfirmDialog(
                title = pluralStringResource(R.plurals.collection_free_space_confirm_title, chosen.size, chosen.size),
                lines = listOf(stringResource(R.string.collection_free_space_permanent)) +
                    chosen.take(6).map { candidate ->
                        // A copy of several files (a sheet and its tracks, a set) says so: they all go.
                        listOfNotNull(
                            candidateTitle(candidate), formatBytes(candidate.bytes),
                            candidate.files.size.takeIf { it > 1 }?.let { pluralStringResource(R.plurals.collection_files, it, it) }
                        ).joinToString("  ·  ")
                    } +
                    listOfNotNull((chosen.size - 6).takeIf { it > 0 }?.let { stringResource(R.string.collection_and_more, it) }) +
                    takenAlongLines(plan) +
                    stringResource(R.string.collection_frees, formatBytes(plan.bytes + caches)),
                confirmLabel = stringResource(R.string.collection_delete),
                onConfirm = {
                    confirm = null
                    viewModel.deleteReclaim(chosen, plan)
                },
                onDismiss = { confirm = null }
            )
        }
    }
}

@Composable
private fun candidateTitle(candidate: ReclaimCandidate): String = when (candidate.kind) {
    ReclaimKind.TORRENT_CACHE -> stringResource(R.string.collection_reclaim_torrent_cache)
    ReclaimKind.IMAGE_CACHE -> stringResource(R.string.collection_reclaim_image_cache)
    else -> candidate.title
}

@Composable
private fun candidateDetail(candidate: ReclaimCandidate): String = when (candidate.kind) {
    ReclaimKind.DUPLICATE -> stringResource(R.string.collection_reclaim_duplicate, location(candidate.files.firstOrNull()), keptLabel(candidate))
    ReclaimKind.EXTRACTED_ARCHIVE -> stringResource(R.string.collection_reclaim_archive, location(candidate.files.firstOrNull()))
    ReclaimKind.PARTIAL_DOWNLOAD -> stringResource(R.string.collection_reclaim_partial, location(candidate.files.firstOrNull()))
    ReclaimKind.TORRENT_CACHE -> stringResource(R.string.collection_reclaim_torrent_cache_hint)
    ReclaimKind.IMAGE_CACHE -> stringResource(R.string.collection_reclaim_image_cache_hint)
}

/**
 * The copy that stays: its name, and where it is when that name is the same (a copy in another
 * folder or console). A `<game>.m3u/` folder already ends in the game's name.
 */
@Composable
private fun keptLabel(candidate: ReclaimCandidate): String {
    val kept = candidate.kept ?: return ""
    return when {
        kept.title != candidate.title -> kept.title
        kept.setFolderUri != null -> location(kept.files.first())
        else -> location(kept.files.first()) + "/" + kept.title
    }
}
