package com.cortinadev.dogmatix.ui.screens.collection

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.GamepadButton
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.SidePanelWidth
import com.cortinadev.dogmatix.ui.components.SubTab
import com.cortinadev.dogmatix.ui.components.SubTabs
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.data.search.COLLECTION_SEARCH_PREFIX
import com.cortinadev.dogmatix.data.search.SearchTarget
import com.cortinadev.dogmatix.data.search.searchKey
import com.cortinadev.dogmatix.ui.navigation.NavRoutes
import com.cortinadev.dogmatix.ui.navigation.SearchTargetEffect
import com.cortinadev.dogmatix.ui.screens.collection.collections.CollectionsViewModel
import com.cortinadev.dogmatix.ui.screens.collection.components.CollectionButton
import com.cortinadev.dogmatix.ui.screens.collection.components.DuplicatesPane
import com.cortinadev.dogmatix.ui.screens.collection.components.FreeSpacePane
import com.cortinadev.dogmatix.ui.screens.collection.components.HealthPane
import com.cortinadev.dogmatix.ui.screens.collection.components.PublishLegend
import com.cortinadev.dogmatix.ui.screens.collection.components.RepairPane
import com.cortinadev.dogmatix.ui.screens.collection.components.SummaryPane
import com.cortinadev.dogmatix.ui.screens.collection.components.agoLabel
import com.cortinadev.dogmatix.ui.screens.collection.components.analyzeLegendEntry
import com.cortinadev.dogmatix.ui.screens.collection.collections.CollectionsPane
import com.cortinadev.dogmatix.ui.screens.collection.history.ActionHistoryPane
import com.cortinadev.dogmatix.ui.screens.collection.librarymove.LibraryMovePane
import com.cortinadev.dogmatix.ui.screens.collection.smartstorage.SmartStoragePane
import com.cortinadev.dogmatix.ui.screens.collection.components.scanningLabel
import com.cortinadev.dogmatix.util.HealthSeverity
import com.cortinadev.dogmatix.util.StorageProblem
import kotlinx.coroutines.launch

/** The tools of the Collection section, in the order they are listed. */
enum class CollectionTool {
    SUMMARY, HEALTH, DUPLICATES, REPAIR, FREE_SPACE, SMART_STORAGE, LIBRARY_MOVE, HISTORY, COLLECTIONS;

    companion object {
        /** The tool saved in `collection_tool`; one that no longer exists (the old statistics tool) opens Summary. */
        fun fromSaved(name: String?): CollectionTool = entries.firstOrNull { it.name == name } ?: SUMMARY
    }
}

/** Title of each tool (`collection_tool_<id>`, also what the app-wide search indexes). */
@get:StringRes
val CollectionTool.titleRes: Int
    get() = when (this) {
        CollectionTool.SUMMARY -> R.string.collection_tool_summary
        CollectionTool.HEALTH -> R.string.collection_tool_health
        CollectionTool.DUPLICATES -> R.string.collection_tool_duplicates
        CollectionTool.REPAIR -> R.string.collection_tool_repair
        CollectionTool.FREE_SPACE -> R.string.collection_tool_free_space
        CollectionTool.SMART_STORAGE -> R.string.collection_tool_smart_storage
        CollectionTool.LIBRARY_MOVE -> R.string.collection_tool_library_move
        CollectionTool.HISTORY -> R.string.collection_tool_history
        CollectionTool.COLLECTIONS -> R.string.collection_tool_collections
    }

/** Ways out of a tool: to another tool, or to another section. */
class CollectionNav(
    val openTool: (CollectionTool) -> Unit,
    val openSettings: () -> Unit,
    val openSources: () -> Unit
)

/**
 * The Collection section. Landscape: the tool list on the left and the open tool on the right,
 * like the Library's filters and list (LB · RB move between them; the list remembers the last
 * tool). Portrait: the same with the tools as tabs fixed at the top.
 */
