package com.cortinadev.dogmatix.ui.screens.home

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.layoutId
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Spacer
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.stripExtension
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.model.SortOption
import com.cortinadev.dogmatix.data.model.SourceFilter
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.GamepadButton
import com.cortinadev.dogmatix.ui.common.Legend
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.swapFaceButtons
import com.cortinadev.dogmatix.ui.screens.home.components.FilterOption
import com.cortinadev.dogmatix.ui.screens.home.components.FilterPanel
import com.cortinadev.dogmatix.ui.screens.home.components.FilterRowSpec
import com.cortinadev.dogmatix.ui.screens.home.components.GameDetailsDialog
import com.cortinadev.dogmatix.ui.screens.home.components.RomRow
import com.cortinadev.dogmatix.ui.screens.home.components.SearchField
import com.cortinadev.dogmatix.util.ConsoleFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val FAV_ALL = "all"
private const val FAV_ONLY = "only"

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
    val sort by viewModel.sort.collectAsState()
    val consolesWithFiles by viewModel.consolesWithFiles.collectAsState()
    val categorizedTags by viewModel.categorizedTags.collectAsState()
    val ownedKeys by viewModel.ownedKeys.collectAsState()
    val favouriteKeys by viewModel.favouriteKeys.collectAsState()
    val favouritesOnly by viewModel.favouritesOnly.collectAsState()
    val sourceFilter by viewModel.source.collectAsState()
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
    // Landscape: the filter panel folds into a thin rail (chevron or R3) to give the list more room.
    var filtersCollapsed by rememberSaveable { mutableStateOf(false) }
    var railHasFocus by remember { mutableStateOf(false) }
    // The list is laid out at its wide (rail) width while the panel is folded *and* while it
    // is sliding back in, so the panel covers it progressively; it narrows once the slide ends.
    var listWide by rememberSaveable { mutableStateOf(filtersCollapsed) }
    var expandedFilter by remember { mutableStateOf<String?>(null) }
    // Row under the D-pad cursor: X opens its details card.
    var focusedItem by remember { mutableStateOf<DownloadableFileWithTags?>(null) }
    val focusManager = LocalFocusManager.current
    // Folding hides whichever side holds the focus, so it is handed over explicitly
    // (two frames later when expanding: the panel has to be laid out first).
    val collapseFilters = {
        filtersCollapsed = true
        listWide = true
        if (filtersHaveFocus) runCatching { listFocus.requestFocus() }
    }
    val expandFilters = {
        filtersCollapsed = false
        if (railHasFocus) scope.launch { withFrameNanos { }; withFrameNanos { }; runCatching { filterFocus.requestFocus() } }
    }

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
        tagRow(stringResource(R.string.filter_tag), categorizedTags?.contentTypes?.tags.orEmpty()),
        FilterRowSpec(
            label = stringResource(R.string.filter_favourites),
            options = listOf(
                FilterOption(FAV_ALL, stringResource(R.string.filter_all)),
                FilterOption(FAV_ONLY, stringResource(R.string.favourites_only), stringResource(R.string.favourites_only_short))
            ),
            selected = setOf(if (favouritesOnly) FAV_ONLY else FAV_ALL),
            single = true,
            onSelectionChange = { viewModel.setFavouritesOnly(FAV_ONLY in it) }
        ),
        FilterRowSpec(
            label = stringResource(R.string.filter_source),
            options = listOf(
                FilterOption(SourceFilter.ALL.name, stringResource(R.string.filter_all)),
                FilterOption(SourceFilter.TORRENT.name, stringResource(R.string.source_torrent)),
                FilterOption(SourceFilter.ROMM.name, stringResource(R.string.source_romm)),
                FilterOption(SourceFilter.DIRECT.name, stringResource(R.string.source_direct))
            ),
            selected = setOf(sourceFilter.name),
            single = true,
            onSelectionChange = { sel -> viewModel.setSource(sel.firstOrNull()?.let { SourceFilter.valueOf(it) } ?: SourceFilter.ALL) }
        ),
        FilterRowSpec(
            label = stringResource(R.string.filter_sort),
            options = listOf(
                FilterOption(SortOption.NAME_ASC.name, stringResource(R.string.sort_name_asc)),
                FilterOption(SortOption.NAME_DESC.name, stringResource(R.string.sort_name_desc)),
                FilterOption(SortOption.SIZE_DESC.name, stringResource(R.string.sort_size_desc), stringResource(R.string.sort_size_desc_short)),
                FilterOption(SortOption.SIZE_ASC.name, stringResource(R.string.sort_size_asc), stringResource(R.string.sort_size_asc_short))
            ),
            selected = setOf(sort.name),
            single = true,
            onSelectionChange = { sel -> viewModel.setSort(sel.firstOrNull()?.let { SortOption.valueOf(it) } ?: SortOption.NAME_ASC) }
        )
    )
    val activeFilterCount = selectedConsoles.size + activeTags.size + (if (favouritesOnly) 1 else 0) + (if (sourceFilter != SourceFilter.ALL) 1 else 0)

    val startedMessage = stringResource(R.string.download_started, "%s")
    val favouriteAddedMessage = stringResource(R.string.favourite_added, "%s")
    val favouriteRemovedMessage = stringResource(R.string.favourite_removed, "%s")
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
    val toggleFavourite: (DownloadableFileWithTags) -> Unit = { item ->
        scope.launch {
            val starred = viewModel.toggleFavourite(item)
            showMessage((if (starred) favouriteAddedMessage else favouriteRemovedMessage).format(stripExtension(item.file.name)))
        }
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
            favourite = viewModel.isFavourite(state.item.file, favouriteKeys),
            onToggleFavourite = { toggleFavourite(state.item) },
            onDownload = { viewModel.closeDetails(); onFileClick(state.item) },
            onDismiss = viewModel::closeDetails
        )
    }

    // Button legend follows where the focus is. Kept short: B (back) and LB / RB are left out
    // of the list and filter legends so they fit on one line even on 4:3 screens.
    val filtersKey = if (isLandscape) listOf(LegendEntry("R3", stringResource(R.string.pad_filters))) else emptyList()
    val section = LegendEntry("ZL · ZR", stringResource(R.string.pad_section))
    val legendList = listOf(
        LegendEntry("A", stringResource(R.string.pad_download)), LegendEntry("X", stringResource(R.string.pad_details)),
        LegendEntry("Y", stringResource(R.string.pad_search)), LegendEntry("SELECT", stringResource(R.string.pad_favourite))
    ) + filtersKey + section
    val legendFilters = listOf(
        LegendEntry("A", stringResource(R.string.pad_options)), LegendEntry("◀ ▶", stringResource(R.string.pad_change))
    ) + filtersKey + section
    val legendDetails = listOf(
        LegendEntry("A", stringResource(R.string.pad_select)), LegendEntry("B", stringResource(R.string.pad_close)),
        LegendEntry("SELECT", stringResource(R.string.pad_favourite)), LegendEntry("▲ ▼", stringResource(R.string.pad_scroll))
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
    val published = remember(legend) { Legend(legend) }
    LaunchedEffect(published) { Gamepad.legendOverride.value = published }
    // Only clear our own legend: another screen may already have published its own during the transition.
    DisposableEffect(published) { onDispose { if (Gamepad.legendOverride.value === published) Gamepad.legendOverride.value = null } }

    // B / Back undoes one layer at a time and never leaves the app from here (Home is the root).
    BackHandler {
        when {
            showFilterSheet -> showFilterSheet = false
            expandedFilter != null -> expandedFilter = null
            searchActive -> { focusManager.clearFocus(); searchActive = false }
            query.isNotEmpty() -> viewModel.setSearch("")
            activeFilterCount > 0 || sort != SortOption.NAME_ASC -> viewModel.clearAllFilters()
            else -> runCatching { Gamepad.sectionFocus.requestFocus() }
        }
    }

    LaunchedEffect(isLandscape) {
        Gamepad.presses.collect { button ->
            when (button) {
                // X shows the details card of the row under the cursor (LB / RB move between panels).
                GamepadButton.X -> focusedItem?.takeIf { listHasFocus }?.let { viewModel.openDetails(it) }
                // Select stars the row under the cursor, or the game whose details card is open.
                GamepadButton.FAVOURITE -> (viewModel.details.value?.item ?: focusedItem?.takeIf { listHasFocus })?.let(toggleFavourite)
                GamepadButton.Y -> searchActive = true
                GamepadButton.PREV_PANEL -> if (isLandscape) {
                    filtersCollapsed = false
                    scope.launch { withFrameNanos { }; withFrameNanos { }; runCatching { filterFocus.requestFocus() } }
                } else showFilterSheet = true
                GamepadButton.TOGGLE_FILTERS -> if (isLandscape) {
                    if (filtersCollapsed) expandFilters() else collapseFilters()
                } else showFilterSheet = !showFilterSheet
                GamepadButton.NEXT_PANEL -> if (isLandscape) {
                    if (runCatching { listFocus.requestFocus() }.isFailure) focusManager.moveFocus(FocusDirection.Right)
                } else showFilterSheet = false
                else -> Unit
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLandscape) BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // The panel folds into a thin rail. A full recomposition + relayout of this screen
            // costs ~150 ms on the K56 (debug build), so a toggle must trigger exactly one: panel
            // and rail stay composed (the hidden one sits off-screen and cannot take focus), every
            // child is measured once at a fixed size and the animated width is read only while
            // placing. No alpha fade either: an alpha graphicsLayer renders the panel to an
            // offscreen buffer and the GPU wait alone was ~190 ms per frame.
            val panelWidth = 216.dp
            val railWidth = 48.dp
            val dividerWidth = 1.dp
            val targetWidth = if (filtersCollapsed) railWidth else panelWidth
            val currentWidth = remember { Animatable(targetWidth, Dp.VectorConverter) }
            // Re-laying out the list at its new width is one heavy frame; let it land before
            // the slide starts so the animation itself runs at full frame rate.
            LaunchedEffect(targetWidth) {
                withFrameNanos { }
                withFrameNanos { }
                currentWidth.animateTo(targetWidth, tween(250))
                listWide = filtersCollapsed
            }
            val listWidth = maxWidth - (if (listWide) railWidth else panelWidth) - dividerWidth
            // The list is anchored to the right edge and only uncovered / covered by the panel:
            // tags and sizes are right-aligned so they never move, and the name column is
            // revealed under the sliding edge. Clipping happens in the draw phase (cheap).
            val listStart = maxWidth - listWidth
            // Left-anchored content (names, "Name" header) starts where it was and slides to its
            // final place along with the panel edge; right-anchored tags and sizes never move.
            val density = LocalDensity.current
            val contentShift = { with(density) { (currentWidth.value + dividerWidth - listStart).roundToPx() } }
            val listClip = Modifier.drawWithContent {
                val left = (currentWidth.value + dividerWidth - listStart).toPx().coerceAtLeast(0f)
                clipRect(left = left) { this@drawWithContent.drawContent() }
            }
            // The single-line table needs room for name + tags + size; on 4:3 screens
            // (or with the panel open on narrow ones) rows stack name over tags instead.
            val tableRows = listWidth >= 560.dp

            Layout(
                modifier = Modifier.fillMaxSize().clipToBounds(),
                content = {
                    // Rail underneath, panel sliding over it. Both fade over the last stretch of
                    // the slide. ModulateAlpha applies the opacity per draw call instead of
                    // rendering the panel to an offscreen buffer (that GPU wait cost ~190 ms a
                    // frame on the K56); the lambdas run in the draw phase only. The hidden one
                    // refuses focus: onEnter is evaluated per focus query, no recomposition.
                    val fadeSpan = (panelWidth - railWidth) * 0.4f
                    val panelAlpha = { ((currentWidth.value - railWidth) / fadeSpan).coerceIn(0f, 1f) }
                    Box(
                        modifier = Modifier
                            .layoutId("rail")
                            .graphicsLayer { alpha = 1f - panelAlpha(); compositingStrategy = CompositingStrategy.ModulateAlpha }
                            .focusProperties { onEnter = { if (!filtersCollapsed) cancelFocusChange() } }
                            .onFocusChanged { railHasFocus = it.hasFocus }
                            .focusGroup()
                    ) {
                        FilterRail(count = activeFilterCount, onExpand = { expandFilters() })
                    }
                    Column(
                        modifier = Modifier
                            .layoutId("panel")
                            .graphicsLayer { alpha = panelAlpha(); compositingStrategy = CompositingStrategy.ModulateAlpha }
                            .focusProperties { onEnter = { if (filtersCollapsed) cancelFocusChange() } }
                            .focusGroup()
                    ) {
                    FilterPanel(
                        rows = filterRows,
                        onClear = viewModel::clearAllFilters,
                        compact = true,
                        firstRowFocus = filterFocus,
                        expandedRow = expandedFilter,
                        onExpandedRowChange = { expandedFilter = it },
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { filtersHaveFocus = it.hasFocus }
                            .focusGroup()
                    )
                    // Result count on the left, collapse control at the bottom-right corner (R3 does the same).
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            resultsLabel(results.size, hasMoreResults),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        PanelArrow(R.drawable.ic_arrow_left, stringResource(R.string.collapse_filters)) { collapseFilters() }
                    }
                }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.layoutId("divider"))
                    Box(modifier = Modifier.layoutId("list").then(listClip)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 12.dp, end = 12.dp, top = 12.dp)
                    ) {
                        SearchField(
                            value = query,
                            onValueChange = viewModel::setSearch,
                            active = searchActive,
                            onActivate = { searchActive = true },
                            onDismiss = { searchActive = false; runCatching { listFocus.requestFocus() } },
                            focusRequester = searchFocus,
                            contentShift = contentShift
                        )
                        if (tableRows) TableHeader(contentShift) else Text(
                            resultsLabel(results.size, hasMoreResults),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 6.dp)
                        )
                        ResultList(
                            results = results,
                            compact = tableRows,
                            hasMore = hasMoreResults,
                            isLoadingMore = isLoadingMore,
                            onLoadMore = { scope.launch { viewModel.loadMore() } },
                            getConsoleName = { ConsoleFormatter.getConsoleShortName(it) },
                            onFileClick = onFileClick,
                            isOwned = { viewModel.isOwned(it.file, ownedKeys) },
                            isFavourite = { viewModel.isFavourite(it.file, favouriteKeys) },
                            isDownloading = { viewModel.isDownloading(it.file, activeDownloads) },
                            onRowFocused = { focusedItem = it },
                            onRowLongClick = viewModel::openDetails,
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { listHasFocus = it.hasFocus }
                                .focusGroup(),
                            firstRowFocus = listFocus,
                            contentShift = contentShift
                        )
                    }
                    }
                }
            ) { measurables, constraints ->
                val height = constraints.maxHeight
                val widths = mapOf(
                    "panel" to panelWidth.roundToPx(), "rail" to railWidth.roundToPx(),
                    "divider" to dividerWidth.roundToPx(),
                    "list" to listWidth.roundToPx()
                )
                val placed = measurables.associate { val id = it.layoutId as String; id to it.measure(Constraints.fixed(widths.getValue(id), height)) }
                layout(constraints.maxWidth, height) {
                    val edge = currentWidth.value.roundToPx()
                    placed["list"]?.placeRelative(constraints.maxWidth - widths.getValue("list"), 0)
                    placed["rail"]?.placeRelative(0, 0)
                    placed["panel"]?.placeRelative(edge - widths.getValue("panel"), 0)
                    placed["divider"]?.placeRelative(edge, 0)
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
                    isFavourite = { viewModel.isFavourite(it.file, favouriteKeys) },
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
                        modifier = Modifier.swapFaceButtons().onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.ButtonR1, Key.ButtonThumbRight -> { showFilterSheet = false; true }
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
private fun TableHeader(contentShift: () -> Int = { 0 }) {
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
        Text(
            stringResource(R.string.column_name), style = style, color = color,
            modifier = Modifier.weight(1f).clipToBounds().offset { IntOffset(contentShift(), 0) }
        )
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
    isFavourite: (DownloadableFileWithTags) -> Boolean,
    isDownloading: (DownloadableFileWithTags) -> Boolean,
    onRowFocused: (DownloadableFileWithTags) -> Unit,
    onRowLongClick: (DownloadableFileWithTags) -> Unit,
    modifier: Modifier = Modifier,
    firstRowFocus: FocusRequester? = null,
    contentShift: () -> Int = { 0 }
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
                favourite = isFavourite(item),
                downloading = isDownloading(item),
                contentShift = contentShift,
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

/** Plain arrow (same tint and size as the filter dropdown arrows) that folds / unfolds the filter panel. */
@Composable
private fun PanelArrow(icon: Int, description: String, onClick: () -> Unit) {
    val source = rememberFocusSource()
    Icon(
        painterResource(icon),
        contentDescription = description,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .focusRing(source, 6.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(6.dp)
            .size(16.dp)
    )
}

/** Collapsed filter panel: a thin rail with the active-filter count and, at the bottom, the button that brings it back. */
@Composable
private fun FilterRail(count: Int, onExpand: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .requiredWidth(48.dp)
            .padding(top = 12.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (count > 0) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(scheme.primary)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        PanelArrow(R.drawable.ic_arrow_right, stringResource(R.string.expand_filters), onExpand)
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
