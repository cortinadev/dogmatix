package com.cortinadev.dogmatix.ui.screens.home.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.model.LibraryGame
import com.cortinadev.dogmatix.data.model.RecommendationState
import com.cortinadev.dogmatix.data.model.Shelf
import com.cortinadev.dogmatix.data.model.ShelfKind
import com.cortinadev.dogmatix.ui.components.GameCover
import com.cortinadev.dogmatix.ui.components.TagChip
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.stripExtension
import com.cortinadev.dogmatix.ui.components.tagPalette
import com.cortinadev.dogmatix.ui.theme.OnAccent

/** Square cover of a recommendation card (the card is as wide). */
private val CardCover = 104.dp

/**
 * What the library shows instead of the list while no filter is on: shelves of games picked
 * from what the user has (see `RecommendationsService`), then "All games". A card works like a
 * list row: A (tap) downloads, X (long press) opens the details card, SELECT stars it.
 * [firstFocus] sits on the first card on screen (RB and closing the search land there).
 * [contentShift] slides the shelves with the filter panel, like the list's names.
 */
@Composable
fun Recommendations(
    state: RecommendationState,
    consoleName: (String) -> String,
    isFavourite: (DownloadableFileWithTags) -> Boolean,
    isDownloading: (DownloadableFileWithTags) -> Boolean,
    onClick: (DownloadableFileWithTags) -> Unit,
    onLongClick: (DownloadableFileWithTags) -> Unit,
    onCardFocused: (DownloadableFileWithTags) -> Unit,
    onShowAll: () -> Unit,
    onOpenSources: () -> Unit,
    firstFocus: FocusRequester,
    modifier: Modifier = Modifier,
    contentShift: () -> Int = { 0 }
) {
    val shifted = modifier.offset { IntOffset(contentShift(), 0) }
    when {
        !state.ready -> Message(shifted) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
            MessageText(stringResource(R.string.rec_loading))
            // The list is a press away meanwhile.
            LinkPill(stringResource(R.string.rec_all_games), onShowAll, Modifier.focusRequester(firstFocus))
        }
        state.libraryEmpty -> Message(shifted) {
            Text(stringResource(R.string.rec_empty_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            MessageText(stringResource(R.string.rec_empty_body))
            LinkPill(stringResource(R.string.rec_go_sources), onOpenSources, Modifier.focusRequester(firstFocus))
        }
        state.shelves.isEmpty() -> Message(shifted) {
            MessageText(stringResource(R.string.rec_none))
            LinkPill(stringResource(R.string.rec_all_games), onShowAll, Modifier.focusRequester(firstFocus))
        }
        else -> Shelves(state, consoleName, isFavourite, isDownloading, onClick, onLongClick, onCardFocused, onShowAll, firstFocus, shifted)
    }
}

/** Where the focus was among the cards; plain fields, a focus move must not recompose anything. */
private class FocusMemo {
    var shelf = 0
    var card = 0
    var id: Long? = null
    var inside = false
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Shelves(
    state: RecommendationState,
    consoleName: (String) -> String,
    isFavourite: (DownloadableFileWithTags) -> Boolean,
    isDownloading: (DownloadableFileWithTags) -> Boolean,
    onClick: (DownloadableFileWithTags) -> Unit,
    onLongClick: (DownloadableFileWithTags) -> Unit,
    onCardFocused: (DownloadableFileWithTags) -> Unit,
    onShowAll: () -> Unit,
    firstFocus: FocusRequester,
    modifier: Modifier
) {
    val shelves = state.shelves
    val columnState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val topPaddingPx = with(LocalDensity.current) { ShelvesTopPadding.toPx() }
    val rowStates = remember { HashMap<String, LazyListState>() }
    val memo = remember { FocusMemo() }

    // The shelves are worked out again when the library changes (a download finished, say): if
    // the focused card went away, the focus goes to the card now in its place. Decided while
    // composing the new shelves, i.e. before the old card is removed and the focus cleared.
    val restoreFocus = remember { FocusRequester() }
    var restoreAt by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val lost = remember(shelves) {
        val stillThere = memo.id != null && shelves.any { shelf -> shelf.games.any { it.primary.file.id == memo.id } }
        if (memo.inside && !stillThere) memo.shelf to memo.card else null
    }
    LaunchedEffect(lost) {
        val (shelfAt, cardAt) = lost ?: return@LaunchedEffect
        val shelf = shelfAt.coerceIn(0, shelves.lastIndex)
        val card = cardAt.coerceIn(0, shelves[shelf].games.lastIndex)
        columnState.scrollToItem(shelf)
        rowStates[shelves[shelf].key]?.scrollToItem(card)
        restoreAt = shelf to card
        for (attempt in 0 until 5) {
            withFrameNanos { }
            if (runCatching { restoreFocus.requestFocus() }.isSuccess) break
        }
        restoreAt = null
    }

    val firstShelf = columnState.firstVisibleItemIndex
    LazyColumn(
        state = columnState,
        modifier = modifier
            .fillMaxSize()
            .onFocusChanged { memo.inside = it.hasFocus },
        contentPadding = PaddingValues(top = ShelvesTopPadding, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        itemsIndexed(shelves, key = { _, shelf -> shelf.key }) { shelfIndex, shelf ->
            val rowState = rowStates.getOrPut(shelf.key) { LazyListState() }
            // A focused card shows its whole shelf, title included (the card alone would leave the
            // title above the edge when going up); the first one also brings the list's top padding back.
            val shelfInView = remember { BringIntoViewRequester() }
            val shelfSize = remember { IntArray(2) }
            Column(
                modifier = Modifier
                    .bringIntoViewRequester(shelfInView)
                    .onSizeChanged { shelfSize[0] = it.width; shelfSize[1] = it.height },
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    shelfTitle(shelf, consoleName),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp)
                )
                val firstCard = rowState.firstVisibleItemIndex
                LazyRow(
                    state = rowState,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    itemsIndexed(shelf.games, key = { _, game -> game.primary.file.id }) { cardIndex, game ->
                        val item = game.primary
                        GameCard(
                            game = game,
                            consoleName = consoleName(item.file.consoleId),
                            favourite = isFavourite(item),
                            downloading = isDownloading(item),
                            onClick = { onClick(item) },
                            onLongClick = { onLongClick(item) },
                            modifier = when {
                                restoreAt == (shelfIndex to cardIndex) -> Modifier.focusRequester(restoreFocus)
                                shelfIndex == firstShelf && cardIndex == firstCard -> Modifier.focusRequester(firstFocus)
                                else -> Modifier
                            }.onFocusChanged {
                                if (it.isFocused) {
                                    memo.shelf = shelfIndex
                                    memo.card = cardIndex
                                    memo.id = item.file.id
                                    onCardFocused(item)
                                    // Asked of the same responder as the card's own request, so the two are
                                    // satisfied together (a separate scroll would be cancelled by it).
                                    scope.launch {
                                        val above = if (shelfIndex == 0) topPaddingPx else 0f
                                        shelfInView.bringIntoView(Rect(0f, -above, shelfSize[0].toFloat(), shelfSize[1].toFloat()))
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
        item(key = "all") {
            LinkPill(
                stringResource(R.string.rec_all_games),
                onShowAll,
                Modifier.padding(start = 4.dp).then(if (firstShelf >= shelves.size) Modifier.focusRequester(firstFocus) else Modifier)
            )
        }
    }
}

private val ShelvesTopPadding = 10.dp

@Composable
private fun shelfTitle(shelf: Shelf, consoleName: (String) -> String): String {
    val console = shelf.consoleId?.let(consoleName).orEmpty()
    return when (shelf.kind) {
        ShelfKind.FOR_YOU -> stringResource(R.string.rec_for_you)
        ShelfKind.SERIES -> stringResource(R.string.rec_series)
        ShelfKind.MORE_FROM -> stringResource(R.string.rec_more_from, console)
        ShelfKind.POPULAR -> stringResource(R.string.rec_popular)
        ShelfKind.HIGHLIGHTS -> stringResource(R.string.rec_highlights, console)
    }
}

/** One game: square cover, name and console chip. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameCard(
    game: LibraryGame,
    consoleName: String,
    favourite: Boolean,
    downloading: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val source = rememberFocusSource()
    val rom = game.primary.file
    Column(
        modifier = modifier
            .width(CardCover + 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .focusRing(source, 10.dp)
            .combinedClickable(interactionSource = source, indication = null, onClick = onClick, onLongClick = onLongClick)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box {
            GameCover(game, CardCover)
            Row(modifier = Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (favourite) CardBadge(R.drawable.ic_star, stringResource(R.string.favourite))
                if (downloading) CardBadge(R.drawable.ic_arrow_down, stringResource(R.string.downloading_badge))
            }
        }
        Text(
            stripExtension(rom.name),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        TagChip(consoleName, emphasized = true, colors = tagPalette().console(rom.consoleId))
    }
}

/** Star / arrow in an accent circle over the cover, like the list's badges. */
@Composable
private fun CardBadge(icon: Int, description: String) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(icon), contentDescription = description, tint = OnAccent, modifier = Modifier.size(11.dp))
    }
}

/** Flat text button with an arrow ("All games", "Go to Sources"). */
@Composable
private fun LinkPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.surfaceContainerHigh)
            .focusRing(source, 10.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
        Icon(painterResource(R.drawable.ic_arrow_right), contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun Message(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) { content() }
    }
}

@Composable
private fun MessageText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}
