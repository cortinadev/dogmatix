package com.cortinadev.dogmatix.ui.screens.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.GamepadButton
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource

/**
 * The recent searches, under the search box while it is open and empty. ▼ from the box lands on
 * the first one ([firstFocus]); A applies it, X (or the ✕, by touch) forgets it, and the last row
 * clears them all. ▲ goes back to the box: the panel lets the focus out only that way, so it
 * never wanders into the list or the filters while the search is open.
 */
@Composable
fun RecentSearchesPanel(
    recents: List<String>,
    onApply: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    firstFocus: FocusRequester,
    /** Where the focus goes when the entry under it is the last one left (the search box). */
    fieldFocus: FocusRequester,
    /** True while the focus is on the panel. */
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    var focused by remember { mutableStateOf<String?>(null) }
    val currentRecents by rememberUpdatedState(recents)
    val currentOnRemove by rememberUpdatedState(onRemove)

    // Hand the focus to a neighbour before the entry under it disappears, or Compose drops it.
    fun remove(query: String) {
        val list = currentRecents
        val index = list.indexOf(query)
        val next = list.getOrNull(index + 1) ?: list.getOrNull(index - 1)
        runCatching { (next?.let { requesters[it] } ?: fieldFocus).requestFocus() }
        currentOnRemove(query)
    }

    LaunchedEffect(Unit) {
        Gamepad.presses.collect { button -> if (button == GamepadButton.X) focused?.let(::remove) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.surfaceContainer)
            .focusProperties {
                onExit = {
                    if (requestedFocusDirection == FocusDirection.Down || requestedFocusDirection == FocusDirection.Left ||
                        requestedFocusDirection == FocusDirection.Right) cancelFocusChange()
                }
            }
            .onFocusChanged { onFocusChange(it.hasFocus) }
            .focusGroup()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp)
    ) {
        Text(
            stringResource(R.string.recent_searches),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 4.dp)
        )
        recents.forEachIndexed { index, query ->
            key(query) {
                val requester = requesters.getOrPut(query) { FocusRequester() }
                RecentRow(
                    query = query,
                    onApply = { onApply(query) },
                    onRemove = { remove(query) },
                    modifier = Modifier
                        .focusRequester(requester)
                        .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        .onFocusChanged { if (it.isFocused) focused = query else if (focused == query) focused = null }
                )
            }
        }
        ClearRow(onClick = {
            runCatching { fieldFocus.requestFocus() }
            onClear()
        })
    }
}

@Composable
private fun RecentRow(query: String, onApply: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .focusRing(source)
            .clickable(interactionSource = source, indication = null, onClick = onApply)
            .padding(start = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Text(
            query,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        // Touch only: X does it on a gamepad, and a focus stop here would sit inside the row.
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .focusProperties { canFocus = false }
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.recent_search_remove),
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun ClearRow(onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .focusRing(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(painterResource(R.drawable.ic_trash), contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Text(stringResource(R.string.recent_searches_clear), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
    }
}
