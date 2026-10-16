package com.cortinadev.dogmatix.ui.screens.home.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.GameDetails
import com.cortinadev.dogmatix.data.model.GameVersion
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.util.VersionGrouping
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import com.cortinadev.dogmatix.ui.components.TagRow
import com.cortinadev.dogmatix.ui.components.rememberCoverUrl
import com.cortinadev.dogmatix.ui.components.rememberImageModel
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.stripExtension
import com.cortinadev.dogmatix.ui.components.swapFaceButtons
import com.cortinadev.dogmatix.ui.screens.home.DetailsState
import kotlinx.coroutines.launch

/**
 * Card with the online metadata of one library entry. Opens with X, closes with B or X;
 * the D-pad scrolls the synopsis while a button is focused, so nothing needs the touch screen.
 * "Similar" closes it and filters the library by the games similar to this one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameDetailsDialog(
    state: DetailsState,
    consoleName: String,
    favourite: Boolean,
    onToggleFavourite: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
    onShowSimilar: () -> Unit,
    /** Whether each version is on disk or downloading, for its badge. */
    versionState: (GameVersion) -> VersionState = { VersionState.NONE },
    /** A on a version of the "Versions" list (every disc of it). */
    onDownloadVersion: (GameVersion) -> Unit = {},
    /**
     * Extra rows between the versions and the buttons (the game on the RomM server). In the
     * card's column, so they can take a `weight` and give way like the top block does.
     */
    extra: (@Composable ColumnScope.() -> Unit)? = null,
    /** coleccion-gestion: "Collection" button (manual collections); null hides it. */
    onAddToCollection: (() -> Unit)? = null
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scheme = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val downloadFocus = remember { FocusRequester() }
    val rom = state.item.file
    // ▲ ▼ scroll the synopsis only from the buttons; inside the versions list they move the focus.
    var actionsFocused by remember { mutableStateOf(true) }
    // LB / RB jump between the versions box and the buttons, like filters ⇄ list in the library;
    // LB lands on the version focused last (the preferred one at first).
    val versionsList = rememberLazyListState()
    val versionFocus = remember(state.versions) { List(state.versions.size) { FocusRequester() } }
    var lastVersion by remember(state.versions) { mutableIntStateOf(0) }
    fun focusVersions() {
        val index = lastVersion.coerceIn(0, versionFocus.lastIndex)
        scope.launch {
            if (versionsList.layoutInfo.visibleItemsInfo.none { it.index == index }) versionsList.scrollToItem(index)
            repeat(3) {
                if (runCatching { versionFocus[index].requestFocus() }.isSuccess) return@launch
                withFrameNanos { }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // The button is not attached on the first frame: retry for a few frames so the first
        // gamepad press acts instead of merely initialising focus.
        LaunchedEffect(Unit) {
            repeat(5) {
                if (runCatching { downloadFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                withFrameNanos { }
            }
        }
        Column(
            modifier = Modifier
                .padding(horizontal = if (isLandscape) 48.dp else 16.dp, vertical = 24.dp)
                .widthIn(max = 720.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(scheme.surfaceContainer)
                // Its own window: the face-button swap has to be applied here too.
                .swapFaceButtons()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.ButtonX -> { onDismiss(); true }
                        Key.ButtonL1 -> (state.versions.size > 1).also { if (it && actionsFocused) focusVersions() }
                        Key.ButtonR1 -> (state.versions.size > 1).also { if (it && !actionsFocused) runCatching { downloadFocus.requestFocus() } }
                        // The dialog is its own window, so Select never reaches the Activity's gamepad bus.
                        Key.ButtonSelect, Key.ButtonThumbLeft -> { onToggleFavourite(); true }
                        Key.DirectionUp -> actionsFocused && scroll.maxValue > 0 && scroll.value > 0 && scope.launch { scroll.animateScrollBy(-SCROLL_STEP) }.let { true }
                        Key.DirectionDown -> actionsFocused && scroll.maxValue > 0 && scroll.value < scroll.maxValue && scope.launch { scroll.animateScrollBy(SCROLL_STEP) }.let { true }
                        else -> false
                    }
                }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val details = state.details
            // Always the library's own name: a database hit only adds artwork and text, never renames the game.
            val title = stripExtension(rom.name)

            if (isLandscape) {
                // The top block gives way first (its synopsis scrolls) so the versions list and the
                // buttons always fit on screen.
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.heightIn(max = 260.dp).weight(1f, fill = false)) {
                    Artwork(state, details, Modifier.width(240.dp).height(180.dp))
                    Body(state, title, consoleName, scroll, Modifier.weight(1f))
                }
            } else {
                Artwork(state, details, Modifier.fillMaxWidth().aspectRatio(16f / 10f))
                Body(state, title, consoleName, scroll, Modifier.heightIn(max = 300.dp).weight(1f, fill = false))
            }

            if (state.versions.size > 1) {
                VersionsSection(state.versions, consoleName, versionState, onDownloadVersion, versionsList, versionFocus) { lastVersion = it }
            }
            extra?.invoke(this)

            // coleccion-gestion: wraps instead of squeezing the buttons on a narrow (portrait) screen.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth().onFocusChanged { actionsFocused = it.hasFocus }
            ) {
                val favouriteSource = rememberFocusSource()
                TextButton(onClick = onToggleFavourite, interactionSource = favouriteSource, modifier = Modifier.focusRing(favouriteSource, 20.dp)) {
                    Text(stringResource(if (favourite) R.string.details_unfavourite else R.string.details_favourite))
                }
                val similarSource = rememberFocusSource()
                TextButton(onClick = onShowSimilar, interactionSource = similarSource, modifier = Modifier.focusRing(similarSource, 20.dp)) {
                    Text(stringResource(R.string.details_similar))
                }
                onAddToCollection?.let { onCollection ->
                    val collectionSource = rememberFocusSource()
                    TextButton(onClick = onCollection, interactionSource = collectionSource, modifier = Modifier.focusRing(collectionSource, 20.dp)) {
                        Text(stringResource(R.string.details_collection))
                    }
                }
                val closeSource = rememberFocusSource()
                TextButton(onClick = onDismiss, interactionSource = closeSource, modifier = Modifier.focusRing(closeSource, 20.dp)) {
                    Text(stringResource(R.string.details_close))
                }
                // Filled with the accent: the focus border goes on the button's own shape, in white
                // (focusRing would ring the 48 dp touch target, sticking out of the 40 dp pill).
                val downloadSource = rememberFocusSource()
                val downloadFocused by downloadSource.collectIsFocusedAsState()
                Button(
                    onClick = onDownload,
                    interactionSource = downloadSource,
                    border = if (downloadFocused) BorderStroke(2.dp, Color.White) else null,
                    modifier = Modifier.focusRequester(downloadFocus)
                ) {
                    Text(stringResource(R.string.details_download))
                }
            }
        }
    }
}

