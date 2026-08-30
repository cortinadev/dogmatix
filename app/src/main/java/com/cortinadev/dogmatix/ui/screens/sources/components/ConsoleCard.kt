package com.cortinadev.dogmatix.ui.screens.sources.components

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.layout.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.cortinadev.dogmatix.R
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.data.model.Console
import com.cortinadev.dogmatix.data.model.ResolvedDownloadPath
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens

@Composable
fun ConsoleCard(
    console: Console,
    downloadPath: ResolvedDownloadPath?,
    subtitle: String? = null,
    onAddUrl: () -> Unit,
    onEditConsole: () -> Unit,
    onDeleteConsole: () -> Unit,
    onEditUrl: (Int, com.cortinadev.dogmatix.data.model.UrlEntry) -> Unit,
    onDeleteUrl: (Int, com.cortinadev.dogmatix.data.model.UrlEntry) -> Unit,
    onToggleUrl: (Int, Boolean) -> Unit,
    onSetCustomDownloadPath: () -> Unit,
    onRefreshConsole: () -> Unit,
    onMergeFolders: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    // D-pad: when any button inside gains focus, scroll the whole card into view (not just the button),
    // so the download-path line of the last card is never left hidden.
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .onFocusChanged { if (it.hasFocus) scope.launch { bringIntoView.bringIntoView() } },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = LocalDogmatixTokens.current.card)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = console.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (subtitle != null) {
                        Text(
                            text = "$subtitle · ${com.cortinadev.dogmatix.util.ConsoleFormatter.getConsoleShortName(console.id)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy((-12).dp)
                ) {
                    IconButton(onClick = onAddUrl) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.sources_add_url))
                    }
                    IconButton(onClick = onSetCustomDownloadPath) {
                        Icon(painterResource(R.drawable.ic_folder), contentDescription = stringResource(R.string.sources_set_custom_path))
                    }
                    IconButton(onClick = onRefreshConsole) {
                        Icon(painterResource(R.drawable.ic_retry), contentDescription = stringResource(R.string.sources_refresh_console))
                    }
                    IconButton(onClick = onEditConsole) {
                        Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.sources_edit_console))
                    }
                    IconButton(onClick = onDeleteConsole) {
                        Icon(painterResource(R.drawable.ic_trash), contentDescription = stringResource(R.string.sources_delete_console))
                    }
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            painterResource(if (expanded) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down),
                            contentDescription = stringResource(if (expanded) R.string.sources_collapse else R.string.sources_expand)
                        )
                    }
                }
            }

            if (downloadPath != null) {
                val (text, color) = when (downloadPath.source) {
                    ResolvedDownloadPath.Source.CUSTOM ->
                        stringResource(R.string.sources_path_custom, downloadPath.displayPath) to MaterialTheme.colorScheme.primary
                    ResolvedDownloadPath.Source.DETECTED ->
                        stringResource(R.string.sources_path, downloadPath.displayPath) to MaterialTheme.colorScheme.onSurfaceVariant
                    ResolvedDownloadPath.Source.WILL_CREATE ->
                        stringResource(R.string.sources_path_will_create, downloadPath.displayPath) to MaterialTheme.colorScheme.onSurfaceVariant
                    ResolvedDownloadPath.Source.ROOT ->
                        stringResource(R.string.sources_path, downloadPath.displayPath) to MaterialTheme.colorScheme.onSurfaceVariant
                    ResolvedDownloadPath.Source.UNSET ->
                        stringResource(R.string.sources_path_unset) to MaterialTheme.colorScheme.error
                }
                Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
                if (downloadPath.alternatives.isNotEmpty()) {
                    val all = listOf(downloadPath.subPath) + downloadPath.alternatives
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.merge_folders_notice, all.size, all.joinToString(", ")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onMergeFolders, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(stringResource(R.string.merge_folders_action), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    console.urls.forEachIndexed { index, urlEntry ->
                        UrlItem(
                            urlEntry = urlEntry,
                            onEdit = { onEditUrl(index, urlEntry) },
                            onDelete = { onDeleteUrl(index, urlEntry) },
                            onToggle = { onToggleUrl(index, it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UrlItem(
    urlEntry: com.cortinadev.dogmatix.data.model.UrlEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    // Disabled sources stay listed but visibly muted; the switch is the first control in the row.
    val contentAlpha = if (urlEntry.enabled) 1f else 0.45f
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f).alpha(contentAlpha)
            ) {
                val displayText = if (urlEntry.url.length > 200) {
                    urlEntry.url.take(197) + "..."
                } else {
                    urlEntry.url
                }
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = if (urlEntry.enabled) stringResource(R.string.sources_url_type, urlEntry.contentType.name.lowercase())
                           else stringResource(R.string.sources_url_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            com.cortinadev.dogmatix.ui.screens.settings.ThemedSwitch(checked = urlEntry.enabled, onChange = onToggle)
            IconButton(onClick = onEdit) {
                Icon(
                    painterResource(R.drawable.ic_edit),
                    contentDescription = stringResource(R.string.sources_edit_url)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    painterResource(R.drawable.ic_trash),
                    contentDescription = stringResource(R.string.sources_delete_url),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
