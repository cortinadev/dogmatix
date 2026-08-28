package com.cortinadev.dogmatix.ui.screens.home

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.stripExtension
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.GamepadButton
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.screens.home.components.FilterOption
import com.cortinadev.dogmatix.ui.screens.home.components.FilterPanel
import com.cortinadev.dogmatix.ui.screens.home.components.FilterRowSpec
import com.cortinadev.dogmatix.ui.screens.home.components.GameDetailsDialog
import com.cortinadev.dogmatix.ui.screens.home.components.RomRow
import com.cortinadev.dogmatix.ui.screens.home.components.SearchField
import com.cortinadev.dogmatix.util.ConsoleFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val SORT_ASC = "asc"
private const val SORT_DESC = "desc"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val results by viewModel.results.collectAsState()
    val hasMoreResults by viewModel.hasMoreResults.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val query by viewModel.searchQuery.collectAsState()
    val selectedConsoles by viewModel.selectedConsoles.collectAsState()
    val activeTags by viewModel.activeTags.collectAsState()
    val sortAsc by viewModel.sortAsc.collectAsState()
    val consolesWithFiles by viewModel.consolesWithFiles.collectAsState()
    val categorizedTags by viewModel.categorizedTags.collectAsState()
    val ownedKeys by viewModel.ownedKeys.collectAsState()
    val activeDownloads by viewModel.activeDownloads.collectAsState()
    val favoriteLanguages by viewModel.favoriteLanguages.collectAsState()
    val detailsState by viewModel.details.collectAsState()

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var snackbarJob by remember { mutableStateOf<Job?>(null) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    val searchFocus = remember { FocusRequester() }
    val filterFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    var searchActive by remember { mutableStateOf(false) }
    var filtersHaveFocus by remember { mutableStateOf(false) }
    var listHasFocus by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var expandedFilter by remember { mutableStateOf<String?>(null) }
    // Row under the D-pad cursor: X opens its details card.
    var focusedItem by remember { mutableStateOf<DownloadableFileWithTags?>(null) }
    val focusManager = LocalFocusManager.current

    val consoleOptions = remember(consolesWithFiles) {
        consolesWithFiles.map { FilterOption(it.id, ConsoleFormatter.getConsoleFolderName(it.id), ConsoleFormatter.getConsoleShortName(it.id)) }
    }
    fun tagRow(label: String, tags: List<String>, featured: Set<String>? = null) = FilterRowSpec(
        label = label,
        options = tags.map { FilterOption(it, it) },
        selected = activeTags.filter { it in tags }.toSet(),
        featured = featured,
        onSelectionChange = { viewModel.setTagsInCategory(tags, it) }
    )
    val filterRows = listOf(
        FilterRowSpec(
            label = stringResource(R.string.filter_console),
            options = consoleOptions,
            selected = selectedConsoles,
            onSelectionChange = viewModel::setConsoleSelection
        ),
        tagRow(stringResource(R.string.filter_region), categorizedTags?.regions?.tags.orEmpty()),
        tagRow(stringResource(R.string.filter_language), categorizedTags?.languages?.tags.orEmpty(), featured = favoriteLanguages),
        tagRow(stringResource(R.string.filter_type), categorizedTags?.contentTypes?.tags.orEmpty()),
        tagRow(stringResource(R.string.filter_file_type), categorizedTags?.fileTypes?.tags.orEmpty()),
        FilterRowSpec(
            label = stringResource(R.string.filter_sort),
            options = listOf(
                FilterOption(SORT_ASC, stringResource(R.string.sort_asc)),
                FilterOption(SORT_DESC, stringResource(R.string.sort_desc))
            ),
            selected = setOf(if (sortAsc) SORT_ASC else SORT_DESC),
            single = true,
            onSelectionChange = { viewModel.setSortAsc(SORT_ASC in it) }
        )
    )
    val activeFilterCount = selectedConsoles.size + activeTags.size

    val startedMessage = stringResource(R.string.download_started, "%s")
    val deletedMessage = stringResource(R.string.owned_deleted, "%s")
    val deleteFailedMessage = stringResource(R.string.owned_delete_failed, "%s")
    val alreadyDownloadingMessage = stringResource(R.string.download_already_active, "%s")
    val showMessage: (String) -> Unit = { message ->
        snackbarJob?.cancel()
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarJob = scope.launch {
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
        }
    }
    val download: (DownloadableFileWithTags) -> Unit = { item ->
        scope.launch { viewModel.startDownload(item, context) }
        showMessage(startedMessage.format(item.file.name))
    }
    // Tapping a game already on disk asks whether to fetch it again or remove it.
    var ownedDialogItem by remember { mutableStateOf<DownloadableFileWithTags?>(null) }
    val onFileClick: (DownloadableFileWithTags) -> Unit = { item ->
        when {
            viewModel.isDownloading(item.file, activeDownloads) -> showMessage(alreadyDownloadingMessage.format(item.file.name))
            viewModel.isOwned(item.file, ownedKeys) -> ownedDialogItem = item
            else -> download(item)
        }
    }

    ownedDialogItem?.let { item ->
        AlertDialog(
            onDismissRequest = { ownedDialogItem = null },
            title = { Text(stripExtension(item.file.name)) },
            text = { Text(stringResource(R.string.owned_dialog_message)) },
            confirmButton = {
                TextButton(onClick = {
                    ownedDialogItem = null
                    download(item)
                }) { Text(stringResource(R.string.owned_download_again)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    ownedDialogItem = null
                    scope.launch {
                        val ok = viewModel.deleteOwned(item)
                        showMessage((if (ok) deletedMessage else deleteFailedMessage).format(item.file.name))
                    }
                }) { Text(stringResource(R.string.owned_delete)) }
            }
        )
    }

    detailsState?.let { state ->
        GameDetailsDialog(
            state = state,
            consoleName = ConsoleFormatter.getConsoleShortName(state.item.file.consoleId),
            onDownload = { viewModel.closeDetails(); onFileClick(state.item) },
            onDismiss = viewModel::closeDetails
        )
    }

    // Button legend follows where the focus is.
    // Portrait has no room for the panel entry; X covers that hop there anyway.
    val panels = if (isLandscape) listOf(LegendEntry("LB · RB", stringResource(R.string.pad_panels))) else emptyList()
    val section = LegendEntry("ZL · ZR", stringResource(R.string.pad_section))
    val legendList = listOf(
        LegendEntry("A", stringResource(R.string.pad_download)), LegendEntry("B", stringResource(R.string.pad_back)),
        LegendEntry("X", stringResource(R.string.pad_details)), LegendEntry("Y", stringResource(R.string.pad_search))
    ) + panels + section
    val legendFilters = listOf(
        LegendEntry("A", stringResource(R.string.pad_options)), LegendEntry("◀ ▶", stringResource(R.string.pad_change)),
        LegendEntry("B", stringResource(R.string.pad_back))
    ) + panels + section
    val legendDetails = listOf(
        LegendEntry("A", stringResource(R.string.pad_select)), LegendEntry("B", stringResource(R.string.pad_close)),
        LegendEntry("▲ ▼", stringResource(R.string.pad_scroll))
    )
    val legendSearch = listOf(
        LegendEntry("A", stringResource(R.string.pad_keyboard)), LegendEntry("B", stringResource(R.string.pad_close_keyboard))
    )
    val legend = when {
        detailsState != null -> legendDetails
        searchActive -> legendSearch
        filtersHaveFocus || showFilterSheet -> legendFilters
        else -> legendList
    }
    LaunchedEffect(legend) { Gamepad.legendOverride.value = legend }
    // Only clear our own legend: another screen may already have published its own during the transition.
    DisposableEffect(legend) { onDispose { if (Gamepad.legendOverride.value == legend) Gamepad.legendOverride.value = null } }

    // B / Back undoes one layer at a time and never leaves the app from here (Home is the root).
    BackHandler {
        when {
            showFilterSheet -> showFilterSheet = false
            expandedFilter != null -> expandedFilter = null
            searchActive -> { focusManager.clearFocus(); searchActive = false }
            query.isNotEmpty() -> viewModel.setSearch("")
            activeFilterCount > 0 || !sortAsc -> viewModel.clearAllFilters()
            else -> runCatching { Gamepad.sectionFocus.requestFocus() }
        }
    }

    LaunchedEffect(isLandscape) {
        Gamepad.presses.collect { button ->
            when (button) {
                // X shows the details card of the row under the cursor (LB / RB move between panels).
                GamepadButton.X -> focusedItem?.takeIf { listHasFocus }?.let { viewModel.openDetails(it) }
                GamepadButton.Y -> searchActive = true
                GamepadButton.PREV_PANEL -> if (isLandscape) runCatching { filterFocus.requestFocus() } else showFilterSheet = true
                GamepadButton.NEXT_PANEL -> if (isLandscape) {
                    if (runCatching { listFocus.requestFocus() }.isFailure) focusManager.moveFocus(FocusDirection.Right)
                } else showFilterSheet = false
                else -> Unit
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLandscape) {
            Row(modifier = Modifier.fillMaxSize()) {
                FilterPanel(
                    rows = filterRows,
                    onClear = viewModel::clearAllFilters,
                    compact = true,
                    firstRowFocus = filterFocus,
                    expandedRow = expandedFilter,
                    onExpandedRowChange = { expandedFilter = it },
                    modifier = Modifier
                        .width(216.dp)
                        .fillMaxHeight()
                        .onFocusChanged { filtersHaveFocus = it.hasFocus }
                        .focusGroup(),
                    footer = {
                        Text(
                            resultsLabel(results.size, hasMoreResults),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp)
                        )
                    }
                )
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = 12.dp, end = 12.dp, top = 12.dp)
                ) {
                    SearchField(
                        value = query,
                        onValueChange = viewModel::setSearch,
                        active = searchActive,
                        onActivate = { searchActive = true },
                        onDismiss = { searchActive = false; runCatching { listFocus.requestFocus() } },
                        focusRequester = searchFocus
                    )
                    TableHeader()
                    ResultList(
                        results = results,
                        compact = true,
                        hasMore = hasMoreResults,
                        isLoadingMore = isLoadingMore,
                        onLoadMore = { scope.launch { viewModel.loadMore() } },
                        getConsoleName = { ConsoleFormatter.getConsoleShortName(it) },
                        onFileClick = onFileClick,
                        isOwned = { viewModel.isOwned(it.file, ownedKeys) },
                        isDownloading = { viewModel.isDownloading(it.file, activeDownloads) },
                        onRowFocused = { focusedItem = it },
                        onRowLongClick = viewModel::openDetails,
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { listHasFocus = it.hasFocus }
                            .focusGroup(),
                        firstRowFocus = listFocus
                    )
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SearchField(
                        value = query,
                        onValueChange = viewModel::setSearch,
                        active = searchActive,
                        onActivate = { searchActive = true },
                        onDismiss = { searchActive = false; runCatching { listFocus.requestFocus() } },
                        focusRequester = searchFocus,
                        modifier = Modifier.weight(1f)
                    )
                    FilterButton(count = activeFilterCount) { showFilterSheet = true }
                }
                ConsoleChips(
                    options = consoleOptions,
                    selected = selectedConsoles,
                    onSelect = viewModel::setConsoleSelection,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
                Text(
                    resultsLabel(results.size, hasMoreResults),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 30.dp, bottom = 6.dp)
                )
                ResultList(
                    results = results,
                    compact = false,
                    hasMore = hasMoreResults,
                    isLoadingMore = isLoadingMore,
                    onLoadMore = { scope.launch { viewModel.loadMore() } },
                    getConsoleName = { ConsoleFormatter.getConsoleShortName(it) },
                    onFileClick = onFileClick,
                    isOwned = { viewModel.isOwned(it.file, ownedKeys) },
                    isDownloading = { viewModel.isDownloading(it.file, activeDownloads) },
                    onRowFocused = { focusedItem = it },
                    onRowLongClick = viewModel::openDetails,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                        .onFocusChanged { listHasFocus = it.hasFocus },
                    firstRowFocus = listFocus
                )
            }

            if (showFilterSheet) {
                val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ModalBottomSheet(
                    onDismissRequest = { showFilterSheet = false },
                    sheetState = sheetState,
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    dragHandle = {
                        Box(
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .width(36.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        )
                    }
                ) {
                    // The sheet is its own window: gamepad keys never reach the Activity while it
                    // is open, so the shortcuts that leave the sheet are handled right here.
                    LaunchedEffect(Unit) { runCatching { filterFocus.requestFocus() } }
                    Box(
                        modifier = Modifier.onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.ButtonR1 -> { showFilterSheet = false; true }
                                Key.ButtonY -> { showFilterSheet = false; searchActive = true; true }
                                Key.ButtonL2 -> { showFilterSheet = false; Gamepad.presses.tryEmit(GamepadButton.PREV_TAB); true }
                                Key.ButtonR2 -> { showFilterSheet = false; Gamepad.presses.tryEmit(GamepadButton.NEXT_TAB); true }
                                else -> false
                            }
                        }
                    ) {
                    FilterPanel(
                        rows = filterRows,
                        onClear = viewModel::clearAllFilters,
                        compact = false,
                        firstRowFocus = filterFocus,
                        expandedRow = expandedFilter,
                        onExpandedRowChange = { expandedFilter = it },
                        footer = {
                            Button(
                                onClick = { showFilterSheet = false },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp)
                                    .height(46.dp)
                            ) {
                                Text(stringResource(R.string.show_results))
                            }
                        }
                    )
                    }
                }
            }
        }

        SnackbarHost(
            snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        )
    }
}

