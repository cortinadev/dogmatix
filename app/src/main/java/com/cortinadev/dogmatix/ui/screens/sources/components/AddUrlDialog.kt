package com.cortinadev.dogmatix.ui.screens.sources.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.ContentType
import com.cortinadev.dogmatix.data.model.UrlEntry
import com.cortinadev.dogmatix.data.service.RommPlatform
import com.cortinadev.dogmatix.util.RommSource
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus

/**
 * Adds a source to a console or, when [existing] is given, edits one in place.
 * Opens with the URL field focused; B cancels.
 */
@Composable
fun AddUrlDialog(
    existing: UrlEntry? = null,
    /** Platforms of the RomM server from Settings; one chip each sets the URL to `romm://<slug>`. */
    rommPlatforms: List<RommPlatform> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (String, ContentType) -> Unit
) {
    var url by remember { mutableStateOf(existing?.url.orEmpty()) }
    var contentType by remember { mutableStateOf(existing?.contentType ?: ContentType.GAME) }
    val urlFocus = rememberInitialFocus()
    val editing = existing != null

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri -> uri?.let { url = it.toString() } }
    )

    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (editing) R.string.dialog_edit_source else R.string.dialog_add_source)) },
        text = {
            Column {
                Text(stringResource(R.string.dialog_add_source_hint))
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.dialog_url_label)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).focusRequester(urlFocus),
                        placeholder = { Text(stringResource(R.string.dialog_url_placeholder)) }
                    )

                    val pickSource = rememberFocusSource()
                    IconButton(
                        onClick = { filePicker.launch("application/x-bittorrent") },
                        interactionSource = pickSource,
                        modifier = Modifier.focusRing(pickSource, 20.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_folder),
                            contentDescription = stringResource(R.string.dialog_upload_torrent)
                        )
                    }
                }

                if (rommPlatforms.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.dialog_from_romm), style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(6.dp))
                    RommPlatformChips(platforms = rommPlatforms, selected = RommSource.slugOf(url)) { url = RommSource.sourceFor(it.slug) }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(stringResource(R.string.dialog_content_type), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                ContentTypeSelection(selectedType = contentType, onTypeSelected = { contentType = it })
            }
        },
        confirmButton = {
            DialogButton(
                text = stringResource(if (editing) R.string.dialog_save else R.string.dialog_add),
                onClick = { onConfirm(url.trim(), contentType); onDismiss() },
                enabled = url.isNotBlank()
            )
        },
        dismissButton = {
            DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss)
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RommPlatformChips(platforms: List<RommPlatform>, selected: String?, onPick: (RommPlatform) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        platforms.forEach { platform ->
            val source = rememberFocusSource()
            FilterChip(
                selected = selected != null && (platform.slug.equals(selected, true) || platform.fsSlug.equals(selected, true)),
                onClick = { onPick(platform) },
                label = { Text(platform.label, maxLines = 1) },
                interactionSource = source,
                modifier = Modifier.focusRing(source, 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContentTypeSelection(
    selectedType: ContentType,
    onTypeSelected: (ContentType) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ContentType.entries.forEach { type ->
            val source = rememberFocusSource()
            FilterChip(
                selected = selectedType == type,
                onClick = { onTypeSelected(type) },
                label = { Text(text = type.name.lowercase().replaceFirstChar { it.uppercase() }, maxLines = 1) },
                interactionSource = source,
                modifier = Modifier.focusRing(source, 8.dp)
            )
        }
    }
}