/** The database's image, or the box art of the library cover lookup when the database has none. */
@Composable
private fun Artwork(state: DetailsState, details: GameDetails?, modifier: Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        val rom = state.item.file
        val cover = rememberCoverUrl(rom.consoleId, rom.fileName, rom.name)
        val url = details?.imageUrl?.ifBlank { null } ?: cover.takeUnless { state.loading }.orEmpty()
        if (url.isNotEmpty()) {
            AsyncImage(
                model = rememberImageModel(url),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(400.dp)
            )
        }
    }
}

@Composable
private fun Body(state: DetailsState, title: String, consoleName: String, scroll: androidx.compose.foundation.ScrollState, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val details = state.details
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        TagRow(console = consoleName, tags = state.item.tags, extension = state.item.file.fileExtension, maxLines = 1, consoleId = state.item.file.consoleId)
        val meta = listOfNotNull(
            details?.released?.takeIf { it.isNotBlank() },
            details?.developer?.takeIf { it.isNotBlank() },
            details?.genres?.takeIf { it.isNotEmpty() }?.joinToString(", ")
        ).joinToString("  ·  ")
        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelLarge, color = scheme.primary)

        Box(modifier = Modifier.weight(1f, fill = false)) {
            when {
                state.loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.details_loading), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
                details == null -> Text(stringResource(R.string.details_not_found), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                else -> Column(modifier = Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        details.description.ifBlank { stringResource(R.string.details_not_found) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface
                    )
                    Text(stringResource(R.string.details_source, details.source), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

private const val SCROLL_STEP = 160f

/** What a version of the game is doing on this device. */
enum class VersionState { NONE, OWNED, DOWNLOADING }

/** Height of one row of the versions box; the box shows three and scrolls the rest. */
private val VersionRowHeight = 40.dp
private val VersionRowGap = 4.dp
/** Three versions at most, so the buttons below always stay on screen. */
private val VersionsMaxHeight = VersionRowHeight * 3 + VersionRowGap * 2

/**
 * Every version of the game, best first (the first one is what A on the library row downloads),
 * in a box three rows tall that scrolls. A row downloads that version, all its discs; the card
 * stays open so several can be queued. [focus] holds one requester per row for LB.
 */
@Composable
private fun VersionsSection(
    versions: List<GameVersion>,
    consoleName: String,
    versionState: (GameVersion) -> VersionState,
    onDownload: (GameVersion) -> Unit,
    listState: LazyListState,
    focus: List<FocusRequester>,
    onRowFocused: (Int) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.details_versions) + "  ·  " + versions.size,
            style = MaterialTheme.typography.titleSmall,
            color = scheme.onSurfaceVariant
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.heightIn(max = VersionsMaxHeight),
            verticalArrangement = Arrangement.spacedBy(VersionRowGap)
        ) {
            itemsIndexed(versions, key = { _, version -> version.primary.file.id }) { index, version ->
                val source = rememberFocusSource()
                val discs = version.discs.size
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(VersionRowHeight)
                        .clip(RoundedCornerShape(8.dp))
                        .background(scheme.surfaceContainerHigh)
                        .focusRequester(focus[index])
                        .onFocusChanged { if (it.isFocused) onRowFocused(index) }
                        .focusRing(source)
                        .clickable(interactionSource = source, indication = null) { onDownload(version) }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when (versionState(version)) {
                        VersionState.OWNED -> Icon(painterResource(R.drawable.ic_check), stringResource(R.string.owned), tint = scheme.tertiary, modifier = Modifier.size(14.dp))
                        VersionState.DOWNLOADING -> Icon(painterResource(R.drawable.ic_arrow_down), stringResource(R.string.downloading_badge), tint = scheme.primary, modifier = Modifier.size(14.dp))
                        VersionState.NONE -> Spacer(Modifier.size(14.dp))
                    }
                    TagRow(
                        console = consoleName,
                        tags = VersionGrouping.displayTags(version),
                        extension = version.primary.file.fileExtension,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                        consoleId = version.primary.file.consoleId
                    )
                    val extra = listOfNotNull(
                        if (index == 0) stringResource(R.string.details_version_preferred) else null,
                        if (discs > 1) pluralStringResource(R.plurals.game_discs, discs, discs) else null,
                        version.totalSize.takeIf { it > 0 }?.let { formatBytes(it) }
                    ).joinToString("  ·  ")
                    Text(extra, style = MaterialTheme.typography.labelMedium, color = if (index == 0) scheme.primary else scheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}
