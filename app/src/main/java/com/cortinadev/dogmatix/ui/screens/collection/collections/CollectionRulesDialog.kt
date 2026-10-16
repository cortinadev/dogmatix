package com.cortinadev.dogmatix.ui.screens.collection.collections

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.SourceFilter
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.Stepper
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.NameDialog
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.ToolRow
import com.cortinadev.dogmatix.ui.screens.home.components.genreLabel
import com.cortinadev.dogmatix.util.CollectionRules
import com.cortinadev.dogmatix.util.GameTraitRules
import com.cortinadev.dogmatix.util.LibraryDecades
import com.cortinadev.dogmatix.util.LibraryGenre
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.OnDiskRule

private const val MB = 1024L * 1024
/** Size bounds offered by ◀ ▶ (per file); null = no bound. */
private val SIZE_STEPS: List<Long?> = listOf(null, 10 * MB, 100 * MB, 500 * MB, 1024 * MB, 2048 * MB, 4096 * MB, 8192 * MB, 16384 * MB)

/**
 * Creates or edits a smart collection: its name and its rules, one row each. A on a list rule
 * (consoles, regions, languages, tags) opens a checklist; ◀ ▶ change the others. Save is
 * enabled once there is a name and at least one rule. B closes without saving.
 */
@Composable
fun CollectionRulesDialog(
    title: String,
    initialName: String,
    initialRules: CollectionRules,
    options: RuleOptions?,
    onSave: (String, CollectionRules) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var rules by remember { mutableStateOf(initialRules) }
    var picking by remember { mutableStateOf<RuleList?>(null) }
    var editingName by remember { mutableStateOf(false) }
    var editingText by remember { mutableStateOf(false) }
    val firstRow = rememberInitialFocus()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (editingName) {
        NameDialog(stringResource(R.string.collections_rename), name, stringResource(android.R.string.ok), { name = it }, { editingName = false })
    }
    if (editingText) {
        NameDialog(
            stringResource(R.string.rules_name_contains), rules.nameContains, stringResource(android.R.string.ok),
            { rules = rules.copy(nameContains = it) }, { editingText = false },
            label = stringResource(R.string.rules_name_contains_label), allowEmpty = true
        )
    }
    picking?.let { list ->
        val (choices, chosen) = when (list) {
            RuleList.CONSOLES -> options?.consoles.orEmpty() to rules.consoles
            RuleList.REGIONS -> options?.regions.orEmpty().map { it to it } to rules.regions
            RuleList.LANGUAGES -> options?.languages.orEmpty().map { it to it } to rules.languages
            RuleList.TAGS -> options?.tags.orEmpty().map { it to it } to rules.tags
            RuleList.GENRES -> LibraryGenre.entries.map { it.name to genreLabel(it) } to GameTraitRules.genresOf(rules).map { it.name }.toSet()
            RuleList.DECADES -> LibraryDecades.options.map { it.toString() to stringResource(R.string.decade_long, it) } to
                GameTraitRules.decadesOf(rules).map { it.toString() }.toSet()
        }
        ChecklistDialog(
            title = stringResource(list.label),
            choices = choices,
            initial = chosen,
            onDone = { picked ->
                rules = when (list) {
                    RuleList.CONSOLES -> rules.copy(consoles = picked)
                    RuleList.REGIONS -> rules.copy(regions = picked)
                    RuleList.LANGUAGES -> rules.copy(languages = picked)
                    RuleList.TAGS -> rules.copy(tags = picked)
                    RuleList.GENRES -> GameTraitRules.withGenres(rules, LibraryGenre.entries.filter { it.name in picked }.toSet())
                    RuleList.DECADES -> GameTraitRules.withDecades(rules, picked.mapNotNull { it.toIntOrNull() }.toSet())
                }
            },
            onDismiss = { picking = null }
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .padding(horizontal = if (isLandscape) 48.dp else 16.dp, vertical = 24.dp)
                .widthIn(max = 640.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .closeOnGamepadB(onDismiss)
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            LazyColumn(modifier = Modifier.weight(1f, fill = false).heightIn(max = 520.dp)) {
                item {
                    ToolRow(
                        title = stringResource(R.string.collections_name),
                        subtitle = name.ifBlank { stringResource(R.string.rules_name_missing) },
                        onClick = { editingName = true },
                        modifier = Modifier.focusRequester(firstRow)
                    )
                }
                listOf(RuleList.CONSOLES, RuleList.REGIONS, RuleList.LANGUAGES, RuleList.TAGS, RuleList.GENRES, RuleList.DECADES).forEach { list ->
                    item {
                        val values = when (list) {
                            RuleList.CONSOLES -> rules.consoles.map { id -> options?.consoles?.firstOrNull { it.first == id }?.second ?: ConsoleFormatter.getConsoleFolderName(id) }
                            RuleList.REGIONS -> rules.regions.toList()
                            RuleList.LANGUAGES -> rules.languages.toList()
                            RuleList.TAGS -> rules.tags.toList()
                            RuleList.GENRES -> GameTraitRules.genresOf(rules).map { genreLabel(it) }
                            RuleList.DECADES -> GameTraitRules.decadesOf(rules).map { stringResource(R.string.decade_long, it) }
                        }
                        ToolRow(
                            title = stringResource(list.label),
                            subtitle = values.sorted().joinToString(", ").ifEmpty { stringResource(R.string.rules_any) },
                            onClick = { picking = list }
                        )
                    }
                }
                item {
                    fun toggle() { rules = rules.copy(favouritesOnly = !rules.favouritesOnly) }
                    ToolRow(title = stringResource(R.string.filter_favourites), onClick = ::toggle, onAdjust = { toggle() }) {
                        Stepper(
                            stringResource(if (rules.favouritesOnly) R.string.favourites_only else R.string.filter_all),
                            onDecrement = ::toggle, onIncrement = ::toggle, valueWidth = 120.dp
                        )
                    }
                }
                item {
                    fun cycle(delta: Int) { rules = rules.copy(onDisk = OnDiskRule.entries.cycle(rules.onDisk, delta)) }
                    ToolRow(title = stringResource(R.string.rules_on_disk), onClick = { cycle(1) }, onAdjust = ::cycle) {
                        Stepper(stringResource(rules.onDisk.label), onDecrement = { cycle(-1) }, onIncrement = { cycle(1) }, valueWidth = 120.dp)
                    }
                }
                item {
                    fun cycle(delta: Int) { rules = rules.copy(source = SourceFilter.entries.cycle(rules.source, delta)) }
                    ToolRow(title = stringResource(R.string.filter_source), onClick = { cycle(1) }, onAdjust = ::cycle) {
                        Stepper(stringResource(rules.source.label), onDecrement = { cycle(-1) }, onIncrement = { cycle(1) }, valueWidth = 120.dp)
                    }
                }
                item {
                    fun step(delta: Int) { rules = rules.copy(minBytes = SIZE_STEPS.step(rules.minBytes, delta)) }
                    ToolRow(title = stringResource(R.string.rules_min_size), onClick = { step(1) }, onAdjust = ::step) {
                        Stepper(sizeLabel(rules.minBytes), onDecrement = { step(-1) }, onIncrement = { step(1) }, valueWidth = 96.dp)
                    }
                }
                item {
                    fun step(delta: Int) { rules = rules.copy(maxBytes = SIZE_STEPS.step(rules.maxBytes, delta)) }
                    ToolRow(title = stringResource(R.string.rules_max_size), onClick = { step(1) }, onAdjust = ::step) {
                        Stepper(sizeLabel(rules.maxBytes), onDecrement = { step(-1) }, onIncrement = { step(1) }, valueWidth = 96.dp)
                    }
                }
                item {
                    ToolRow(
                        title = stringResource(R.string.rules_name_contains),
                        subtitle = rules.nameContains.ifBlank { stringResource(R.string.rules_any) },
                        onClick = { editingText = true }
                    )
                }
                // Fields this version has no row for (Genre and Decade have theirs above).
                val unknownExtras = rules.extras.keys - setOf(GameTraitRules.GENRES, GameTraitRules.DECADES)
                if (unknownExtras.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.rules_extras, unknownExtras.sorted().joinToString(", ")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            if (rules.isEmpty) {
                Text(
                    stringResource(R.string.rules_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                DialogButton(stringResource(R.string.dialog_cancel), onDismiss)
                DialogButton(
                    stringResource(R.string.collections_save),
                    { onDismiss(); onSave(name.trim(), rules) },
                    enabled = name.isNotBlank() && !rules.isEmpty
                )
            }
        }
    }
}

private enum class RuleList(val label: Int) {
    CONSOLES(R.string.filter_console),
    REGIONS(R.string.filter_region),
    LANGUAGES(R.string.filter_language),
    TAGS(R.string.filter_tag),
    GENRES(R.string.filter_genre),
    DECADES(R.string.filter_decade)
}

private val OnDiskRule.label: Int
    get() = when (this) {
        OnDiskRule.ANY -> R.string.rules_any
        OnDiskRule.OWNED -> R.string.rules_on_disk_owned
        OnDiskRule.MISSING -> R.string.rules_on_disk_missing
    }

private val SourceFilter.label: Int
    get() = when (this) {
        SourceFilter.ALL -> R.string.filter_all
        SourceFilter.TORRENT -> R.string.source_torrent
        SourceFilter.ROMM -> R.string.source_romm
        SourceFilter.DIRECT -> R.string.source_direct
    }

private fun <T> List<T>.cycle(current: T, delta: Int): T = this[((indexOf(current).coerceAtLeast(0) + delta) % size + size) % size]

private fun List<Long?>.step(current: Long?, delta: Int): Long? = this[(indexOf(current).coerceAtLeast(0) + delta).coerceIn(0, lastIndex)]

@Composable
private fun sizeLabel(bytes: Long?): String = bytes?.let { formatBytes(it) } ?: stringResource(R.string.rules_any)

/**
 * Multi-select list (A ticks a row); Done keeps the ticks, B / Cancel drops them. Focus starts
 * on the first row.
 */
@Composable
private fun ChecklistDialog(
    title: String,
    choices: List<Pair<String, String>>,
    initial: Set<String>,
    onDone: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var ticked by remember { mutableStateOf(initial) }
    val first = rememberInitialFocus()
    val doneFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (choices.isEmpty()) {
                Text(stringResource(R.string.rules_no_choices))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(choices, key = { it.first }) { (value, label) ->
                        ToolRow(
                            title = label,
                            onClick = { ticked = if (value in ticked) ticked - value else ticked + value },
                            modifier = if (value == choices.first().first) Modifier.focusRequester(first) else Modifier
                        ) {
                            if (value in ticked) Icon(painterResource(R.drawable.ic_check), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            DialogButton(stringResource(R.string.rules_done), { onDismiss(); onDone(ticked) }, initialFocus = if (choices.isEmpty()) doneFocus else null)
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DialogButton(stringResource(R.string.rules_clear), { ticked = emptySet() })
                DialogButton(stringResource(R.string.dialog_cancel), onDismiss)
            }
        }
    )
}
