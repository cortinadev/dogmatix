package com.cortinadev.dogmatix.ui.screens.collection.collections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.SourceFilter
import com.cortinadev.dogmatix.data.repository.CollectionSummary
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.GamepadButton
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.NameDialog
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.PaneText
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.SectionTitle
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.ToolButton
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.ToolButtons
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.ToolConfirmDialog
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.ToolRow
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.paneLegend
import com.cortinadev.dogmatix.ui.screens.collection.toolkit.toolPane
import com.cortinadev.dogmatix.util.CollectionRules
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.OnDiskRule

/** Which collection dialog is open: one at a time, so B always closes the top one. */
private sealed interface CollectionDialog {
    data object NewManual : CollectionDialog
    data object NewSmart : CollectionDialog
    data class Manage(val collection: CollectionSummary) : CollectionDialog
    data class Rename(val collection: CollectionSummary) : CollectionDialog
    data class EditRules(val collection: CollectionSummary) : CollectionDialog
    data class Delete(val collection: CollectionSummary) : CollectionDialog
}

/**
 * Collection → Collections: the user's collections, manual (games added from the details card)
 * and smart (by rules). A opens one in the library ("Collection: X", B there clears it); X (or
 * the ⋯ by touch) offers rename, edit rules and delete — deleting asks first and can be undone
 * from History. Collections of other places (RomM) are listed below, read-only.
 */
