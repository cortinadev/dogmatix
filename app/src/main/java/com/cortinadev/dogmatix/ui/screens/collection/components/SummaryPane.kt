package com.cortinadev.dogmatix.ui.screens.collection.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.components.stripExtension
import com.cortinadev.dogmatix.ui.screens.collection.CollectionAnalysis
import com.cortinadev.dogmatix.ui.screens.collection.CollectionNav
import com.cortinadev.dogmatix.ui.screens.collection.CollectionViewModel
import com.cortinadev.dogmatix.ui.screens.collection.SummaryExtras
import com.cortinadev.dogmatix.util.CollectionStats
import com.cortinadev.dogmatix.util.TagCount
import java.util.Locale

/**
 * Summary: games, files and size on disk, the catalogue's coverage and favourites, then each
 * volume's usage, each console, the latest downloads, the biggest games and how the games split by
 * region and language. Every row is focusable so the D-pad scrolls the page; A opens the console,
 * game or tag in the Library. The entry focus goes to the first row, so the totals stay in view.
 */
@Composable
fun SummaryPane(viewModel: CollectionViewModel, active: Boolean, entryFocus: FocusRequester, nav: CollectionNav, modifier: Modifier = Modifier) {
    AnalysisGate(viewModel, active, entryFocus, nav) { analysis ->
        val extras by viewModel.summaryExtras.collectAsState()
        PublishLegend(
            listOf(LegendEntry("A", stringResource(R.string.pad_show_in_library)), analyzeLegendEntry(viewModel)) + paneLegendTail(),
            active
        )
        val volumes = analysis.snapshot.volumes
        val gameCount = analysis.games.size.toLong()
        val entry = when {
            volumes.isNotEmpty() -> Section.VOLUMES
            analysis.consoles.isNotEmpty() -> Section.CONSOLES
            else -> Section.RECENT
        }
        val entryModifier = { section: Section, index: Int -> if (section == entry && index == 0) Modifier.focusRequester(entryFocus) else Modifier }
        LazyColumn(modifier = modifier, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
            item { SummaryTotals(analysis, extras) }
            if (volumes.isNotEmpty()) item { SectionLabel(stringResource(R.string.collection_storage)) }
            itemsIndexed(volumes, key = { _, it -> "v:" + it.id }) { index, volume ->
                val name = volumeLabel(volume, volumes)
                val title = if (volume.hostsLibrary) stringResource(R.string.collection_volume_library, name) else name
                CollectionRow(
                    title = title,
                    subtitle = stringResource(R.string.collection_volume_free, formatBytes(volume.freeBytes), formatBytes(volume.totalBytes)),
                    modifier = entryModifier(Section.VOLUMES, index),
                    trailing = { RowFigure(formatBytes(volume.usedBytes)) },
                    extra = { FlatBar(CollectionStats.fraction(volume.usedBytes, volume.totalBytes)) }
                )
            }
            if (analysis.consoles.isEmpty()) {
                item { EmptyLine(stringResource(R.string.collection_no_games)) }
            } else {
                item { SectionLabel(stringResource(R.string.collection_by_console)) }
            }
            itemsIndexed(analysis.consoles, key = { _, it -> "c:" + (it.consoleId ?: it.folder) }) { index, usage ->
                val coverage = if (usage.catalogGames > 0) {
                    stringResource(R.string.collection_console_coverage, usage.ownedCatalogGames, usage.catalogGames)
                } else null
                CollectionRow(
                    title = consoleLabel(usage.consoleId, usage.folder),
                    subtitle = listOfNotNull(pluralStringResource(R.plurals.collection_games, usage.games, usage.games), coverage).joinToString("  ·  "),
                    modifier = entryModifier(Section.CONSOLES, index),
                    trailing = { RowFigure(formatBytes(usage.bytes)) },
                    extra = if (usage.catalogGames > 0) ({
                        FlatBar(CollectionStats.fraction(usage.ownedCatalogGames.toLong(), usage.catalogGames.toLong()))
                    }) else null,
                    onClick = { viewModel.openInLibrary(usage.consoleId) }
                )
            }
            if (extras.recent.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.collection_recent_downloads)) }
                itemsIndexed(extras.recent, key = { index, it -> "r:$index:${it.name}" }) { index, recent ->
                    CollectionRow(
                        title = stripExtension(recent.name),
                        subtitle = listOfNotNull(recent.consoleId?.let { consoleLabel(it) }, recent.finishedAt?.let { agoLabel(it) }).joinToString("  ·  "),
                        modifier = entryModifier(Section.RECENT, index),
                        onClick = { viewModel.openInLibrary(recent.consoleId, stripExtension(recent.name)) }
                    )
                }
            }
            if (analysis.biggest.isNotEmpty()) item { SectionLabel(stringResource(R.string.collection_biggest_games)) }
            itemsIndexed(analysis.biggest, key = { _, it -> "g:" + it.id }) { _, game ->
                CollectionRow(
                    title = game.title,
                    subtitle = consoleLabel(game.consoleId, game.files.first().topFolder),
                    trailing = { RowFigure(formatBytes(game.size)) },
                    onClick = { viewModel.openInLibrary(game.consoleId, game.title) }
                )
            }
            tagSection(R.string.collection_regions, analysis.regions, gameCount) { viewModel.openInLibraryTag(it) }
            tagSection(R.string.collection_languages, analysis.languages, gameCount) { viewModel.openInLibraryTag(it) }
        }
    }
}

/** The blocks of the summary that can hold its first row. */
private enum class Section { VOLUMES, CONSOLES, RECENT }

@Composable
private fun SummaryTotals(analysis: CollectionAnalysis, extras: SummaryExtras) {
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Figure(analysis.gameCount.toString(), pluralStringResource(R.plurals.collection_games_label, analysis.gameCount))
            Figure(analysis.files.toString(), pluralStringResource(R.plurals.collection_files_label, analysis.files))
            Figure(formatBytes(analysis.bytes), stringResource(R.string.collection_on_disk))
        }
        if (analysis.catalogGames > 0) {
            val percent = (CollectionStats.fraction(analysis.ownedCatalogGames.toLong(), analysis.catalogGames.toLong()) * 100).toInt()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.collection_coverage, analysis.ownedCatalogGames, analysis.catalogGames, percent),
                    style = MaterialTheme.typography.bodyMedium
                )
                FlatBar(CollectionStats.fraction(analysis.ownedCatalogGames.toLong(), analysis.catalogGames.toLong()), modifier = Modifier.width(280.dp))
            }
        }
        Text(
            stringResource(R.string.collection_favourites_line, extras.favourites, extras.favouritesOnDisk),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Figure(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun LazyListScope.tagSection(label: Int, tags: List<TagCount>, games: Long, onOpen: (String) -> Unit) {
    if (tags.isEmpty()) return
    item { SectionLabel(stringResource(label)) }
    itemsIndexed(tags.take(MAX_TAGS), key = { _, it -> "t:$label:${it.tag}" }) { _, count ->
        CollectionRow(
            title = tagName(count.tag),
            trailing = { RowFigure(count.games.toString()) },
            extra = { FlatBar(CollectionStats.fraction(count.games.toLong(), games)) },
            onClick = { onOpen(count.tag) }
        )
    }
}

/** `EUROPE` → `Europe`; two- and three-letter codes stay upper-case (`USA`, `EN`). */
private fun tagName(tag: String): String =
    if (tag.length <= 3) tag else tag.lowercase(Locale.ROOT).split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }

private const val MAX_TAGS = 12

@Composable
internal fun EmptyLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp)
    )
}
