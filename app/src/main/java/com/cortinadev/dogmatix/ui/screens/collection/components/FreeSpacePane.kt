package com.cortinadev.dogmatix.ui.screens.collection.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.refocusOnChange
import androidx.compose.ui.res.pluralStringResource
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.GamepadButton
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.screens.download.conversionLine
import com.cortinadev.dogmatix.ui.screens.collection.CollectionNav
import com.cortinadev.dogmatix.ui.screens.collection.CollectionViewModel
import com.cortinadev.dogmatix.util.CollectionReclaim
import com.cortinadev.dogmatix.util.ReclaimCandidate
import com.cortinadev.dogmatix.util.ReclaimKind
import com.cortinadev.dogmatix.util.RomConversions

/**
 * Free up space: what can go, biggest first (copies the Duplicates tool would delete, archives
 * already unpacked, leftovers of interrupted downloads, the torrent cache when nothing uses it,
 * the cover cache). A ticks, Y ticks all, X (or the button) deletes after asking. Deleted for
 * good, not to the trash, and the confirmation says so (focus on Cancel).
 *
 * conversion: below them, the games that take less space converted (an Xbox ISO as XISO…), with
 * the saving each one would bring. They are ticked the same way; X queues them along with the
 * deletions, and their originals go for good once each converted copy has been checked.
 */
