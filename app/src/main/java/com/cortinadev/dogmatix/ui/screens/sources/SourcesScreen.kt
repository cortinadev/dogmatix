package com.cortinadev.dogmatix.ui.screens.sources

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.ui.screens.sources.components.AddConsoleDialog
import com.cortinadev.dogmatix.ui.screens.sources.components.AddUrlDialog
import com.cortinadev.dogmatix.ui.screens.sources.components.ConfirmDialog
import com.cortinadev.dogmatix.ui.screens.sources.components.ConsoleCard
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.screens.sources.SourcesViewModel.Dialog as SourcesDialog
import com.cortinadev.dogmatix.ui.screens.sources.components.MergeFoldersDialog
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    viewModel: SourcesViewModel = hiltViewModel()
) {
    val manufacturers by viewModel.manufacturers.collectAsState(initial = emptyList())
    val isRescanning by viewModel.isRescanning.collectAsState()
    val consoleDownloadPaths by viewModel.consoleDownloadPaths.collectAsState()
    val downloadDirectory by viewModel.downloadDirectory.collectAsState()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    val directoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            uri?.let {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                viewModel.finishPickingDownloadPath(uri.toString())
            }
        }
    )

    val rootDirectoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            uri?.let {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                viewModel.updateDownloadDirectory(it.toString())
            }
        }
    )

    val importPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let { viewModel.importSources(it.toString()) } }
    )

    fun shareExport(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.sources_export_share_title)))
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Padding inside the list: cards scroll edge to edge instead of being clipped 16dp early.
        // Cards sit 20dp from the edge, the same x as the app title and the Settings rows' text.
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            SourcesHeader(
                isRescanning = isRescanning,
                isLandscape = isLandscape,
                onAddConsole = { viewModel.showAddConsoleDialog() },
                onRescan = { viewModel.rescanAllSources() },
                onExport = { viewModel.exportSources(::shareExport) },
                onImport = { viewModel.confirmImport() }
            )
        }

        item {
            DownloadDirectoryCard(
                downloadDirectory = downloadDirectory,
                onChange = { rootDirectoryPicker.launch(null) }
            )
        }

        if (manufacturers.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.sources_empty_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.sources_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            // Flat, alphabetical console list; the manufacturer is only a subtitle on each card.
            val consoles = manufacturers
                .flatMap { m -> m.consoles.map { m to it } }
                .sortedBy { (_, c) -> c.name.lowercase() }
            items(consoles, key = { (_, c) -> c.id }) { (manufacturer, console) ->
                ConsoleCard(
                    console = console,
                    subtitle = manufacturer.name,
                    downloadPath = consoleDownloadPaths[console.id],
                    onAddUrl = { viewModel.showAddUrlDialog(console.id) },
                    onEditConsole = { viewModel.showEditConsoleDialog(console) },
                    onDeleteConsole = { viewModel.confirmDeleteConsole(console) },
                    onEditUrl = { index, entry -> viewModel.showEditUrlDialog(console.id, index, entry) },
                    onDeleteUrl = { index, entry -> viewModel.confirmDeleteUrl(console.id, index, entry) },
                    onToggleUrl = { index, enabled -> viewModel.setUrlEnabled(console.id, index, enabled) },
                    onSetCustomDownloadPath = {
                        viewModel.beginPickingDownloadPath(console.id)
                        directoryPicker.launch(null)
                    },
                    onRefreshConsole = { viewModel.refreshConsole(console.id) },
                    onMergeFolders = { viewModel.showMergeDialog(console.id) }
                )
            }
        }
    }

    val dialog by viewModel.dialog.collectAsState()

    val rommPlatforms by viewModel.rommPlatforms.collectAsState()
    val importMessage by viewModel.importMessage.collectAsState()

    val mergeConsoleId by viewModel.mergeConsoleId.collectAsState()
    val mergeInProgress by viewModel.mergeInProgress.collectAsState()
    val mergeResult by viewModel.mergeResult.collectAsState()
    mergeConsoleId?.let { consoleId ->
        val path = consoleDownloadPaths[consoleId]
        val consoleName = manufacturers.flatMap { it.consoles }.firstOrNull { it.id == consoleId }?.name ?: consoleId
        if (path != null || mergeResult != null) {
            MergeFoldersDialog(
                consoleName = consoleName,
                folders = path?.let { listOf(it.subPath) + it.alternatives }.orEmpty(),
                inProgress = mergeInProgress,
                result = mergeResult,
                onMerge = { target -> viewModel.mergeFolders(consoleId, target) },
                onKeep = { viewModel.keepFoldersAsIs(consoleId) },
                onDismiss = { viewModel.hideMergeDialog() }
            )
        }
    }

    when (val d = dialog) {
        null -> Unit
        SourcesDialog.AddConsole -> AddConsoleDialog(
            manufacturers = manufacturers,
            onDismiss = { viewModel.dismissDialog() },
            onConfirmWithManufacturer = { manufacturerName, values -> viewModel.addConsoleUnderNewManufacturer(manufacturerName, values.name, values.shortName, values.aliases) },
            onConfirmExisting = { manufacturerId, values -> viewModel.addConsole(manufacturerId, values.name, values.shortName, values.aliases) }
        )
        is SourcesDialog.EditConsole -> AddConsoleDialog(
            existing = d.console,
            onDismiss = { viewModel.dismissDialog() },
            onConfirm = { values -> viewModel.updateConsole(d.console.id, values.name, values.shortName, values.aliases) }
        )
        is SourcesDialog.AddUrl -> AddUrlDialog(
            rommPlatforms = rommPlatforms,
            onDismiss = { viewModel.dismissDialog() },
            onConfirm = { url, contentType -> viewModel.addUrl(d.consoleId, url, contentType) }
        )
        is SourcesDialog.EditUrl -> AddUrlDialog(
            existing = d.entry,
            rommPlatforms = rommPlatforms,
            onDismiss = { viewModel.dismissDialog() },
            onConfirm = { url, contentType -> viewModel.updateUrl(d.consoleId, d.index, url, contentType) }
        )
        is SourcesDialog.ConfirmDeleteConsole -> ConfirmDialog(
            title = stringResource(R.string.sources_delete_console_title, d.name),
            message = stringResource(R.string.sources_delete_console_message),
            confirmText = stringResource(R.string.dialog_delete),
            onConfirm = { viewModel.deleteConsole(d.consoleId) },
            onDismiss = { viewModel.dismissDialog() }
        )
        is SourcesDialog.ConfirmDeleteUrl -> ConfirmDialog(
            title = stringResource(R.string.sources_delete_url_title),
            message = stringResource(R.string.sources_delete_url_message, d.url.take(80)),
            confirmText = stringResource(R.string.dialog_delete),
            onConfirm = { viewModel.deleteUrl(d.consoleId, d.index) },
            onDismiss = { viewModel.dismissDialog() }
        )
        SourcesDialog.ConfirmImport -> ConfirmDialog(
            title = stringResource(R.string.sources_import_confirm_title),
            message = stringResource(R.string.sources_import_confirm_message),
            confirmText = stringResource(R.string.sources_import_pick),
            onConfirm = { importPicker.launch(arrayOf("application/json", "application/octet-stream", "text/*")) },
            onDismiss = { viewModel.dismissDialog() }
        )
    }

    importMessage?.let { message ->
        val okFocus = rememberInitialFocus()
        AlertDialog(
            modifier = Modifier.closeOnGamepadB { viewModel.clearImportMessage() },
            onDismissRequest = { viewModel.clearImportMessage() },
            title = { Text(stringResource(R.string.sources_import_result_title)) },
            text = { Text(message) },
            confirmButton = {
                DialogButton(stringResource(R.string.dialog_ok), onClick = { viewModel.clearImportMessage() }, initialFocus = okFocus)
            }
        )
    }
}