@Composable
fun CollectionScreen(
    navController: NavController,
    viewModel: CollectionViewModel = hiltViewModel()
) {
    val tool by viewModel.tool.collectAsState()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }

    val rowFocus = remember { CollectionTool.entries.associateWith { FocusRequester() } }
    // The open tool's first control; external panes manage their own focus and may not use it.
    val contentFocus = remember { FocusRequester() }
    var contentHasFocus by remember { mutableStateOf(false) }

    // Two frames: the target may only be laid out after the change that asked for it.
    val focusPanel: () -> Unit = {
        scope.launch {
            withFrameNanos { }
            withFrameNanos { }
            runCatching { rowFocus.getValue(viewModel.tool.value).requestFocus() }
        }
    }
    val focusContent: () -> Unit = {
        scope.launch {
            withFrameNanos { }
            withFrameNanos { }
            if (runCatching { contentFocus.requestFocus() }.isFailure) focusManager.moveFocus(FocusDirection.Right)
        }
    }
    // By touch nothing holds the focus: a ring jumping into the tool would only be noise.
    val openTool: (CollectionTool) -> Unit = { next ->
        viewModel.selectTool(next)
        if (Gamepad.connected.value) focusContent()
    }
    val nav = remember(navController, isLandscape) {
        CollectionNav(
            openTool = openTool,
            openSettings = { navController.switchTo(NavRoutes.Settings) },
            openSources = { navController.switchTo(NavRoutes.Sources) }
        )
    }

    // busqueda: a search result opens its tool (focus on it), or a collection in the Library.
    val collectionsViewModel: CollectionsViewModel = hiltViewModel()
    SearchTargetEffect<SearchTarget.Screen>(accept = { it.route == NavRoutes.Collection.route }) { target ->
        target.key.removePrefix(COLLECTION_SEARCH_PREFIX).takeIf { target.key.startsWith(COLLECTION_SEARCH_PREFIX) }?.let { id ->
            id.toLongOrNull()?.let { collectionsViewModel.open(it) }
            return@SearchTargetEffect
        }
        val wanted = CollectionTool.entries.firstOrNull { it.searchKey == target.key } ?: return@SearchTargetEffect
        // After the shell parks the focus on the section tab.
        repeat(3) { withFrameNanos { } }
        viewModel.selectTool(wanted)
        focusContent()
    }

    LaunchedEffect(Unit) {
        Gamepad.presses.collect { button ->
            when (button) {
                GamepadButton.PREV_PANEL -> focusPanel()
                GamepadButton.NEXT_PANEL -> focusContent()
                // X analyses again from the list and from the tools that have no X of their own.
                GamepadButton.X -> if (!contentHasFocus || viewModel.tool.value in AnalyzeOnX) viewModel.toggleAnalysis()
                else -> Unit
            }
        }
    }

    // B: tool → list (or tabs) → section tab; it never leaves the app from here.
    BackHandler {
        if (contentHasFocus) focusPanel() else runCatching { Gamepad.sectionFocus.requestFocus() }
    }

    val messages = collectionMessages()
    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(messages(message), duration = if (message.troubled) SnackbarDuration.Long else SnackbarDuration.Short)
        }
    }
    // A library folder gone since the last analysis stops being offered (Health shows it instead).
    LaunchedEffect(Unit) { viewModel.checkFolders() }

    // The tools publish their own legend while they hold the focus; the list's is this one.
    val listLegend = buildList {
        add(LegendEntry("A", stringResource(R.string.pad_open)))
        add(analyzeLegendEntry(viewModel))
        add(LegendEntry("LB · RB", stringResource(R.string.pad_panels)))
        add(LegendEntry("ZL · ZR", stringResource(R.string.pad_section)))
    }
    // Leaving for another tab (a tool opening the Library), the content loses the focus while this
    // screen is still composed: publishing then would replace the new screen's legend and the
    // dispose that follows would leave none at all.
    val onThisTab = navController.currentBackStackEntryAsState().value?.destination?.route == NavRoutes.Collection.route
    PublishLegend(listLegend, active = !contentHasFocus && onThisTab)

    val content: @Composable (Modifier) -> Unit = { modifier ->
        Box(
            modifier = modifier
                .onFocusChanged { contentHasFocus = it.hasFocus }
                .focusGroup()
        ) {
            ToolContent(tool, viewModel, active = contentHasFocus, entryFocus = contentFocus, nav = nav)
        }
    }

    // ◀ from the tool (or ▲, in portrait) comes back to its row or tab, never to a neighbour.
    val panelModifier = Modifier
        .focusProperties {
            onEnter = {
                if (requestedFocusDirection != FocusDirection.Enter) {
                    runCatching { rowFocus.getValue(viewModel.tool.value).requestFocus() }
                }
            }
        }
        .focusGroup()

    Box(
        modifier = Modifier
            .fillMaxSize()
            // From the section tabs the D-pad lands on the tool in use, not on whatever is nearest.
            // A request for a given element (FocusDirection.Enter: a search result, a pane taking
            // its focus back after a change) goes where it asked.
            .focusProperties {
                onEnter = {
                    if (requestedFocusDirection != FocusDirection.Enter) {
                        runCatching { rowFocus.getValue(viewModel.tool.value).requestFocus() }
                    }
                }
            }
            .focusGroup()
    ) {
        if (isLandscape) {
            Row(modifier = Modifier.fillMaxSize()) {
                ToolPanel(
                    active = tool,
                    viewModel = viewModel,
                    rowFocus = rowFocus,
                    onOpen = openTool,
                    modifier = Modifier
                        .width(SidePanelWidth)
                        .fillMaxHeight()
                        .then(panelModifier)
                )
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                content(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            // Portrait: the tools as tabs fixed at the top, the analysis status under them.
            val badgeOf = toolBadges(viewModel)
            Column(modifier = Modifier.fillMaxSize()) {
                SubTabs(modifier = panelModifier) {
                    CollectionTool.entries.forEach { t ->
                        val badge = badgeOf(t)
                        SubTab(
                            title = stringResource(t.titleRes),
                            selected = t == tool,
                            focus = rowFocus.getValue(t),
                            onFocused = { viewModel.selectTool(t) },
                            onClick = { openTool(t) },
                            badge = badge?.first,
                            badgeAlert = badge?.second == true
                        )
                    }
                }
                AnalyzeBar(viewModel)
                content(Modifier.weight(1f).fillMaxWidth())
            }
        }

        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
    }
}

/** The tools where X analyses again; the others use X for their own action on what is ticked. */
private val AnalyzeOnX = setOf(CollectionTool.SUMMARY, CollectionTool.HEALTH)

/**
 * The open tool. A single `when`. The four management panes take the entry requester on their
 * root (`Modifier.toolPane`), which hands the focus to their first useful element; their own
 * layers are dialogs that B closes first, then B comes back to the tool list.
 */
@Composable
private fun ToolContent(tool: CollectionTool, viewModel: CollectionViewModel, active: Boolean, entryFocus: FocusRequester, nav: CollectionNav) {
    val modifier = Modifier.fillMaxSize()
    when (tool) {
        CollectionTool.SUMMARY -> SummaryPane(viewModel, active, entryFocus, nav, modifier)
        CollectionTool.HEALTH -> HealthPane(viewModel, active, entryFocus, nav, modifier)
        CollectionTool.DUPLICATES -> DuplicatesPane(viewModel, active, entryFocus, nav, modifier)
        CollectionTool.REPAIR -> RepairPane(viewModel, active, entryFocus, nav, modifier)
        CollectionTool.FREE_SPACE -> FreeSpacePane(viewModel, active, entryFocus, nav, modifier)
        CollectionTool.SMART_STORAGE -> SmartStoragePane(modifier.focusRequester(entryFocus))
        CollectionTool.LIBRARY_MOVE -> LibraryMovePane(modifier.focusRequester(entryFocus))
        CollectionTool.HISTORY -> ActionHistoryPane(modifier.focusRequester(entryFocus))
        CollectionTool.COLLECTIONS -> CollectionsPane(modifier.focusRequester(entryFocus))
    }
}

/**
 * Landscape: the list of tools. A row becomes the open tool as soon as it takes the focus and A
 * moves into it (portrait shows the same as tabs, [SubTabs]). While the folders are being read,
 * Summary's figure gives way to a spinner, in the same place so the rows never move.
 */
@Composable
private fun ToolPanel(
    active: CollectionTool,
    viewModel: CollectionViewModel,
    rowFocus: Map<CollectionTool, FocusRequester>,
    onOpen: (CollectionTool) -> Unit,
    modifier: Modifier = Modifier
) {
    val badgeOf = toolBadges(viewModel)
    val progress by viewModel.progress.collectAsState()
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 12.dp)
    ) {
        CollectionTool.entries.forEach { tool ->
            val badge = badgeOf(tool)
            ToolRow(
                title = stringResource(tool.titleRes),
                badge = badge?.first,
                badgeAlert = badge?.second == true,
                busy = tool == CollectionTool.SUMMARY && progress != null,
                selected = tool == active,
                focus = rowFocus.getValue(tool),
                onFocused = { viewModel.selectTool(tool) },
                onClick = { onOpen(tool) }
            )
        }
    }
}

