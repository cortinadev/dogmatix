package com.cortinadev.dogmatix.ui.screens.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.theme.OnAccent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.ui.components.TagRow
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.stripExtension

/**
 * One library result. [compact] is the landscape table row; otherwise name and tags stack.
 * Tap downloads, a long press opens the details card (X does the same on a gamepad).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RomRow(
    item: DownloadableFileWithTags,
    consoleName: String,
    compact: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    owned: Boolean = false,
    favourite: Boolean = false,
    downloading: Boolean = false,
    /**
     * Horizontal shift (px) of the left-anchored content, read at placement time so the filter
     * panel animation can slide names along without re-measuring the row. 0 when idle.
     */
    contentShift: () -> Int = { 0 }
) {
    val source = rememberFocusSource()
    val rom = item.file
    val base = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .focusRing(source, startShift = contentShift)
        .combinedClickable(interactionSource = source, indication = null, onClick = onClick, onLongClick = onLongClick)

    if (compact) {
        Row(
            modifier = base
                .defaultMinSize(minHeight = 46.dp)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.weight(1f).slidingCell(contentShift),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (favourite) FavouriteBadge()
                if (downloading) DownloadingBadge() else if (owned) OwnedBadge()
                Text(
                    stripExtension(rom.name),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (owned) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TagRow(
                console = consoleName,
                tags = item.tags,
                extension = rom.fileExtension,
                maxLines = 1,
                modifier = Modifier.width(300.dp)
            )
            SizeText(rom.fileSize, Modifier.width(64.dp))
        }
    } else {
        Row(
            modifier = base
                .defaultMinSize(minHeight = 64.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f).slidingCell(contentShift),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (favourite) FavouriteBadge()
                if (downloading) DownloadingBadge() else if (owned) OwnedBadge()
                    Text(
                        stripExtension(rom.name),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (owned) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TagRow(console = consoleName, tags = item.tags, extension = rom.fileExtension)
            }
            SizeText(rom.fileSize, Modifier.width(64.dp))
        }
    }
}

/**
 * Cell whose content is pushed [shift] px to the right and re-measured with the width that is
 * left, so the name ellipsises progressively while the filter panel slides. The cell keeps
 * reporting its full size, hence only this node re-measures per frame, not the row or the list.
 */
private fun Modifier.slidingCell(shift: () -> Int): Modifier = clipToBounds().layout { measurable, constraints ->
    val dx = shift().coerceAtLeast(0)
    val available = (constraints.maxWidth - dx).coerceAtLeast(0)
    // Only cells whose text would actually be cut get narrower constraints; the others keep the
    // same constraints as the previous frame, which lets Compose skip their measurement entirely.
    val needed = measurable.maxIntrinsicWidth(constraints.maxHeight)
    val placeable = measurable.measure(
        if (available >= needed) constraints.copy(minWidth = 0)
        else constraints.copy(minWidth = 0, maxWidth = available)
    )
    layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(dx, 0) }
}

/** Small check inside a tinted circle: this file is already in the download folder. */
@Composable
private fun OwnedBadge() {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiary),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(R.drawable.ic_check),
            contentDescription = stringResource(R.string.owned),
            tint = OnAccent,
            modifier = Modifier.size(11.dp)
        )
    }
}

/** Star inside an accent circle: the user marked this game as a favourite. */
@Composable
private fun FavouriteBadge() {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(R.drawable.ic_star),
            contentDescription = stringResource(R.string.favourite),
            tint = OnAccent,
            modifier = Modifier.size(11.dp)
        )
    }
}

/** Down arrow inside an accent circle: a download for this file is in flight. */
@Composable
private fun DownloadingBadge() {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painterResource(R.drawable.ic_arrow_down),
            contentDescription = stringResource(R.string.downloading_badge),
            tint = OnAccent,
            modifier = Modifier.size(11.dp)
        )
    }
}

@Composable
private fun SizeText(bytes: Long, modifier: Modifier) {
    Text(
        if (bytes > 0) formatBytes(bytes) else "",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = modifier
    )
}
