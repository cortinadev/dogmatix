package com.cortinadev.dogmatix.ui.screens.sources.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.Console
import com.cortinadev.dogmatix.data.model.Manufacturer
import com.cortinadev.dogmatix.ui.components.ColorPickerDialog
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.WideDialogProperties
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.components.wideDialog
import com.cortinadev.dogmatix.util.ColorMath
import com.cortinadev.dogmatix.util.ConsoleAliasRegistry
import com.cortinadev.dogmatix.util.ConsoleFamily
import com.cortinadev.dogmatix.util.ConsoleFolderAliases
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.KnownConsoles
import com.cortinadev.dogmatix.util.SourcesJson

/**
 * What the console dialog hands back: display name, chip label, download-folder aliases, colour
 * (`#RRGGBB`, empty = default) and, when a predefined console was picked, the console part of
 * its id ([KnownConsoles.KnownConsole.key]; empty = derive it from the name).
 */
data class ConsoleFormValues(val name: String, val shortName: String, val aliases: List<String>, val color: String = "", val key: String = "")

/**
 * Adds or edits a console. When adding, the manufacturer comes first (the existing ones, then
 * the predefined ones of [KnownConsoles], or a new name), then the predefined consoles of that
 * manufacturer that are not there yet: picking one fills name, chip label and folder aliases,
 * all still editable. Opens with the first manufacturer chip focused (adding) or the name field
 * (editing); B cancels. Empty short name / aliases keep the built-in defaults.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddConsoleDialog(
    existing: Console? = null,
    manufacturers: List<Manufacturer> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (ConsoleFormValues) -> Unit = {},
    onConfirmExisting: (manufacturerId: String, values: ConsoleFormValues) -> Unit = { _, _ -> },
    onConfirmWithManufacturer: (manufacturerName: String, values: ConsoleFormValues) -> Unit = { _, _ -> }
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var shortName by remember { mutableStateOf(existing?.shortName.orEmpty()) }
    var aliases by remember { mutableStateOf(existing?.folderAliases.orEmpty().joinToString(", ")) }
    var color by remember { mutableStateOf(existing?.color.orEmpty()) }
    var pickedKey by remember { mutableStateOf("") }
    var pickingColor by remember { mutableStateOf(false) }
    val adding = existing == null

    // Manufacturer chips: the ones in the database, then the predefined ones not there yet.
    val existingIds = manufacturers.map { it.id }.toSet()
    val manufacturerChips = remember(manufacturers) {
        manufacturers.map { it.id to it.name } +
            KnownConsoles.manufacturers.filter { it.id !in existingIds }.map { it.id to it.name }
    }
    var selectedManufacturer by remember { mutableStateOf(manufacturerChips.firstOrNull()?.first) }
    var newManufacturer by remember { mutableStateOf("") }
    // A typed name selects nothing; if it happens to be a predefined manufacturer, its consoles show.
    val manufacturerId = if (newManufacturer.isNotBlank()) SourcesJson.slug(newManufacturer) else selectedManufacturer
    val manufacturerOk = !adding || manufacturerId != null
    val knownConsoles = if (adding && manufacturerId != null) KnownConsoles.missing(
        manufacturerId,
        manufacturers.firstOrNull { it.id == manufacturerId }?.consoles.orEmpty().map { it.id to it.name }
    ) else emptyList()
    val initialFocus = rememberInitialFocus()

    // Built-in values shown as placeholders so the user knows what "empty" means.
    val defaultShort = existing?.let { ConsoleFormatter.getDefaultShortName(it.id) }.orEmpty()
    val defaultAliases = existing?.let { ConsoleFolderAliases.defaultAliasesFor(it.id).joinToString(", ") }.orEmpty()

    fun pick(console: KnownConsoles.KnownConsole) {
        pickedKey = console.key
        name = console.name
        shortName = console.shortName
        aliases = console.aliases.joinToString(", ")
    }

    fun submit() {
        val values = ConsoleFormValues(name.trim(), shortName.trim(), ConsoleAliasRegistry.parseAliases(aliases), color, pickedKey)
        if (values.name.isEmpty() || !manufacturerOk) return
        when {
            !adding -> onConfirm(values)
            newManufacturer.isNotBlank() -> onConfirmWithManufacturer(newManufacturer.trim(), values)
            // A predefined manufacturer that is not in the database yet is created by name.
            selectedManufacturer !in existingIds -> onConfirmWithManufacturer(
                KnownConsoles.manufacturer(selectedManufacturer!!)?.name ?: selectedManufacturer!!, values
            )
            else -> onConfirmExisting(selectedManufacturer!!, values)
        }
        onDismiss()
    }

    // Without a picked colour the chip follows the console's family (guessed from the id while adding).
    val familyId = existing?.id ?: listOfNotNull(manufacturerId, pickedKey.ifBlank { name }).joinToString("_")
    val shownColor = Color(ColorMath.parseHex(color) ?: ConsoleFamily.of(familyId).defaultArgb)
    if (pickingColor) {
        ColorPickerDialog(
            title = stringResource(R.string.dialog_console_color),
            initial = shownColor,
            sample = shortName.ifBlank { defaultShort }.ifBlank { name }.ifBlank { "SNES" },
            strong = true,
            onPick = { picked -> color = picked?.let(ColorMath::toHex).orEmpty() },
            onDismiss = { pickingColor = false }
        )
    }

    AlertDialog(
        modifier = Modifier.wideDialog().closeOnGamepadB(onDismiss),
        properties = WideDialogProperties,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (adding) R.string.sources_add_console else R.string.sources_edit_console)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (adding) {
                    Text(stringResource(R.string.dialog_manufacturer), style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    ChipFlow(
                        items = manufacturerChips,
                        isSelected = { it.first == selectedManufacturer && newManufacturer.isBlank() },
                        label = { it.second },
                        firstFocus = initialFocus
                    ) { (id, _) ->
                        if (id != selectedManufacturer) pickedKey = ""
                        selectedManufacturer = id
                        newManufacturer = ""
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newManufacturer,
                        onValueChange = { newManufacturer = it; pickedKey = "" },
                        label = { Text(stringResource(R.string.dialog_new_manufacturer)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    if (knownConsoles.isNotEmpty()) {
                        Text(stringResource(R.string.dialog_known_consoles), style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        ChipFlow(
                            items = knownConsoles,
                            isSelected = { it.key == pickedKey },
                            label = { it.name },
                            onPick = ::pick
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    } else {
                        Text(stringResource(R.string.dialog_console_name_hint))
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                } else {
                    Text(stringResource(R.string.dialog_console_name_hint))
                    Spacer(modifier = Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.dialog_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().then(if (adding) Modifier else Modifier.focusRequester(initialFocus))
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Side by side in landscape; stacked on a narrow screen, where the long labels
                // (Spanish especially) would wrap over the field border.
                FieldPair(
                    narrow = LocalConfiguration.current.screenWidthDp < 600,
                    first = { modifier ->
                        OutlinedTextField(
                            value = shortName,
                            onValueChange = { shortName = it },
                            label = { Text(stringResource(R.string.dialog_short_name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            placeholder = { if (defaultShort.isNotEmpty()) Text(defaultShort) },
                            singleLine = true,
                            modifier = modifier
                        )
                    },
                    second = { modifier ->
                        OutlinedTextField(
                            value = aliases,
                            onValueChange = { aliases = it },
                            label = { Text(stringResource(R.string.dialog_folder_aliases), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            placeholder = { if (defaultAliases.isNotEmpty()) Text(defaultAliases) },
                            singleLine = true,
                            modifier = modifier
                        )
                    }
                )
                Text(
                    stringResource(R.string.dialog_aliases_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(shownColor))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.dialog_console_color), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (color.isBlank()) stringResource(R.string.dialog_console_color_default) else color,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DialogButton(text = stringResource(R.string.settings_change), onClick = { pickingColor = true })
                }
            }
        },
        confirmButton = {
            DialogButton(
                text = stringResource(R.string.dialog_confirm),
                onClick = ::submit,
                enabled = name.isNotBlank() && manufacturerOk
            )
        },
        dismissButton = {
            DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss)
        }
    )
}

/** Two fields in one row (1:2) or, when [narrow], one under the other at full width. */
@Composable
private fun FieldPair(narrow: Boolean, first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    if (narrow) {
        first(Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        second(Modifier.fillMaxWidth())
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            first(Modifier.weight(1f))
            second(Modifier.weight(2f))
        }
    }
}

/** A row of wrapping filter chips with the focus ring; [firstFocus] goes on the first one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipFlow(
    items: List<T>,
    isSelected: (T) -> Boolean,
    label: (T) -> String,
    firstFocus: FocusRequester? = null,
    onPick: (T) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEachIndexed { index, item ->
            val source = rememberFocusSource()
            FilterChip(
                selected = isSelected(item),
                onClick = { onPick(item) },
                label = { Text(label(item), maxLines = 1) },
                interactionSource = source,
                modifier = Modifier
                    .focusRing(source, 8.dp)
                    .then(if (index == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
            )
        }
    }
}
