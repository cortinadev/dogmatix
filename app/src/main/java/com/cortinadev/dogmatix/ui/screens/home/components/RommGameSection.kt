package com.cortinadev.dogmatix.ui.screens.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.screens.home.DetailsState
import com.cortinadev.dogmatix.ui.screens.home.RommCollectionChoice
import com.cortinadev.dogmatix.ui.screens.home.RommGameUi
import com.cortinadev.dogmatix.ui.screens.home.RommGameViewModel
import com.cortinadev.dogmatix.util.RommPlayState
import com.cortinadev.dogmatix.util.ToastUtil
import kotlinx.coroutines.launch

/** Same height as a row of the versions box, so the card keeps one rhythm. */
private val RommRowHeight = 40.dp

/**
 * The details card's RomM rows, only when the game is on the server: its rating and genres
 * (and its summary when the online lookup found none), the account's play status — ◀ ▶ picks
 * it and it is saved on the server — and the collections it is in (A edits them).
 */
@Composable
fun RommGameSection(state: DetailsState, modifier: Modifier = Modifier, viewModel: RommGameViewModel = hiltViewModel()) {
    val files = remember(state.item, state.versions) {
        state.versions.flatMap { version -> version.discs.map { it.file } }.ifEmpty { listOf(state.item.file) }
    }
    LaunchedEffect(files) { viewModel.open(files) }
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.errors.collect { ToastUtil.showError(context, context.getString(it)) } }

    val game by viewModel.game.collectAsState()
    val current = game ?: return
    val scheme = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf(false) }
    val collectionsFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    if (editing) {
        RommCollectionsDialog(
            choices = current.collections,
            onToggle = viewModel::toggleCollection,
            onDismiss = {
                editing = false
                // Back in the card (once its window has the focus again): the D-pad goes on
                // from the row that opened the dialog.
                scope.launch {
                    repeat(5) {
                        withFrameNanos { }
                        if (runCatching { collectionsFocus.requestFocus() }.isSuccess) return@launch
                    }
                }
            }
        )
    }

    // Scrolls when the card is short of room (a focused row is brought into view).
    Column(modifier = modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val info = current.info
        val meta = listOfNotNull(
            stringResource(R.string.romm_section),
            info?.ratingPercent?.let { stringResource(R.string.romm_rating, it) },
            info?.genres?.takeIf { it.isNotEmpty() }?.take(3)?.joinToString(", ")
        ).joinToString("  ·  ")
        Text(meta, style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        StatusRow(current, onStep = viewModel::stepPlayState)
        if (current.collections.isNotEmpty()) {
            CollectionsRow(current, Modifier.focusRequester(collectionsFocus)) { editing = true }
        }
        // RomM's summary only when the online lookup found none; after the controls, which come first.
        val summary = info?.summary.orEmpty()
        if (summary.isNotBlank() && !state.loading && state.details?.description.isNullOrBlank()) {
            Text(summary, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StatusRow(game: RommGameUi, onStep: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RommRowHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(scheme.surfaceContainerHigh)
            .focusRing(source)
            // ◀ ▶ like the steppers of Settings; A steps forward too.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onStep(-1); true }
                    Key.DirectionRight -> { onStep(1); true }
                    else -> false
                }
            }
            .clickable(interactionSource = source, indication = null) { onStep(1) }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            stringResource(R.string.romm_status),
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        when {
            game.failed -> Text(stringResource(R.string.romm_unreachable), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, maxLines = 1)
            game.loading -> Text(stringResource(R.string.romm_loading), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, maxLines = 1)
            else -> {
                StepArrow("‹") { onStep(-1) }
                Text(
                    stringResource(game.playState.labelRes),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (game.playState == RommPlayState.NONE) scheme.onSurfaceVariant else scheme.primary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.width(132.dp)
                )
                StepArrow("›") { onStep(1) }
            }
        }
    }
}

@Composable
private fun CollectionsRow(game: RommGameUi, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val names = game.collections.filter { it.member }.joinToString(", ") { it.name }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(RommRowHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(scheme.surfaceContainerHigh)
            .focusRing(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.romm_collections), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, maxLines = 1)
        Text(
            names.ifEmpty { stringResource(R.string.romm_collections_none) },
            style = MaterialTheme.typography.bodyMedium,
            color = if (names.isEmpty()) scheme.onSurfaceVariant else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

/** Touch-only: the D-pad steps the row itself, so these never take focus. */
@Composable
private fun StepArrow(glyph: String, onClick: () -> Unit) {
    val source = rememberFocusSource()
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .focusProperties { canFocus = false }
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The account's collections with a check on those holding the game; A puts it in or takes it out, B closes. */
@Composable
private fun RommCollectionsDialog(choices: List<RommCollectionChoice>, onToggle: (Int) -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val firstRow = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.romm_collections_title)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(choices, key = { _, it -> it.id }) { index, choice ->
                    val source = rememberFocusSource()
                    Row(
                        modifier = (if (index == 0) Modifier.focusRequester(firstRow) else Modifier)
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .focusRing(source)
                            .clickable(interactionSource = source, indication = null) { onToggle(choice.id) }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (choice.member) {
                            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                        } else {
                            Spacer(Modifier.size(18.dp))
                        }
                        Text(choice.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.details_close), onClick = onDismiss) }
    )
}

private val RommPlayState.labelRes: Int
    get() = when (this) {
        RommPlayState.NONE -> R.string.romm_state_none
        RommPlayState.BACKLOG -> R.string.romm_state_backlog
        RommPlayState.PLAYING -> R.string.romm_state_playing
        RommPlayState.INCOMPLETE -> R.string.romm_state_incomplete
        RommPlayState.FINISHED -> R.string.romm_state_finished
        RommPlayState.COMPLETED_100 -> R.string.romm_state_completed
        RommPlayState.RETIRED -> R.string.romm_state_retired
        RommPlayState.NEVER_PLAYING -> R.string.romm_state_never
    }
