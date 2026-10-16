package com.cortinadev.dogmatix.ui.screens.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource

/** One chip of [ActiveFiltersBar]: what narrows the list, and how to drop it. */
data class ActiveFilter(val key: String, val label: String, val loading: Boolean = false, val onRemove: () -> Unit)

/** A chip per filter row that is not on "any": "Region: Europe, Japan +1", "Favourites: ★". */
fun activeFiltersOf(rows: List<FilterRowSpec>): List<ActiveFilter> = rows.mapNotNull { row ->
    val any = if (row.single) setOfNotNull(row.options.firstOrNull()?.id) else emptySet()
    if (row.selected.isEmpty() || row.selected == any) return@mapNotNull null
    val names = row.options.filter { it.id in row.selected }.map { it.shortLabel }.ifEmpty { row.selected.toList() }
    val shown = names.take(2).joinToString(", ") + if (names.size > 2) " +${names.size - 2}" else ""
    ActiveFilter(key = row.label, label = "${row.label}: $shown", onRemove = { row.onSelectionChange(any) })
}

/**
 * The filters in force as a slim line of chips under the search box. A (or a tap) on a chip drops
 * it, and "Clear" at the end (with two or more) drops them all. The list's ▲ lands on the first
 * chip through [entryFocus]; ▲ again goes to [upFocus] (the sort button). After a removal the chip that takes the
 * place of the removed one gets the focus; when the bar is about to go, [onEmptied] hands it on.
 */
@Composable
fun ActiveFiltersBar(
    filters: List<ActiveFilter>,
    onClearAll: () -> Unit,
    entryFocus: FocusRequester,
    upFocus: FocusRequester,
    onEmptied: () -> Unit,
    modifier: Modifier = Modifier
) {
    val withClear = filters.size > 1
    val count = filters.size + if (withClear) 1 else 0
    // One requester per position, kept across changes: the chip that moves into a removed one's place takes its requester.
    val requesters = remember { ArrayList<FocusRequester>() }
    while (requesters.size < count) requesters += FocusRequester()
    var refocusAt by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(filters) {
        val at = refocusAt ?: return@LaunchedEffect
        refocusAt = null
        withFrameNanos { }
        runCatching { requesters[at.coerceAtMost(count - 1)].requestFocus() }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .focusGroup()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        filters.forEachIndexed { index, filter ->
            key(filter.key) {
                FilterChip(
                    label = filter.label,
                    loading = filter.loading,
                    focus = requesters[index],
                    entryFocus = entryFocus.takeIf { index == 0 },
                    upFocus = upFocus,
                    last = index == count - 1,
                    onClick = {
                        if (filters.size == 1) onEmptied() else refocusAt = index
                        filter.onRemove()
                    }
                )
            }
        }
        if (withClear) {
            FilterChip(
                label = stringResource(R.string.clear),
                accent = true,
                focus = requesters[filters.size],
                upFocus = upFocus,
                last = true,
                onClick = {
                    onEmptied()
                    onClearAll()
                }
            )
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    focus: FocusRequester,
    upFocus: FocusRequester,
    last: Boolean,
    onClick: () -> Unit,
    /** The bar's own entry point, on its first chip. */
    entryFocus: FocusRequester? = null,
    loading: Boolean = false,
    /** "Clear": accent text, no ✕. */
    accent: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    Row(
        modifier = Modifier
            .height(30.dp)
            .focusRequester(focus)
            .then(entryFocus?.let { Modifier.focusRequester(it) } ?: Modifier)
            // ▲ goes straight to the sort button (the focus search could pick a tab instead).
            .onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp && runCatching { upFocus.requestFocus() }.isSuccess
            }
            // Nothing to the right of the last chip: without this the focus search would wander into the list.
            .focusProperties { if (last) right = FocusRequester.Cancel }
            .clip(RoundedCornerShape(15.dp))
            .background(if (accent) scheme.surface.copy(alpha = 0f) else scheme.surfaceContainerHigh)
            .focusRing(source, 15.dp)
            .clickable(interactionSource = source, indication = null, onClickLabel = stringResource(R.string.pad_remove), onClick = onClick)
            .padding(start = 12.dp, end = if (accent) 12.dp else 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (accent) scheme.primary else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (loading) CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
        if (!accent) {
            Icon(
                painterResource(R.drawable.ic_close),
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}