/** A figure per tool once there is an analysis: what waits inside (true = a serious finding). */
@Composable
private fun toolBadges(viewModel: CollectionViewModel): (CollectionTool) -> Pair<String, Boolean>? {
    val analysis by viewModel.analysis.collectAsState()
    val reclaim by viewModel.reclaim.collectAsState()
    return { tool ->
        analysis?.let { a ->
            when (tool) {
                CollectionTool.SUMMARY -> a.gameCount.toString() to false
                CollectionTool.HEALTH -> a.findings.size.takeIf { it > 0 }?.let { n ->
                    n.toString() to a.findings.any { it.severity == HealthSeverity.HIGH }
                }
                CollectionTool.DUPLICATES -> a.duplicates.size.takeIf { it > 0 }?.let { it.toString() to false }
                CollectionTool.REPAIR -> a.repairs.size.takeIf { it > 0 }?.let { it.toString() to false }
                CollectionTool.FREE_SPACE -> reclaim.sumOf { it.bytes }.takeIf { it > 0 }?.let { formatBytes(it) to false }
                else -> null
            }
        }
    }
}

/** When the folders were last read (or how the reading goes), and whether it is running. */
@Composable
private fun analysisStatus(viewModel: CollectionViewModel): Pair<String, Boolean> {
    val snapshot by viewModel.snapshot.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val text = when {
        progress != null -> scanningLabel(progress!!)
        snapshot != null -> stringResource(R.string.collection_analysed_at, agoLabel(snapshot!!.scannedAt))
        else -> stringResource(R.string.collection_not_analysed)
    }
    return text to (progress != null)
}

