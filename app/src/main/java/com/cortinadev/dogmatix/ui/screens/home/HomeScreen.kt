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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.ui.focus.FocusEnterExitScope
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
import androidx.navigation.compose.currentBackStackEntryAsState
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.stripExtension
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.model.LibraryGame
import com.cortinadev.dogmatix.ui.screens.home.components.VersionState
import com.cortinadev.dogmatix.data.model.SortOption
import com.cortinadev.dogmatix.data.model.SourceFilter
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.GamepadButton
import com.cortinadev.dogmatix.ui.common.Legend
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.SidePanelWidth
import com.cortinadev.dogmatix.ui.screens.collection.collections.AddToCollectionDialog
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.swapFaceButtons
import com.cortinadev.dogmatix.ui.screens.home.components.ActiveFilter
import com.cortinadev.dogmatix.ui.screens.home.components.ActiveFiltersBar
import com.cortinadev.dogmatix.ui.screens.home.components.FilterOption
import com.cortinadev.dogmatix.ui.screens.home.components.activeFiltersOf
import com.cortinadev.dogmatix.ui.screens.home.components.FilterPanel
import com.cortinadev.dogmatix.ui.screens.home.components.FilterRowSpec
import com.cortinadev.dogmatix.ui.screens.home.components.SortButton
import com.cortinadev.dogmatix.ui.screens.home.components.GameDetailsDialog
import com.cortinadev.dogmatix.ui.screens.home.components.CoverGap
import com.cortinadev.dogmatix.ui.screens.home.components.RomRow
import com.cortinadev.dogmatix.ui.screens.home.components.RommGameSection
import com.cortinadev.dogmatix.ui.screens.home.components.TableCoverSize
import com.cortinadev.dogmatix.ui.screens.home.components.SearchField
import com.cortinadev.dogmatix.ui.screens.home.components.Recommendations
import com.cortinadev.dogmatix.ui.screens.home.components.rememberListLetterJump
import com.cortinadev.dogmatix.ui.screens.home.components.genreDecadeRows
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.util.LetterJump
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import com.cortinadev.dogmatix.util.ConsoleFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
// busqueda
import androidx.compose.foundation.layout.heightIn
import com.cortinadev.dogmatix.data.search.SearchEntry
import com.cortinadev.dogmatix.data.search.SearchGroup
import com.cortinadev.dogmatix.data.search.SearchGroupHits
import com.cortinadev.dogmatix.ui.screens.home.components.AlsoInLine
import com.cortinadev.dogmatix.ui.screens.home.components.GroupJump
import com.cortinadev.dogmatix.ui.screens.home.components.RecentSearchesPanel
import com.cortinadev.dogmatix.ui.screens.home.components.globalSearchGroupOffset
import com.cortinadev.dogmatix.ui.screens.home.components.globalSearchGroups
import com.cortinadev.dogmatix.ui.screens.home.components.noGamesItem
import com.cortinadev.dogmatix.util.RecentSearches
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val FAV_ALL = "all"
private const val FAV_ONLY = "only"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val results by viewModel.results.collectAsState()
    val games by viewModel.games.collectAsState()
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
    val showListCovers by viewModel.showListCovers.collectAsState()
    val similar by viewModel.similar.collectAsState()
    // busqueda
    val recentSearches by viewModel.search.recent.collectAsState()
    val globalResults by viewModel.search.results.collectAsState()
    val genres by viewModel.genres.collectAsState()
    val decades by viewModel.decades.collectAsState()
    val traitsLoading by viewModel.traitsLoading.collectAsState()
    val showRecommendations by viewModel.showRecommendations.collectAsState()
    val recommendations by viewModel.recommendations.collectAsState()
    val listForced by viewModel.listForced.collectAsState()
    val letterJumpEnabled by viewModel.letterJumpEnabled.collectAsState()
    // coleccion-gestion
    val collection by viewModel.collection.collectAsState()
    var addToCollection by remember { mutableStateOf<DownloadableFileWithTags?>(null) }

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

    // busqueda: recent searches under the open, empty box; matches in other sections under the games.
    val recentsFocus = remember { FocusRequester() }
    var recentsFocused by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    val showRecents = searchActive && query.isEmpty() && recentSearches.isNotEmpty()
    val inRecents = showRecents && recentsFocused
    LaunchedEffect(showRecents) { if (!showRecents) recentsFocused = false }
    val globalGroups = if (query.isBlank()) emptyList() else globalResults.groups
    var groupJump by remember { mutableStateOf<GroupJump?>(null) }
    var globalFocused by remember { mutableStateOf(false) }
    var alsoInFocused by remember { mutableStateOf(false) }
    var sortFocused by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    val sortFocus = remember { FocusRequester() }
    // Set by ▶ in the search box so that closing it lands on the sort button.
    var searchToSort by remember { mutableStateOf(false) }
    val searchToSortButton = {
        searchToSort = true
        runCatching { sortFocus.requestFocus() }.isSuccess
    }
    // Matches under the cursor (the legend changes there). Moving between two of them must not
    // recompose this heavy screen, so a lost focus only counts if no other match took it a frame later.
    val globalFocusCount = remember { intArrayOf(0) }
    LaunchedEffect(globalGroups.isEmpty()) {
        if (globalGroups.isEmpty()) { globalFocusCount[0] = 0; globalFocused = false; alsoInFocused = false }
    }
    val onGlobalFocusChange: (Boolean) -> Unit = { focused ->
        globalFocusCount[0] = (globalFocusCount[0] + if (focused) 1 else -1).coerceAtLeast(0)
        if (focused) {
            globalFocused = true
            // X and SELECT act on the game row under the cursor: none while on another section's match.
            focusedItem = null
        } else scope.launch {
            withFrameNanos { }
            if (globalFocusCount[0] == 0) globalFocused = false
        }
    }
    val confirmSearch = { viewModel.search.confirm(viewModel.searchQuery.value) { viewModel.results.value.isNotEmpty() } }
    // ▼ into the recent searches keeps the search open; closing the keyboard any other way confirms it.
    val dismissSearch = {
        if (!recentsFocused) {
            searchActive = false
            // ▶ from the box went to the sort button: leave the focus there, not on the list.
            runCatching { (if (searchToSort) sortFocus else listFocus).requestFocus() }
            searchToSort = false
            confirmSearch()
        }
    }
    // The panel that had the focus goes away with the new results: land on them once they are in.
    var focusAfterApply by remember { mutableStateOf<Pair<String, List<DownloadableFileWithTags>>?>(null) }
    LaunchedEffect(focusAfterApply) {
        val (applied, before) = focusAfterApply ?: return@LaunchedEffect
        withTimeoutOrNull(2_000) {
            viewModel.results.first { it !== before }
            viewModel.search.results.first { it.query == RecentSearches.clean(applied) }
        }
        withFrameNanos { }
        withFrameNanos { }
        if (!runCatching { listFocus.requestFocus() }.getOrDefault(false)) runCatching { Gamepad.sectionFocus.requestFocus() }
        focusAfterApply = null
    }
    val applyRecent: (String) -> Unit = { recent ->
        focusAfterApply = recent to viewModel.results.value
        searchActive = false
        viewModel.setSearch(recent)
        viewModel.search.record(recent)
    }
    val openGlobal: (SearchEntry) -> Unit = { viewModel.search.open(it, viewModel.searchQuery.value) }
    val recentsPanel: @Composable (Modifier) -> Unit = { modifier ->
        RecentSearchesPanel(
            recents = recentSearches,
            onApply = applyRecent,
            onRemove = viewModel.search::forget,
            onClear = viewModel.search::clearRecent,
            firstFocus = recentsFocus,
            fieldFocus = searchFocus,
            onFocusChange = { has ->
                val had = recentsFocused
                recentsFocused = has
                // LB / RB or a tap took the focus elsewhere: close the search (▲ back to the box keeps it).
                // Only a focus the panel had and lost: onFocusChanged also reports "unfocused" when
                // the panel appears, and that must not close the search Y is just opening.
                if (had && !has) scope.launch {
                    withFrameNanos { }
                    if (!recentsFocused && !fieldFocused) searchActive = false
                }
            },
            modifier = modifier.heightIn(max = 320.dp)
        )
    }
    val alsoInLine: @Composable (Modifier) -> Unit = { modifier ->
        AlsoInLine(
            groups = globalGroups,
            onJump = { groupJump = GroupJump(it) },
            modifier = modifier.onFocusChanged { alsoInFocused = it.hasFocus }
        )
    }
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

    // Sorting by console only makes sense with more than one in play (the console filter's, or the library's).
    val consoleSortAvailable = (selectedConsoles.ifEmpty { consolesWithFiles.map { it.id }.toSet() }).size > 1
    LaunchedEffect(consoleSortAvailable) {
        if (!consoleSortAvailable && sort == SortOption.CONSOLE) viewModel.setSort(SortOption.NAME_ASC)
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
    ) + genreDecadeRows(genres, decades, viewModel::setGenres, viewModel::setDecades) + listOf(
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
        )
    )
    val activeFilterCount = selectedConsoles.size + activeTags.size + (if (favouritesOnly) 1 else 0) + (if (sourceFilter != SourceFilter.ALL) 1 else 0) +
        (if (similar != null) 1 else 0) + genres.size + decades.size + (if (collection != null) 1 else 0)
    // The filters in force, as chips under the search box. Portrait leaves the console out: its own
    // chips are on screen already (it is the first row).
    val activeFilters = buildList {
        similar?.let { add(ActiveFilter("similar", stringResource(R.string.similar_to, it.title), loading = it.loading, onRemove = viewModel::clearSimilar)) }
        collection?.let { add(ActiveFilter("collection", stringResource(R.string.collections_filter, it.name), onRemove = viewModel::clearCollection)) }
        addAll(activeFiltersOf(if (isLandscape) filterRows else filterRows.drop(1)))
    }
    val barFocus = remember { FocusRequester() }
    var barFocused by remember { mutableStateOf(false) }
    LaunchedEffect(activeFilters.isEmpty()) { if (activeFilters.isEmpty()) barFocused = false }
    // ▲ off the top of the list or the shelves (landscape): the tabs line up better with the left-hand
    // cards, so the focus search would skip the sort pill; send it there unless "also in" or the
    // chips of the filters in force sit between (the search finds those on its own).
    val upToSort: FocusEnterExitScope.() -> Unit = {
        if (requestedFocusDirection == FocusDirection.Up && globalGroups.isEmpty() && activeFilters.isEmpty()) {
            cancelFocusChange()
            runCatching { sortFocus.requestFocus() }
        }
    }

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
            viewModel.isGameDownloading(item, activeDownloads) -> showMessage(alreadyDownloadingMessage.format(item.file.name))
            viewModel.isGameOwned(item, ownedKeys) -> ownedDialogItem = item
            else -> download(item)
        }
    }

    ownedDialogItem?.let { item ->
        AlertDialog(
            // B closes it; the focus starts on "Download again", never on "Delete" (the dialog
            // opens with A, and a second A must not wipe the game).
            modifier = Modifier.closeOnGamepadB { ownedDialogItem = null },
            onDismissRequest = { ownedDialogItem = null },
            title = { Text(stripExtension(item.file.name)) },
            text = { Text(stringResource(R.string.owned_dialog_message)) },
            confirmButton = {
                DialogButton(stringResource(R.string.owned_download_again), onClick = {
                    ownedDialogItem = null
                    download(item)
                }, initialFocus = rememberInitialFocus())
            },
            dismissButton = {
                DialogButton(stringResource(R.string.owned_delete), onClick = {
                    ownedDialogItem = null
                    scope.launch {
                        val ok = viewModel.deleteOwned(item)
                        showMessage((if (ok) deletedMessage else deleteFailedMessage).format(item.file.name))
                    }
                })
            }
        )
    }

    detailsState?.let { state ->
        GameDetailsDialog(
            state = state,
            consoleName = ConsoleFormatter.getConsoleShortName(state.item.file.consoleId),
            favourite = viewModel.isGameFavourite(state.item, favouriteKeys),
            onToggleFavourite = { toggleFavourite(state.item) },
            onDownload = { viewModel.closeDetails(); onFileClick(state.item) },
            onDismiss = viewModel::closeDetails,
            onShowSimilar = { viewModel.showSimilar(state.item, state.details) },
            versionState = { version ->
                when {
                    viewModel.isVersionDownloading(version, activeDownloads) -> VersionState.DOWNLOADING
                    viewModel.isVersionOwned(version, ownedKeys) -> VersionState.OWNED
                    else -> VersionState.NONE
                }
            },
            onDownloadVersion = { version ->
                scope.launch { viewModel.downloadVersion(version, context) }
                showMessage(startedMessage.format(version.primary.file.name))
            },
            // romm-catalogo: the game on the RomM server (nothing when it is not there); it
            // shares the room left with the top block so the buttons always fit.
            extra = { RommGameSection(state, Modifier.weight(1f, fill = false)) },
            onAddToCollection = { addToCollection = state.item }
        )
    }
    addToCollection?.let { item ->
        AddToCollectionDialog(
            consoleId = item.file.consoleId,
            groupKey = item.file.groupKey,
            gameName = stripExtension(item.file.name),
            onDismiss = { addToCollection = null }
        )
    }

    // The similar list replaces the rows the details card was opened from: land on its first one.
    val similarReady = similar?.takeIf { !it.loading }?.fileId
    LaunchedEffect(similarReady, results.isNotEmpty()) {
        if (similarReady == null || results.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        runCatching { listFocus.requestFocus() }
    }
    val emptyText = when {
        similar?.loading == true -> stringResource(R.string.similar_searching)
        (genres.isNotEmpty() || decades.isNotEmpty()) && traitsLoading -> stringResource(R.string.traits_loading)
        else -> null
    }

    // Leaving the list for the recommendations, or the other way, takes the D-pad focus along:
    // once the new view is on screen, its first card / row gets it.
    var refocus by remember { mutableStateOf<Refocus?>(null) }
    var refocusSerial by remember { mutableIntStateOf(0) }
    LaunchedEffect(refocus) {
        val target = refocus ?: return@LaunchedEffect
        // An undone layer may leave the list on screen (the list was opened by a deep link or a
        // collection): its new rows arriving count too, and its first row then takes the focus.
        val before = results
        withTimeoutOrNull(1_000) {
            snapshotFlow { showRecommendations == target.toRecommendations || results !== before }.first { it }
        }
        // Shelves still being worked out: wait a little for them rather than land on "All games".
        if (showRecommendations) withTimeoutOrNull(3_000) { snapshotFlow { recommendations.ready }.first { it } }
        for (attempt in 0 until 10) {
            withFrameNanos { }
            if (runCatching { listFocus.requestFocus() }.isSuccess) break
        }
    }
    val activeFiltersBar: @Composable (Modifier) -> Unit = { modifier ->
        ActiveFiltersBar(
            filters = activeFilters,
            onClearAll = viewModel::clearAllFilters,
            entryFocus = barFocus,
            upFocus = sortFocus,
            // The last chip gone with the focus on it: the list (or the shelves) takes it.
            onEmptied = { if (barFocused) refocus = Refocus(toRecommendations = true, serial = ++refocusSerial) },
            modifier = modifier.onFocusChanged { barFocused = it.hasFocus }
        )
    }
    val showAllGames = {
        viewModel.showAllGames()
        refocus = Refocus(toRecommendations = false, serial = ++refocusSerial)
    }
    val openSources = {
        navController.navigate(NavRoutes.Sources.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // First letter of every loaded row, for ◀ ▶ letter jumps in the A→Z / Z→A list.
    val rowLetters = remember(results, games, letterJumpEnabled) {
        if (!letterJumpEnabled) null else results.map { LetterJump.letterOf(listName(it, games)) }
    }

    // coleccion-gestion: opening a collection (Collection tab) lands on its first game, once.
    val landOnCollection by viewModel.landOnCollection.collectAsState()
    LaunchedEffect(landOnCollection) {
        if (!landOnCollection) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        runCatching { listFocus.requestFocus() }
        viewModel.landedOnCollection()
    }

    // Button legend follows where the focus is. Kept short: B (back) and LB / RB are left out
    // of the list and filter legends so they fit on one line even on 4:3 screens.
    val filtersKey = if (isLandscape) listOf(LegendEntry("R3", stringResource(R.string.pad_filters))) else emptyList()
    val section = LegendEntry("ZL · ZR", stringResource(R.string.pad_section))
    val legendCards = listOf(
        LegendEntry("A", stringResource(R.string.pad_download)), LegendEntry("X", stringResource(R.string.pad_details)),
        LegendEntry("Y", stringResource(R.string.pad_search)), LegendEntry("SELECT", stringResource(R.string.pad_favourite))
    ) + filtersKey + section
    // ◀ ▶ jump letter by letter in the A→Z / Z→A list.
    val letterKey = if (rowLetters != null) listOf(LegendEntry("◀ ▶", stringResource(R.string.pad_letter))) else emptyList()
    val legendList = legendCards.take(4) + letterKey + filtersKey + section
    val legendFilters = listOf(
        LegendEntry("A", stringResource(R.string.pad_options)), LegendEntry("◀ ▶", stringResource(R.string.pad_change))
    ) + filtersKey + section
    val legendDetails = listOf(
        LegendEntry("A", stringResource(R.string.pad_select)), LegendEntry("B", stringResource(R.string.pad_close)),
        LegendEntry("SELECT", stringResource(R.string.pad_favourite)), LegendEntry("▲ ▼", stringResource(R.string.pad_scroll))
    ) + if ((detailsState?.versions?.size ?: 0) > 1) {
        // Versions box ⇄ buttons, like filters ⇄ list.
        listOf(LegendEntry("LB · RB", stringResource(R.string.pad_panels)))
    } else emptyList()
    val legendSearch = listOf(
        LegendEntry("A", stringResource(R.string.pad_keyboard)), LegendEntry("B", stringResource(R.string.pad_close_keyboard))
    ) + (if (showRecents) listOf(LegendEntry("▼", stringResource(R.string.pad_recent))) else emptyList()) +
        LegendEntry("▶", stringResource(R.string.pad_sort))
    val legendRecents = listOf(
        LegendEntry("A", stringResource(R.string.pad_apply)), LegendEntry("X", stringResource(R.string.pad_delete)),
        LegendEntry("B", stringResource(R.string.pad_close))
    )
    val legendSort = listOf(
        LegendEntry("A", stringResource(R.string.pad_sort)), LegendEntry("◀", stringResource(R.string.pad_search)), section
    )
    val legendSortMenu = listOf(
        LegendEntry("A", stringResource(R.string.pad_select)), LegendEntry("B", stringResource(R.string.pad_close))
    )
    val legendBar = listOf(LegendEntry("A", stringResource(R.string.pad_remove)), section)
    val legendGlobal = listOf(
        LegendEntry("A", stringResource(R.string.pad_open)), LegendEntry("Y", stringResource(R.string.pad_search))
    ) + section
    val legend = when {
        detailsState != null -> legendDetails
        sortMenuOpen -> legendSortMenu
        inRecents -> legendRecents
        searchActive -> legendSearch
        filtersHaveFocus || showFilterSheet -> legendFilters
        barFocused -> legendBar
        globalFocused || alsoInFocused -> legendGlobal
        sortFocused -> legendSort
        showRecommendations -> legendCards
        else -> legendList
    }
    // Only while this is the current section: leaving it (a search result in another section) the
    // focus goes and the legend changes while this screen is still composed, which would replace
    // the new section's legend, and the dispose that follows would leave none.
    val onThisTab = navController.currentBackStackEntryAsState().value?.destination?.route == NavRoutes.Home.route
    val published = remember(legend, onThisTab) { if (onThisTab) Legend(legend) else null }
    LaunchedEffect(published) { if (published != null) Gamepad.legendOverride.value = published }
    // Only clear our own legend: another screen may already have published its own during the transition.
    DisposableEffect(published) { onDispose { if (published != null && Gamepad.legendOverride.value === published) Gamepad.legendOverride.value = null } }

    // B / Back undoes one layer at a time and never leaves the app from here (Home is the root).
    BackHandler {
        // Undoing the last filter brings the recommendations back; the focus follows from the list.
        fun undo(action: () -> Unit) {
            if (listHasFocus) refocus = Refocus(toRecommendations = true, serial = ++refocusSerial)
            action()
        }
        when {
            showFilterSheet -> showFilterSheet = false
            expandedFilter != null -> expandedFilter = null
            inRecents -> { searchActive = false; runCatching { listFocus.requestFocus() } }
            searchActive -> { focusManager.clearFocus(); searchActive = false }
            similar != null -> undo(viewModel::clearSimilar)
            collection != null -> undo(viewModel::clearCollection)
            query.isNotEmpty() -> undo { viewModel.setSearch("") }
            activeFilterCount > 0 || sort != SortOption.NAME_ASC || listForced -> undo(viewModel::clearAllFilters)
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
                // From the recent searches Y goes back up to the box.
                GamepadButton.Y -> if (recentsFocused) runCatching { searchFocus.requestFocus() } else searchActive = true
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
            val panelWidth = SidePanelWidth
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SearchField(
                                value = query,
                                onValueChange = viewModel::setSearch,
                                active = searchActive,
                                onActivate = { searchActive = true },
                                onDismiss = dismissSearch,
                                focusRequester = searchFocus,
                                contentShift = contentShift,
                                onSubmit = confirmSearch,
                                onDirectionDown = { showRecents && runCatching { recentsFocus.requestFocus() }.getOrDefault(false) },
                                onDirectionRight = searchToSortButton,
                                modifier = Modifier.weight(1f).onFocusChanged { fieldFocused = it.hasFocus }
                            )
                            SortButton(
                                sort, viewModel::setSort, onLeft = { searchActive = true }, consoleSort = consoleSortAvailable,
                                modifier = Modifier.onFocusChanged { sortFocused = it.hasFocus },
                                focusRequester = sortFocus, onMenuChange = { sortMenuOpen = it }
                            )
                        }
                        if (showRecents) recentsPanel(Modifier)
                        if (activeFilters.isNotEmpty()) activeFiltersBar(Modifier.offset { IntOffset(contentShift(), 0) })
                        if (globalGroups.isNotEmpty()) alsoInLine(Modifier)
                        if (showRecommendations) Recommendations(
                            state = recommendations,
                            consoleName = { ConsoleFormatter.getConsoleShortName(it) },
                            isFavourite = { viewModel.isGameFavourite(it, favouriteKeys) },
                            isDownloading = { viewModel.isGameDownloading(it, activeDownloads) },
                            onClick = onFileClick,
                            onLongClick = viewModel::openDetails,
                            onCardFocused = { focusedItem = it },
                            onShowAll = showAllGames,
                            onOpenSources = openSources,
                            firstFocus = listFocus,
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { listHasFocus = it.hasFocus }
                                .focusProperties { onExit = upToSort }
                                .focusGroup(),
                            contentShift = contentShift
                        ) else {
                        if (tableRows) TableHeader(contentShift, nameInset = if (showListCovers) TableCoverSize + CoverGap else 0.dp) else ResultsLabel(
                            resultsLabel(results.size, hasMoreResults),
                            modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 6.dp)
                        )
                        ResultList(
                            results = results,
                            compact = tableRows,
                            showCovers = showListCovers,
                            emptyText = emptyText,
                            hasMore = hasMoreResults,
                            isLoadingMore = isLoadingMore,
                            onLoadMore = { scope.launch { viewModel.loadMore() } },
                            getConsoleName = { ConsoleFormatter.getConsoleShortName(it) },
                            onFileClick = onFileClick,
                            isOwned = { viewModel.isGameOwned(it, ownedKeys) },
                            isFavourite = { viewModel.isGameFavourite(it, favouriteKeys) },
                            isDownloading = { viewModel.isGameDownloading(it, activeDownloads) },
                            gameOf = { games[it.file.id] },
                            onRowFocused = { focusedItem = it },
                            onRowLongClick = viewModel::openDetails,
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { listHasFocus = it.hasFocus }
                                .focusProperties { onExit = upToSort }
                                .focusGroup(),
                            firstRowFocus = listFocus,
                            contentShift = contentShift,
                            globalGroups = globalGroups,
                            onOpenGlobal = openGlobal,
                            onGlobalFocusChange = onGlobalFocusChange,
                            groupJump = groupJump,
                            onGroupJumpDone = { groupJump = null },
                            rowLetters = rowLetters,
                            nextLetterBeyond = viewModel::nextLetterStart
                        )
                        }
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
                        onDismiss = dismissSearch,
                        focusRequester = searchFocus,
                        modifier = Modifier.weight(1f).onFocusChanged { fieldFocused = it.hasFocus },
                        onSubmit = confirmSearch,
                        onDirectionDown = { showRecents && runCatching { recentsFocus.requestFocus() }.getOrDefault(false) },
                        onDirectionRight = searchToSortButton
                    )
                    SortButton(
                        sort, viewModel::setSort, onLeft = { searchActive = true }, consoleSort = consoleSortAvailable,
                        modifier = Modifier.onFocusChanged { sortFocused = it.hasFocus },
                        focusRequester = sortFocus, onMenuChange = { sortMenuOpen = it }
                    )
                    FilterButton(count = activeFilterCount) { showFilterSheet = true }
                }
                if (showRecents) recentsPanel(Modifier.padding(horizontal = 16.dp))
                if (activeFilters.isNotEmpty()) activeFiltersBar(Modifier.padding(horizontal = 16.dp))
                ConsoleChips(
                    options = consoleOptions,
                    selected = selectedConsoles,
                    onSelect = viewModel::setConsoleSelection,
                    modifier = Modifier.padding(vertical = 10.dp)
                )
                if (globalGroups.isNotEmpty()) alsoInLine(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp))
                if (showRecommendations) Recommendations(
                    state = recommendations,
                    consoleName = { ConsoleFormatter.getConsoleShortName(it) },
                    isFavourite = { viewModel.isGameFavourite(it, favouriteKeys) },
                    isDownloading = { viewModel.isGameDownloading(it, activeDownloads) },
                    onClick = onFileClick,
                    onLongClick = viewModel::openDetails,
                    onCardFocused = { focusedItem = it },
                    onShowAll = showAllGames,
                    onOpenSources = openSources,
                    firstFocus = listFocus,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                        .onFocusChanged { listHasFocus = it.hasFocus }
                ) else {
                ResultsLabel(
                    resultsLabel(results.size, hasMoreResults),
                    modifier = Modifier.padding(start = 30.dp, end = 16.dp, bottom = 6.dp)
                )
                ResultList(
                    results = results,
                    compact = false,
                    showCovers = showListCovers,
                    emptyText = emptyText,
                    hasMore = hasMoreResults,
                    isLoadingMore = isLoadingMore,
                    onLoadMore = { scope.launch { viewModel.loadMore() } },
                    getConsoleName = { ConsoleFormatter.getConsoleShortName(it) },
                    onFileClick = onFileClick,
                    isOwned = { viewModel.isGameOwned(it, ownedKeys) },
                    isFavourite = { viewModel.isGameFavourite(it, favouriteKeys) },
                    isDownloading = { viewModel.isGameDownloading(it, activeDownloads) },
                    gameOf = { games[it.file.id] },
                    onRowFocused = { focusedItem = it },
                    onRowLongClick = viewModel::openDetails,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                        .onFocusChanged { listHasFocus = it.hasFocus },
                    firstRowFocus = listFocus,
                    globalGroups = globalGroups,
                    onOpenGlobal = openGlobal,
                    onGlobalFocusChange = onGlobalFocusChange,
                    groupJump = groupJump,
                    onGroupJumpDone = { groupJump = null },
                    rowLetters = rowLetters,
                    nextLetterBeyond = viewModel::nextLetterStart
                )
                }
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
                            // ZL / ZR (down and up) go through the shared trigger state, so the axis of an
                            // analog pull that closes the sheet does not count it again in the Activity.
                            if (event.key == Key.ButtonL2 || event.key == Key.ButtonR2) {
                                if (event.type == KeyEventType.KeyDown) showFilterSheet = false
                                return@onPreviewKeyEvent Gamepad.interceptKey(event.nativeKeyEvent)
                            }
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.ButtonR1, Key.ButtonThumbRight -> { showFilterSheet = false; true }
                                Key.ButtonY -> { showFilterSheet = false; searchActive = true; true }
                                else -> false
                            }
                        }
                    ) {
                    FilterPanel(
                        rows = filterRows,
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

/** A request to move the focus into the recommendations ([toRecommendations]) or the list once it is shown. */
private data class Refocus(val toRecommendations: Boolean, val serial: Int)

/** The name a game sorts by in the list (the DAO's `MIN(name)` over its files), for letter jumps. */
private fun listName(item: DownloadableFileWithTags, games: Map<Long, LibraryGame>): String =
    games[item.file.id]?.files?.minOfOrNull { it.file.name } ?: item.file.name

/** "N results" above the stacked list. */
@Composable
private fun ResultsLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun TableHeader(contentShift: () -> Int = { 0 }, nameInset: Dp = 0.dp) {
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
            modifier = Modifier.weight(1f).clipToBounds().offset { IntOffset(contentShift(), 0) }.padding(start = nameInset)
        )
        Text(stringResource(R.string.column_tags), style = style, color = color, modifier = Modifier.width(300.dp))
        Text(stringResource(R.string.column_size), style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
    }
}

@Composable
private fun ResultList(
    results: List<DownloadableFileWithTags>,
    compact: Boolean,
    showCovers: Boolean,
    /** Shown instead of "no results" (e.g. while the similar games are being looked for). */
    emptyText: String?,
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
    /** The game behind a row: its versions and discs (see HomeViewModel.games). */
    gameOf: (DownloadableFileWithTags) -> LibraryGame? = { null },
    firstRowFocus: FocusRequester? = null,
    contentShift: () -> Int = { 0 },
    // busqueda: matches in other sections, listed under the games while the search has text.
    globalGroups: List<SearchGroupHits> = emptyList(),
    onOpenGlobal: (SearchEntry) -> Unit = {},
    onGlobalFocusChange: (Boolean) -> Unit = {},
    groupJump: GroupJump? = null,
    onGroupJumpDone: () -> Unit = {},
    /** First letter of each row (see LetterJump); null when the order is not by name (◀ ▶ then move the focus as usual). */
    rowLetters: List<Char>? = null,
    /** Where the next letter starts when it is past the loaded rows (loads up to it); null at the last letter. */
    nextLetterBeyond: suspend (Int) -> Int? = { null }
) {
    if (results.isEmpty() && globalGroups.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                emptyText ?: stringResource(R.string.no_results),
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
    // "Also in": scroll to the group and land on its first entry.
    val groupFocus = remember { mutableMapOf<SearchGroup, FocusRequester>() }
    val groupFocusFor: (SearchGroup) -> FocusRequester = { groupFocus.getOrPut(it) { FocusRequester() } }
    LaunchedEffect(groupJump) {
        val jump = groupJump ?: return@LaunchedEffect
        globalSearchGroupOffset(globalGroups, jump.group)?.let { offset ->
            val start = results.size + (if (hasMore) 1 else 0) + (if (results.isEmpty()) 1 else 0)
            listState.scrollToItem(start + offset)
            withFrameNanos { }
            runCatching { groupFocusFor(jump.group).requestFocus() }
        }
        onGroupJumpDone()
    }
    val letterJump = rememberListLetterJump(listState, results.size, rowLetters, hasMore, nextLetterBeyond)
    LazyColumn(
        state = listState,
        modifier = modifier.then(letterJump.modifier),
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
                showCover = showCovers,
                game = gameOf(item),
                contentShift = contentShift,
                // RB from the filters lands on the first row currently on screen.
                modifier = when {
                    index == pendingFocusIndex -> Modifier.focusRequester(newPageFocus)
                    index == letterJump.focusIndex -> Modifier.focusRequester(letterJump.focus)
                    index == focusIndex && firstRowFocus != null -> Modifier.focusRequester(firstRowFocus)
                    else -> Modifier
                }.onFocusChanged {
                    if (it.isFocused) {
                        letterJump.onRowFocused(index)
                        onRowFocused(item)
                    }
                }
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
        if (globalGroups.isNotEmpty()) {
            if (results.isEmpty()) noGamesItem()
            globalSearchGroups(
                groups = globalGroups,
                groupFocus = groupFocusFor,
                // No games: the first match is where B from the box and RB from the filters land.
                firstEntryFocus = firstRowFocus.takeIf { results.isEmpty() },
                onOpen = onOpenGlobal,
                // ◀ ▶ jump letters on the game rows only; on these they move the focus as usual.
                onFocusChange = { focused -> letterJump.paused = focused; onGlobalFocusChange(focused) },
                contentShift = contentShift
            )
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
            .focusRing(source, 10.dp, width = if (active) 2.dp else 1.5.dp, ringColor = if (active) scheme.onSurface else null)
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
