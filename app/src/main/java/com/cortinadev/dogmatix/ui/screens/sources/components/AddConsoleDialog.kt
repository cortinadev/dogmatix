package com.cortinadev.dogmatix.ui.screens.sources.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.Console
import com.cortinadev.dogmatix.data.model.Manufacturer
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.util.ConsoleAliasRegistry
import com.cortinadev.dogmatix.util.ConsoleFolderAliases
import com.cortinadev.dogmatix.util.ConsoleFormatter

/** What the console dialog hands back: display name, chip label and download-folder aliases. */
data class ConsoleFormValues(val name: String, val shortName: String, val aliases: List<String>)

/**
 * Adds or edits a console: name, short label (chip), folder aliases and — when adding — the
 * manufacturer, picked from the existing ones (chips) or typed as a new name.
 * Opens with the name field focused; B cancels. Empty short name / aliases keep the built-in defaults.
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
    val adding = existing == null
    var selectedManufacturer by remember { mutableStateOf(manufacturers.firstOrNull()?.id) }
    var newManufacturer by remember { mutableStateOf("") }
    val manufacturerOk = !adding || selectedManufacturer != null || newManufacturer.isNotBlank()
    val nameFocus = rememberInitialFocus()

    // Built-in values shown as placeholders so the user knows what "empty" means.
    val defaultShort = existing?.let { ConsoleFormatter.getDefaultShortName(it.id) }.orEmpty()
    val defaultAliases = existing?.let { ConsoleFolderAliases.defaultAliasesFor(it.id).joinToString(", ") }.orEmpty()

    fun submit() {
        val values = ConsoleFormValues(name.trim(), shortName.trim(), ConsoleAliasRegistry.parseAliases(aliases))
        if (values.name.isEmpty() || !manufacturerOk) return
        when {
            !adding -> onConfirm(values)
            newManufacturer.isNotBlank() -> onConfirmWithManufacturer(newManufacturer.trim(), values)
            else -> onConfirmExisting(selectedManufacturer!!, values)
        }
        onDismiss()
    }

    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (adding) R.string.sources_add_console else R.string.sources_edit_console)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.dialog_console_name_hint))
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.dialog_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(nameFocus)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = shortName,
                    onValueChange = { shortName = it },
                    label = { Text(stringResource(R.string.dialog_short_name)) },
                    placeholder = { if (defaultShort.isNotEmpty()) Text(defaultShort) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = aliases,
                    onValueChange = { aliases = it },
                    label = { Text(stringResource(R.string.dialog_folder_aliases)) },
                    placeholder = { if (defaultAliases.isNotEmpty()) Text(defaultAliases) },
                    supportingText = { Text(stringResource(R.string.dialog_aliases_hint), style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (adding) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(stringResource(R.string.dialog_manufacturer), style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        manufacturers.forEach { m ->
                            val source = rememberFocusSource()
                            FilterChip(
                                selected = selectedManufacturer == m.id && newManufacturer.isBlank(),
                                onClick = { selectedManufacturer = m.id; newManufacturer = "" },
                                label = { Text(m.name, maxLines = 1) },
                                interactionSource = source,
                                modifier = Modifier.focusRing(source, 8.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newManufacturer,
                        onValueChange = { newManufacturer = it },
                        label = { Text(stringResource(R.string.dialog_new_manufacturer)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
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