@Composable
private fun ToolRow(
    title: String,
    badge: String?,
    badgeAlert: Boolean,
    /** A spinner instead of the badge (an analysis under way). */
    busy: Boolean,
    selected: Boolean,
    focus: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .focusRequester(focus)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) scheme.surfaceContainer else scheme.surface.copy(alpha = 0f))
            .focusRing(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
        } else badge?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = if (badgeAlert) scheme.error else scheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Portrait: the analysis status in one line under the tabs, with its button on the right. */
@Composable
private fun AnalyzeBar(viewModel: CollectionViewModel) {
    val (status, running) = analysisStatus(viewModel)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        CollectionButton(
            stringResource(if (running) R.string.collection_cancel_analysis else R.string.collection_analyze),
            onClick = if (running) viewModel::cancelAnalysis else viewModel::analyze
        )
    }
}

/** Snackbar text of each outcome. */
@Composable
private fun collectionMessages(): (CollectionMessage) -> String {
    val res = LocalResources.current
    // Why some could not be done, when that is known: the folder lost, the card out, no room, copies that did not match.
    fun reasons(failed: Int, problem: StorageProblem?, unverified: Int): List<String> = listOfNotNull(
        res.getQuantityString(R.plurals.collection_failed, failed, failed).takeIf { failed > 0 },
        when (problem) {
            StorageProblem.NO_SPACE -> res.getString(R.string.collection_problem_no_space)
            StorageProblem.PERMISSION_LOST -> res.getString(R.string.collection_problem_permission)
            StorageProblem.VOLUME_GONE -> res.getString(R.string.collection_problem_volume)
            null -> null
        }?.takeIf { failed > 0 },
        res.getQuantityString(R.plurals.collection_unverified, unverified, unverified).takeIf { unverified > 0 }
    )
    return { message ->
        // Nothing done and something failed: the failure and its reason lead ("0 deleted" says nothing).
        val nothingDone = message.done == 0 && message.failed > 0
        when (message) {
            is CollectionMessage.Deleted ->
                (listOfNotNull(res.getQuantityString(R.plurals.collection_deleted, message.done, message.done, formatBytes(message.bytes)).takeUnless { nothingDone }) +
                    reasons(message.failed, message.problem, message.unverified)).joinToString(" · ")
            is CollectionMessage.Repaired ->
                (listOfNotNull(res.getQuantityString(R.plurals.collection_repaired, message.done, message.done).takeUnless { nothingDone }) +
                    reasons(message.failed, message.problem, message.unverified)).joinToString(" · ")
        }
    }
}

private fun NavController.switchTo(route: NavRoutes) {
    navigate(route.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