@Composable
fun FreeSpacePane(viewModel: CollectionViewModel, active: Boolean, entryFocus: FocusRequester, nav: CollectionNav, modifier: Modifier = Modifier) {
    AnalysisGate(viewModel, active, entryFocus, nav) { _ ->
        val candidates by viewModel.reclaim.collectAsState()
        val ticked by viewModel.reclaimTicked.collectAsState()
        val working by viewModel.working.collectAsState()
        val selected = candidates.filter { it.id in ticked }
        val jobs by viewModel.conversionJobs.collectAsState()
        val estimating by viewModel.estimating.collectAsState()
        val (deletables, conversions) = candidates.partition { it.kind != ReclaimKind.CONVERT }
        val tickedConversions = selected.count { it.kind == ReclaimKind.CONVERT }
        val tickedDeletions = selected.size - tickedConversions
        var confirm by remember { mutableStateOf<List<ReclaimCandidate>?>(null) }
        // When the focused row goes (deleted, converted), the focus takes the row now in its place
        // (or the last one), and the button above when none is left: never the tools panel.
        val rowFocus = remember { HashMap<String, FocusRequester>() }
        val focusedIndex = remember { intArrayOf(-1) }
        val ids = deletables.map { it.id } + conversions.map { it.id }
        val currentIds by rememberUpdatedState(ids)
        fun rowModifier(index: Int, id: String) = Modifier
            .focusRequester(rowFocus.getOrPut(id) { FocusRequester() })
            .onFocusChanged { if (it.isFocused) focusedIndex[0] = index }

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
                LegendEntry("X", stringResource(if (tickedDeletions == 0 && tickedConversions > 0) R.string.conversion_convert else R.string.pad_delete))
            ) + paneLegendTail(),
            active
        )

        LazyColumn(
            modifier = modifier.refocusOnChange(ids) {
                val list = currentIds
                val next = (list.getOrNull(focusedIndex[0]) ?: list.lastOrNull())?.let { rowFocus[it] }
                val moved = next?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false
                if (!moved) runCatching { entryFocus.requestFocus() }
            },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
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
                            stringResource(
                                when {
                                    tickedConversions == 0 -> R.string.collection_delete_ticked
                                    tickedDeletions == 0 -> R.string.collection_convert_ticked
                                    else -> R.string.collection_apply_ticked
                                }
                            ),
                            onClick = { if (selected.isNotEmpty()) confirm = selected },
                            enabled = selected.isNotEmpty() && !working,
                            destructive = true,
                            focus = entryFocus
                        )
                    }
                }
            }
            itemsIndexed(deletables, key = { _, it -> it.id }) { index, candidate ->
                CollectionRow(
                    modifier = rowModifier(index, candidate.id),
                    title = candidateTitle(candidate),
                    subtitle = candidateDetail(candidate),
                    selected = candidate.id in ticked,
                    leading = { TickBox(candidate.id in ticked) },
                    trailing = { RowFigure(formatBytes(candidate.bytes)) },
                    onClick = { viewModel.toggleReclaim(candidate.id) }
                )
            }
            if (conversions.isNotEmpty() || estimating) {
                item(key = "convert-heading") {
                    SectionLabel(stringResource(R.string.collection_reclaim_convert_heading))
                    if (estimating) {
                        Text(
                            stringResource(R.string.collection_reclaim_convert_estimating),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 14.dp, bottom = 6.dp)
                        )
                    }
                }
            }
            itemsIndexed(conversions, key = { _, it -> it.id }) { index, candidate ->
                // While it is queued or running the row says so and cannot be ticked; also once it
                // turned out to save nothing (the reason stays on the row, also after a restart).
                val job = candidate.files.firstOrNull()?.uri?.let { jobs[it] }?.takeIf { it.state.active || it.savesNothing }
                CollectionRow(
                    modifier = rowModifier(deletables.size + index, candidate.id),
                    title = candidate.title,
                    subtitle = job?.let { conversionLine(it).first } ?: stringResource(
                        R.string.collection_reclaim_convert, candidate.format?.label.orEmpty(), location(candidate.files.firstOrNull())
                    ),
                    selected = candidate.id in ticked,
                    leading = { TickBox(candidate.id in ticked) },
                    trailing = {
                        RowFigure(if (job?.savesNothing == true) "—" else if (candidate.bytes > 0) "−" + formatBytes(candidate.bytes) else "?")
                    },
                    onClick = { viewModel.toggleReclaim(candidate.id) }
                )
            }
        }

        confirm?.let { all ->
            val (chosen, converts) = all.partition { it.kind != ReclaimKind.CONVERT }
            // What really goes (tracks and playlists of these discs included): shown, then deleted as it is.
            val plan = remember(chosen) { viewModel.deletionPlan(viewModel.reclaimFiles(chosen)) }
            val caches = chosen.filter { it.kind == ReclaimKind.TORRENT_CACHE || it.kind == ReclaimKind.IMAGE_CACHE }.sumOf { it.bytes }
            val format = RomConversions.labels(converts.mapNotNull { it.format })
            val conversionLines = if (converts.isEmpty()) emptyList() else listOfNotNull(
                pluralStringResource(R.plurals.collection_convert_lines, converts.size, converts.size, format),
                converts.sumOf { it.bytes }.takeIf { it > 0 }?.let { stringResource(R.string.conversion_confirm_saves, formatBytes(it)) },
                stringResource(R.string.conversion_confirm_wbfs).takeIf { converts.any { c -> c.files.any { RomConversions.losesDatMatch(it.name) } } }
            )
            if (chosen.isEmpty()) {
                ConfirmDialog(
                    title = pluralStringResource(R.plurals.conversion_confirm_title, converts.size, converts.size, format),
                    lines = converts.take(6).map { "${it.title}  ·  ${location(it.files.firstOrNull())}" } +
                        listOfNotNull((converts.size - 6).takeIf { it > 0 }?.let { stringResource(R.string.collection_and_more, it) }) +
                        conversionLines,
                    confirmLabel = stringResource(R.string.conversion_convert),
                    onConfirm = {
                        confirm = null
                        viewModel.deleteReclaim(converts)
                    },
                    onDismiss = { confirm = null }
                )
            } else ConfirmDialog(
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
                    stringResource(R.string.collection_frees, formatBytes(plan.bytes + caches)) +
                    conversionLines,
                confirmLabel = stringResource(if (converts.isEmpty()) R.string.collection_delete else R.string.collection_delete_and_convert),
                onConfirm = {
                    confirm = null
                    viewModel.deleteReclaim(all, plan)
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
    ReclaimKind.CONVERT -> stringResource(R.string.collection_reclaim_convert, candidate.format?.label.orEmpty(), location(candidate.files.firstOrNull()))
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