/**
 * Root download directory. Per-console folders (see each console's "Download path")
 * are detected inside — or created under — this directory.
 */
@Composable
private fun DownloadDirectoryCard(
    downloadDirectory: String,
    onChange: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = LocalDogmatixTokens.current.card),
        onClick = onChange
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_folder),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_download_directory),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = if (downloadDirectory.isBlank()) stringResource(R.string.sources_directory_unset)
                           else FileParsingUtils.toUserReadablePath(downloadDirectory),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (downloadDirectory.isBlank()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(
                onClick = onChange,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = stringResource(if (downloadDirectory.isBlank()) R.string.sources_choose else R.string.settings_change),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun SourcesHeader(
    isRescanning: Boolean,
    isLandscape: Boolean,
    onAddConsole: () -> Unit,
    onRescan: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    // The section name already lives in the tabs; only the actions stay, right-aligned.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onAddConsole) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.sources_add_console))
            }
            IconButton(onClick = onExport) {
                Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.sources_export))
            }
            IconButton(onClick = onImport, enabled = !isRescanning) {
                Icon(painterResource(R.drawable.ic_import), contentDescription = stringResource(R.string.sources_import))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isRescanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = stringResource(R.string.sources_rescanning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    FilledTonalButton(
                        onClick = onRescan,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        )
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_retry),
                            contentDescription = "",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.sources_rescan_all), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