@Composable
private fun resultsLabel(count: Int, hasMore: Boolean): String =
    stringResource(if (hasMore) R.string.results_count_more else R.string.results_count, count)

@Composable
private fun TableHeader() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelMedium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.column_name), style = style, color = color, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.column_tags), style = style, color = color, modifier = Modifier.width(300.dp))
        Text(stringResource(R.string.column_size), style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
    }
}

@Composable
private fun ResultList(
    results: List<DownloadableFileWithTags>,
    compact: Boolean,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    getConsoleName: (String) -> String,
    onFileClick: (DownloadableFileWithTags) -> Unit,
    isOwned: (DownloadableFileWithTags) -> Boolean,
    isDownloading: (DownloadableFileWithTags) -> Boolean,
    onRowFocused: (DownloadableFileWithTags) -> Unit,
    onRowLongClick: (DownloadableFileWithTags) -> Unit,
    modifier: Modifier = Modifier,
    firstRowFocus: FocusRequester? = null
) {
    if (results.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    val listState = rememberLazyListState()
    val focusIndex = listState.firstVisibleItemIndex
    // After "Load more" the button is replaced by a spinner and focus is lost:
    // remember where the new page starts and land on its first row once it arrives.
    var pendingFocusIndex by remember { mutableStateOf<Int?>(null) }
    val newPageFocus = remember { FocusRequester() }
    LaunchedEffect(results.size) {
        val target = pendingFocusIndex ?: return@LaunchedEffect
        if (target >= results.size) {
            pendingFocusIndex = null
            return@LaunchedEffect
        }
        listState.scrollToItem(target)
        withFrameNanos { }
        runCatching { newPageFocus.requestFocus() }
        pendingFocusIndex = null
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        itemsIndexed(results, key = { _, it -> it.file.id }) { index, item ->
            RomRow(
                item = item,
                consoleName = getConsoleName(item.file.consoleId),
                compact = compact,
                onClick = { onFileClick(item) },
                onLongClick = { onRowLongClick(item) },
                owned = isOwned(item),
                downloading = isDownloading(item),
                // RB from the filters lands on the first row currently on screen.
                modifier = when {
                    index == pendingFocusIndex -> Modifier.focusRequester(newPageFocus)
                    index == focusIndex && firstRowFocus != null -> Modifier.focusRequester(firstRowFocus)
                    else -> Modifier
                }.onFocusChanged { if (it.isFocused) onRowFocused(item) }
            )
        }
        if (hasMore) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoadingMore) {
                        CircularProgressIndicator()
                    } else {
                        Button(
                            onClick = { pendingFocusIndex = results.size; onLoadMore() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.load_more))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterButton(count: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val active = count > 0
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) scheme.primary else scheme.surfaceContainerHigh)
            .focusRing(source, 10.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val fg = if (active) scheme.onPrimary else scheme.onSurface
        Icon(painterResource(R.drawable.ic_filter), contentDescription = null, tint = fg, modifier = Modifier.width(16.dp))
        Text(stringResource(R.string.filters), style = MaterialTheme.typography.labelLarge, color = fg)
        if (active) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(scheme.surface)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
private fun ConsoleChips(
    options: List<FilterOption>,
    selected: Set<String>,
    onSelect: (Set<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item { ConsoleChip(stringResource(R.string.filter_all), selected.isEmpty()) { onSelect(emptySet()) } }
        items(options, key = { it.id }) { option ->
            val isOnly = selected.size == 1 && option.id in selected
            ConsoleChip(option.shortLabel, isOnly) { onSelect(if (isOnly) emptySet() else setOf(option.id)) }
        }
    }
}

@Composable
private fun ConsoleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) scheme.onPrimary else scheme.secondary,
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) scheme.primary else scheme.surfaceContainer)
            .focusRing(source, 20.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp)
            .wrapContentHeight()
    )
}
