package com.cortinadev.dogmatix.ui.screens.home.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.cortinadev.dogmatix.ui.components.TagRow
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.stripExtension
import com.cortinadev.dogmatix.ui.screens.home.DetailsState
import kotlinx.coroutines.launch

/**
 * Card with the online metadata of one library entry. Opens with X, closes with B or X;
 * the D-pad scrolls the synopsis while a button is focused, so nothing needs the touch screen.
 */
@Composable
fun GameDetailsDialog(
    state: DetailsState,
    consoleName: String,
    favourite: Boolean,
    onToggleFavourite: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val scheme = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val downloadFocus = remember { FocusRequester() }
    val rom = state.item.file

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
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.ButtonX -> { onDismiss(); true }
                        // The dialog is its own window, so Select never reaches the Activity's gamepad bus.
                        Key.ButtonSelect, Key.ButtonThumbLeft -> { onToggleFavourite(); true }
                        Key.DirectionUp -> scroll.maxValue > 0 && scroll.value > 0 && scope.launch { scroll.animateScrollBy(-SCROLL_STEP) }.let { true }
                        Key.DirectionDown -> scroll.maxValue > 0 && scroll.value < scroll.maxValue && scope.launch { scroll.animateScrollBy(SCROLL_STEP) }.let { true }
                        else -> false
                    }
                }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val details = state.details
            val title = details?.title?.takeIf { it.isNotBlank() } ?: stripExtension(rom.name)

            if (isLandscape) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.heightIn(max = 260.dp)) {
                    Artwork(details, Modifier.width(240.dp).height(180.dp))
                    Body(state, title, consoleName, scroll, Modifier.weight(1f))
                }
            } else {
                Artwork(details, Modifier.fillMaxWidth().aspectRatio(16f / 10f))
                Body(state, title, consoleName, scroll, Modifier.heightIn(max = 300.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                val favouriteSource = rememberFocusSource()
                TextButton(onClick = onToggleFavourite, interactionSource = favouriteSource, modifier = Modifier.focusRing(favouriteSource, 20.dp)) {
                    Text(stringResource(if (favourite) R.string.details_unfavourite else R.string.details_favourite))
                }
                val closeSource = rememberFocusSource()
                TextButton(onClick = onDismiss, interactionSource = closeSource, modifier = Modifier.focusRing(closeSource, 20.dp)) {
                    Text(stringResource(R.string.details_close))
                }
                val downloadSource = rememberFocusSource()
                Button(
                    onClick = onDownload,
                    interactionSource = downloadSource,
                    modifier = Modifier.focusRequester(downloadFocus).focusRing(downloadSource, 20.dp)
                ) {
                    Text(stringResource(R.string.details_download))
                }
            }
        }
    }
}

@Composable
private fun Artwork(details: GameDetails?, modifier: Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        val url = details?.imageUrl.orEmpty()
        if (url.isNotEmpty()) {
            AsyncImage(
                model = url,
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
        TagRow(console = consoleName, tags = state.item.tags, extension = state.item.file.fileExtension, maxLines = 1)
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
