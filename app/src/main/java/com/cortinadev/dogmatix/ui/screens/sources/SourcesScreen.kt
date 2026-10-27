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
import com.cortinadev.dogmatix.ui.screens.sources.components.EmptyManufacturerCard
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.screens.sources.SourcesViewModel.Dialog as SourcesDialog
import com.cortinadev.dogmatix.ui.screens.sources.components.MergeFoldersDialog
import com.cortinadev.dogmatix.ui.screens.sources.components.ScanActions
import com.cortinadev.dogmatix.ui.screens.sources.components.ScanStatusCard
import com.cortinadev.dogmatix.ui.screens.sources.components.ScanReportDialog
import androidx.compose.runtime.saveable.rememberSaveable
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens
// busqueda
import androidx.compose.foundation.lazy.rememberLazyListState
import com.cortinadev.dogmatix.data.model.Manufacturer
import com.cortinadev.dogmatix.data.search.SearchTarget
import com.cortinadev.dogmatix.ui.navigation.SearchTargetEffect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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
    // escaneo: progress of the running scan and the report of the last one.
    val scanProgress by viewModel.scanProgress.collectAsState()
    val scanMessage by viewModel.scanMessage.collectAsState()
    val lastReport by viewModel.lastReport.collectAsState()
    var showReport by rememberSaveable { mutableStateOf(false) }
    // Only while this screen is visible: a scan it started opens its report when it ends.
    LaunchedEffect(Unit) { viewModel.scanFinished.collect { showReport = true } }

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

    // busqueda: a library search result unfolds its console and focuses it (or one of its addresses).
    val listState = rememberLazyListState()
    var reveal by remember { mutableStateOf<SearchTarget.Source?>(null) }
    SearchTargetEffect<SearchTarget.Source> { target ->
        // The list on screen, not a fresh query: the index must be the one the list shows.
        val all = withTimeoutOrNull(3_000) {
            snapshotFlow { manufacturers }.first { ms -> ms.any { m -> m.consoles.any { it.id == target.consoleId } } }
        } ?: return@SearchTargetEffect
        // Above the cards: the header, the scan buttons (portrait), the scan status and the folder card.
        val leading = 2 + (if (isLandscape) 0 else 1) + (if (isRescanning || lastReport != null) 1 else 0)
        listState.scrollToItem(leading + sortedConsoles(all).indexOfFirst { (_, c) -> c.id == target.consoleId })
        reveal = target
    }

    // Deleting an empty manufacturer: the focus goes to its neighbour (the next one, else the one
    // before, else the last console, else "Add console"), never left on a card that has gone.
    val addConsoleFocus = remember { FocusRequester() }
    val lastConsoleFocus = remember { FocusRequester() }
    val manufacturerFocus = remember { mutableMapOf<String, FocusRequester>() }
    /** The manufacturer being deleted and the empty one that takes the focus after it (null = none left). */
    var refocus by remember { mutableStateOf<Pair<String, String?>?>(null) }
    LaunchedEffect(manufacturers) {
        val (deleted, neighbour) = refocus ?: return@LaunchedEffect
        if (manufacturers.any { it.id == deleted }) return@LaunchedEffect
        refocus = null
        val consoles = sortedConsoles(manufacturers)
        val empties = emptyManufacturers(manufacturers)
        // Same items above the cards as the search jump; the empty ones follow the consoles (or the "no sources" card).
        val firstCard = 2 + (if (isLandscape) 0 else 1) + (if (isRescanning || lastReport != null) 1 else 0)
        val firstEmpty = firstCard + maxOf(consoles.size, 1)
        val next = neighbour?.let { id -> empties.indexOfFirst { it.id == id } }?.takeIf { it >= 0 }
        val (index, requester) = when {
            next != null -> firstEmpty + next to manufacturerFocus.getOrPut(empties[next].id) { FocusRequester() }
            consoles.isNotEmpty() -> firstCard + consoles.size - 1 to lastConsoleFocus
            else -> 0 to addConsoleFocus
        }
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
        // The dialog's window has just gone: give the screen's window a couple of frames to take the focus back.
        withFrameNanos { }
        withFrameNanos { }
        repeat(5) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    LazyColumn(
        state = listState,
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
                addConsoleFocus = addConsoleFocus,
                onRescan = { viewModel.rescanAllSources() },
                onFullRescan = { viewModel.rescanAllSources(force = true) },
                onExport = { viewModel.exportSources(::shareExport) },
                onImport = { viewModel.confirmImport() }
            )
        }

        if (!isLandscape) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ScanActions(isRescanning, onRescan = { viewModel.rescanAllSources() }, onFullRescan = { viewModel.rescanAllSources(force = true) })
                }
            }
        }
        if (isRescanning || lastReport != null) {
            item(key = "scan-status") {
                ScanStatusCard(isRescanning, scanProgress, scanMessage, lastReport, onOpenReport = { showReport = true })
            }
        }

        item {
            DownloadDirectoryCard(
                downloadDirectory = downloadDirectory,
                onChange = { rootDirectoryPicker.launch(null) }
            )
        }

        // Flat, alphabetical console list; the manufacturer is only a subtitle on each card.
        val consoles = sortedConsoles(manufacturers)
        if (consoles.isEmpty()) {
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
            items(consoles, key = { (_, c) -> c.id }) { (manufacturer, console) ->
                ConsoleCard(
                    focusTarget = lastConsoleFocus.takeIf { console.id == consoles.last().second.id },
                    reveal = reveal?.takeIf { it.consoleId == console.id },
                    onRevealed = { reveal = null },
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

        // Manufacturers left without consoles: invisible in a list of consoles, so they get a card
        // of their own, after them, with the one thing left to do (delete it).
        items(emptyManufacturers(manufacturers), key = { "manufacturer:${it.id}" }) { manufacturer ->
            EmptyManufacturerCard(
                name = manufacturer.name,
                onDelete = { viewModel.confirmDeleteManufacturer(manufacturer) },
                focusRequester = manufacturerFocus.getOrPut(manufacturer.id) { FocusRequester() }
            )
        }
    }

    val dialog by viewModel.dialog.collectAsState()

    val rommPlatforms by viewModel.rommPlatforms.collectAsState()
    val scrapeTest by viewModel.scrapeTest.collectAsState()
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
            onConfirmWithManufacturer = { manufacturerName, values -> viewModel.addConsoleUnderNewManufacturer(manufacturerName, values.name, values.shortName, values.aliases, values.color, values.key) },
            onConfirmExisting = { manufacturerId, values -> viewModel.addConsole(manufacturerId, values.name, values.shortName, values.aliases, values.color, values.key) }
        )
        is SourcesDialog.EditConsole -> AddConsoleDialog(
            existing = d.console,
            onDismiss = { viewModel.dismissDialog() },
            onConfirm = { values -> viewModel.updateConsole(d.console.id, values.name, values.shortName, values.aliases, values.color) }
        )
        is SourcesDialog.AddUrl -> AddUrlDialog(
            rommPlatforms = rommPlatforms,
            scrapeTest = scrapeTest,
            onTestScrape = viewModel::testScrape,
            onResetScrapeTest = viewModel::resetScrapeTest,
            onDismiss = { viewModel.dismissDialog() },
            onConfirm = { v -> viewModel.addUrl(d.consoleId, v.url, v.contentType, v.mirrors, v.scrape) }
        )
        is SourcesDialog.EditUrl -> AddUrlDialog(
            existing = d.entry,
            rommPlatforms = rommPlatforms,
            scrapeTest = scrapeTest,
            onTestScrape = viewModel::testScrape,
            onResetScrapeTest = viewModel::resetScrapeTest,
            onDismiss = { viewModel.dismissDialog() },
            onConfirm = { v -> viewModel.updateUrl(d.consoleId, d.index, v.url, v.contentType, v.mirrors, v.scrape) }
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
        is SourcesDialog.ConfirmDeleteManufacturer -> ConfirmDialog(
            title = stringResource(R.string.sources_delete_manufacturer_title, d.name),
            message = stringResource(R.string.sources_delete_manufacturer_message),
            confirmText = stringResource(R.string.dialog_delete),
            onConfirm = {
                val empties = emptyManufacturers(manufacturers)
                val at = empties.indexOfFirst { it.id == d.manufacturerId }
                refocus = d.manufacturerId to (empties.getOrNull(at + 1) ?: empties.getOrNull(at - 1))?.id
                viewModel.deleteManufacturer(d.manufacturerId)
            },
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

    if (showReport) {
        lastReport?.let { report ->
            ScanReportDialog(
                report = report,
                canRetry = !isRescanning,
                onRetryFailed = { viewModel.retryFailedSources() },
                onDismiss = { showReport = false }
            )
        }
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

/** Flat, alphabetical console list; the manufacturer is only a subtitle on each card. */
private fun sortedConsoles(manufacturers: List<Manufacturer>) = manufacturers
    .flatMap { m -> m.consoles.map { m to it } }
    .sortedBy { (_, c) -> c.name.lowercase() }

/** Manufacturers without a console, alphabetical: they go after the consoles, each with its delete button. */
private fun emptyManufacturers(manufacturers: List<Manufacturer>) = manufacturers
    .filter { it.consoles.isEmpty() }
    .sortedBy { it.name.lowercase() }

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
    addConsoleFocus: FocusRequester,
    onRescan: () -> Unit,
    onFullRescan: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    // The section name already lives in the tabs; only the actions stay, right-aligned. In
    // portrait the two scan buttons get a row of their own (below): the five do not fit in one.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onAddConsole, modifier = Modifier.focusRequester(addConsoleFocus)) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.sources_add_console))
            }
            IconButton(onClick = onExport) {
                Icon(painterResource(R.drawable.ic_share), contentDescription = stringResource(R.string.sources_export))
            }
            IconButton(onClick = onImport, enabled = !isRescanning) {
                Icon(painterResource(R.drawable.ic_import), contentDescription = stringResource(R.string.sources_import))
            }
            if (isLandscape) ScanActions(isRescanning, onRescan, onFullRescan)
        }
    }
}