@Composable
fun CollectionsPane(modifier: Modifier = Modifier, viewModel: CollectionsViewModel = hiltViewModel()) {
    val collections by viewModel.collections.collectAsState()
    val external by viewModel.external.collectAsState()
    val opening by viewModel.opening.collectAsState()
    val options by viewModel.options.collectAsState()
    var dialog by remember { mutableStateOf<CollectionDialog?>(null) }
    var focused by remember { mutableStateOf<CollectionSummary?>(null) }
    var hasFocus by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    // Deleting takes the focused row away: the focus goes back to the pane's first button.
    var refocusFirst by remember { mutableStateOf(false) }
    LaunchedEffect(collections, refocusFirst) {
        if (!refocusFirst) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        runCatching { first.requestFocus() }
        refocusFirst = false
    }

    // X on a collection: its options (the pane only reacts while it holds the focus).
    LaunchedEffect(Unit) {
        Gamepad.presses.collect { button ->
            if (button == GamepadButton.X && hasFocus && dialog == null) focused?.let { dialog = CollectionDialog.Manage(it) }
        }
    }

    when (val d = dialog) {
        CollectionDialog.NewManual -> NameDialog(
            stringResource(R.string.collections_new), "", stringResource(R.string.collections_create),
            { viewModel.create(it, null) }, { dialog = null }
        )
        CollectionDialog.NewSmart -> {
            LaunchedEffect(Unit) { viewModel.loadOptions() }
            CollectionRulesDialog(
                title = stringResource(R.string.collections_new_smart), initialName = "", initialRules = CollectionRules(),
                options = options, onSave = { name, rules -> viewModel.create(name, rules) }, onDismiss = { dialog = null }
            )
        }
        is CollectionDialog.Manage -> ManageDialog(
            collection = d.collection,
            onOpen = { viewModel.open(d.collection) },
            onRename = { dialog = CollectionDialog.Rename(d.collection) },
            onEditRules = { dialog = CollectionDialog.EditRules(d.collection) },
            onDelete = { dialog = CollectionDialog.Delete(d.collection) },
            onDismiss = { dialog = null }
        )
        is CollectionDialog.Rename -> NameDialog(
            stringResource(R.string.collections_rename), d.collection.name, stringResource(R.string.collections_save),
            { viewModel.rename(d.collection.id, it) }, { dialog = null }
        )
        is CollectionDialog.EditRules -> {
            LaunchedEffect(Unit) { viewModel.loadOptions() }
            CollectionRulesDialog(
                title = stringResource(R.string.collections_edit_rules), initialName = d.collection.name,
                initialRules = d.collection.rules ?: CollectionRules(), options = options,
                onSave = { name, rules -> viewModel.saveRules(d.collection.id, name, rules) }, onDismiss = { dialog = null }
            )
        }
        is CollectionDialog.Delete -> ToolConfirmDialog(
            title = stringResource(R.string.collections_delete_title, d.collection.name),
            message = stringResource(R.string.collections_delete_message),
            confirmText = stringResource(R.string.collections_delete),
            onConfirm = { refocusFirst = true; viewModel.delete(d.collection.id) },
            onDismiss = { dialog = null }
        )
        null -> Unit
    }

    val legend = paneLegend(LegendEntry("A", stringResource(R.string.pad_open)), LegendEntry("X", stringResource(R.string.pad_options)))
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .onFocusChanged { hasFocus = it.hasFocus }
            .toolPane(first, legend),
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item(key = "actions") {
            ToolButtons {
                ToolButton(stringResource(R.string.collections_new), { dialog = CollectionDialog.NewManual }, focusRequester = first)
                ToolButton(stringResource(R.string.collections_new_smart), { dialog = CollectionDialog.NewSmart })
            }
        }
        item(key = "mine-title") { SectionTitle(stringResource(R.string.collections_mine)) }
        val mine = collections
        if (mine != null && mine.isEmpty()) {
            item(key = "mine-empty") { PaneText(stringResource(R.string.collections_empty)) }
        }
        items(mine.orEmpty(), key = { "c${it.id}" }) { collection ->
            ToolRow(
                title = collection.name,
                subtitle = collectionSubtitle(collection),
                onClick = { viewModel.open(collection) },
                onFocused = { focused = collection }
            ) {
                if (opening == collection.id) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    // Touch only: on a gamepad X opens the same options.
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .focusProperties { canFocus = false }
                            .clickable { dialog = CollectionDialog.Manage(collection) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.pad_options), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        external.groupBy { it.sourceKey }.forEach { (source, list) ->
            item(key = "ext-$source") { SectionTitle(viewModel.externalLabel(source)) }
            items(list, key = { "e${it.sourceKey}:${it.id}" }) { collection ->
                ToolRow(
                    title = collection.name,
                    subtitle = pluralStringResource(R.plurals.collections_games, collection.games, collection.games),
                    onClick = { viewModel.openExternal(collection) },
                    onFocused = { focused = null }
                )
            }
        }
    }
}

/** "Manual · 12 games" / "Smart · PS1, Saturn · Europe · 40 games". */
@Composable
private fun collectionSubtitle(collection: CollectionSummary): String {
    val games = pluralStringResource(R.plurals.collections_games, collection.games, collection.games)
    val rules = collection.rules ?: return stringResource(R.string.collections_manual) + "  ·  " + games
    return listOf(stringResource(R.string.collections_smart), rulesSummary(rules), games)
        .filter { it.isNotBlank() }.joinToString("  ·  ")
}

@Composable
fun rulesSummary(rules: CollectionRules): String {
    val parts = mutableListOf<String>()
    if (rules.consoles.isNotEmpty()) parts += rules.consoles.map { ConsoleFormatter.getConsoleShortName(it) }.sorted().joinToString(", ")
    if (rules.regions.isNotEmpty()) parts += rules.regions.sorted().joinToString(", ")
    if (rules.languages.isNotEmpty()) parts += rules.languages.sorted().joinToString(", ")
    if (rules.tags.isNotEmpty()) parts += rules.tags.sorted().joinToString(", ")
    if (rules.favouritesOnly) parts += stringResource(R.string.favourites_only_short)
    when (rules.onDisk) {
        OnDiskRule.ANY -> Unit
        OnDiskRule.OWNED -> parts += stringResource(R.string.rules_on_disk_owned)
        OnDiskRule.MISSING -> parts += stringResource(R.string.rules_on_disk_missing)
    }
    when (rules.source) {
        SourceFilter.ALL -> Unit
        SourceFilter.TORRENT -> parts += stringResource(R.string.source_torrent)
        SourceFilter.ROMM -> parts += stringResource(R.string.source_romm)
        SourceFilter.DIRECT -> parts += stringResource(R.string.source_direct)
    }
    rules.minBytes?.let { parts += stringResource(R.string.rules_min_summary, formatBytes(it)) }
    rules.maxBytes?.let { parts += stringResource(R.string.rules_max_summary, formatBytes(it)) }
    if (rules.nameContains.isNotBlank()) parts += stringResource(R.string.rules_text_summary, rules.nameContains)
    return parts.joinToString("  ·  ")
}

/** The options of one collection (X, or ⋯ by touch); focus starts on Open. */
@Composable
private fun ManageDialog(
    collection: CollectionSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onEditRules: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val openFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(collection.name) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                DialogButton(stringResource(R.string.collections_open), { onDismiss(); onOpen() }, initialFocus = openFocus)
                DialogButton(stringResource(R.string.collections_rename), onRename)
                if (collection.isSmart) DialogButton(stringResource(R.string.collections_edit_rules), onEditRules)
                DialogButton(stringResource(R.string.collections_delete), onDelete)
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.details_close), onDismiss) }
    )
}
