package com.cortinadev.dogmatix.ui.screens.home.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.search.SearchEntry
import com.cortinadev.dogmatix.data.search.SearchGroup
import com.cortinadev.dogmatix.data.search.SearchGroupHits
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource

/** Ask the result list to scroll to [group] and focus its first entry (a new object per press). */
class GroupJump(val group: SearchGroup)

/**
 * "Also in: Downloads (2) · Settings (1)", above the list while the search has text: with many
 * games the groups sit far below, so each name jumps to its group.
 */
@Composable
fun AlsoInLine(
    groups: List<SearchGroupHits>,
    onJump: (SearchGroup) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            stringResource(R.string.search_also_in),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 14.dp, end = 4.dp)
        )
        groups.forEachIndexed { index, hits -> key(hits.group) {
            if (index > 0) Text("·", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            val source = rememberFocusSource()
            Text(
                stringResource(R.string.search_group_count, stringResource(hits.group.label), hits.entries.size),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
                maxLines = 1,
                modifier = Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .focusRing(source, 6.dp)
                    .clickable(interactionSource = source, indication = null) { onJump(hits.group) }
                    .padding(horizontal = 8.dp, vertical = 7.dp)
            )
        } }
    }
}

/** Position of [group]'s header among the group items (add the items placed before them). */
fun globalSearchGroupOffset(groups: List<SearchGroupHits>, group: SearchGroup): Int? {
    var offset = 0
    for (hits in groups) {
        if (hits.group == group) return offset
        offset += 1 + hits.entries.size
    }
    return null
}

/**
 * The groups under the games: a header and up to five entries each; A (or a tap) on an entry goes
 * to it in its section. [groupFocus] is each group's first entry, for the "Also in" jumps;
 * [firstEntryFocus] marks the very first entry when there are no games to land on.
 */
fun LazyListScope.globalSearchGroups(
    groups: List<SearchGroupHits>,
    groupFocus: (SearchGroup) -> FocusRequester,
    firstEntryFocus: FocusRequester?,
    onOpen: (SearchEntry) -> Unit,
    /** True while one of these entries has the focus (the screen's legend and X / SELECT follow it). */
    onFocusChange: (Boolean) -> Unit,
    contentShift: () -> Int
) {
    groups.forEachIndexed { groupIndex, hits ->
        item(key = "search-group:${hits.group.name}") {
            Text(
                stringResource(hits.group.label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .offset { IntOffset(contentShift(), 0) }
                    .padding(start = 14.dp, top = 16.dp, bottom = 4.dp)
            )
        }
        hits.entries.forEachIndexed { entryIndex, entry ->
            item(key = "search-entry:${entry.id}") {
                var modifier: Modifier = Modifier
                if (entryIndex == 0) modifier = modifier.focusRequester(groupFocus(hits.group))
                if (groupIndex == 0 && entryIndex == 0 && firstEntryFocus != null) modifier = modifier.focusRequester(firstEntryFocus)
                GlobalSearchRow(
                    entry = entry,
                    onClick = { onOpen(entry) },
                    contentShift = contentShift,
                    modifier = modifier.onFocusChanged { onFocusChange(it.isFocused) }
                )
            }
        }
    }
}

@Composable
private fun GlobalSearchRow(entry: SearchEntry, onClick: () -> Unit, contentShift: () -> Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .focusRing(source, startShift = contentShift)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .defaultMinSize(minHeight = 48.dp)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f).offset { IntOffset(contentShift(), 0) }) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            entry.subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = scheme.onSurfaceVariant)
    }
}

/** First item of the list when the search found no games but something elsewhere. */
fun LazyListScope.noGamesItem() {
    item(key = "search-no-games") {
        Text(
            stringResource(R.string.search_no_games),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp)
        )
    }
}
