package com.cortinadev.dogmatix.ui.screens.home.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource

/** [label] is shown in the option list; [shortLabel] (defaults to [label]) in compact places like the collapsed summary and chips. */
data class FilterOption(val id: String, val label: String, val shortLabel: String = label)

/**
 * One row of the filter panel. Multi-select unless [single]; the empty selection means "any".
 * ◀ ▶ (or the D-pad while the row is focused) cycles single choices; click / A opens the option list.
 */
data class FilterRowSpec(
    val label: String,
    val options: List<FilterOption>,
    val selected: Set<String>,
    val single: Boolean = false,
    /** When set, only these ids (plus the current selection) are listed until "Show more" is tapped. */
    val featured: Set<String>? = null,
    val onSelectionChange: (Set<String>) -> Unit
)

@Composable
fun FilterPanel(
    rows: List<FilterRowSpec>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = true,
    firstRowFocus: FocusRequester? = null,
    expandedRow: String? = null,
    onExpandedRowChange: (String?) -> Unit = {},
    footer: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.filters).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val clearSource = rememberFocusSource()
            Text(
                stringResource(R.string.clear),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .focusRing(clearSource, 6.dp)
                    .clickable(interactionSource = clearSource, indication = null, onClick = onClear)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
        rows.forEachIndexed { index, row ->
            FilterRow(
                spec = row,
                compact = compact,
                expanded = expandedRow == row.label,
                onExpandedChange = { onExpandedRowChange(if (it) row.label else null) },
                modifier = if (index == 0 && firstRowFocus != null) Modifier.focusRequester(firstRowFocus) else Modifier
            )
        }
        footer?.let {
            Spacer(Modifier.height(10.dp))
            it()
        }
    }
}

@Composable
private fun FilterRow(
    spec: FilterRowSpec,
    compact: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val rowHeight = if (compact) 40.dp else 48.dp
    val rowFocus = remember { FocusRequester() }

    // When the option list closes, the focused option disappears; put focus back on the row.
    var wasExpanded by remember { mutableStateOf(expanded) }
    LaunchedEffect(expanded) {
        if (wasExpanded && !expanded) runCatching { rowFocus.requestFocus() }
        wasExpanded = expanded
    }

    // Long lists (languages) start folded to the featured ids; "Show more" reveals the rest.
    var showAll by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) { if (!expanded) showAll = false }
    val featuredOptions = remember(spec.options, spec.featured, spec.selected) {
        spec.featured?.let { featured -> spec.options.filter { it.id in featured || it.id in spec.selected } }
    }
    val canFold = featuredOptions != null && featuredOptions.size < spec.options.size
    val visibleOptions = if (canFold && !showAll) featuredOptions!! else spec.options

    fun cycle(delta: Int) {
        // ◀ ▶ walks the short list when there is one; the full list is only a tap away.
        val options = if (canFold) featuredOptions!! else spec.options
        val n = options.size
        if (n == 0) return
        if (spec.single) {
            val current = options.indexOfFirst { it.id in spec.selected }.coerceAtLeast(0)
            spec.onSelectionChange(setOf(options[((current + delta) % n + n) % n].id))
        } else {
            // Slot 0 is "any"; slots 1..n are single picks.
            val current = if (spec.selected.size == 1) options.indexOfFirst { it.id == spec.selected.first() } + 1 else 0
            val next = ((current + delta) % (n + 1) + n + 1) % (n + 1)
            spec.onSelectionChange(if (next == 0) emptySet() else setOf(options[next - 1].id))
        }
    }

    val valueText = when {
        spec.selected.isEmpty() -> stringResource(if (spec.single) R.string.filter_all else R.string.filter_any)
        spec.selected.size == 1 -> spec.options.firstOrNull { it.id == spec.selected.first() }?.shortLabel ?: spec.selected.first()
        else -> stringResource(R.string.filter_selected_count, spec.selected.size)
    }
    val active = spec.selected.isNotEmpty() && !spec.single

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .focusRequester(rowFocus)
                .clip(RoundedCornerShape(8.dp))
                .background(if (active) scheme.surfaceContainer else scheme.surface.copy(alpha = 0f))
                .focusRing(source)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> { cycle(-1); true }
                        Key.DirectionRight -> { cycle(1); true }
                        else -> false
                    }
                }
                .clickable(interactionSource = source, indication = null) { onExpandedChange(!expanded) }
                .padding(start = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                spec.label,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
            Spacer(Modifier.width(8.dp))
            ArrowButton("‹") { cycle(-1) }
            Text(
                valueText,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 2.dp)
            )
            ArrowButton("›") { cycle(1) }
            Icon(
                painterResource(R.drawable.ic_arrow_down),
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .rotate(if (expanded) 180f else 0f)
            )
        }
        fun close() {
            runCatching { rowFocus.requestFocus() }
            onExpandedChange(false)
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(scheme.surfaceContainer)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && (event.key == Key.Back || event.key == Key.ButtonB || event.key == Key.Escape)) {
                            close(); true
                        } else false
                    }
                    .padding(6.dp)
            ) {
                if (!spec.single) {
                    OptionRow(
                        label = stringResource(R.string.filter_any),
                        checked = spec.selected.isEmpty(),
                        compact = compact,
                        showBox = false
                    ) { spec.onSelectionChange(emptySet()); close() }
                }
                visibleOptions.forEach { option ->
                    val checked = option.id in spec.selected
                    OptionRow(label = option.label, checked = checked, compact = compact, showBox = !spec.single) {
                        if (spec.single) {
                            spec.onSelectionChange(setOf(option.id)); close()
                        } else {
                            spec.onSelectionChange(if (checked) spec.selected - option.id else spec.selected + option.id)
                        }
                    }
                }
                if (canFold) {
                    OptionRow(
                        label = stringResource(if (showAll) R.string.filter_show_less else R.string.filter_show_more),
                        checked = false,
                        compact = compact,
                        showBox = false,
                        accent = true
                    ) { showAll = !showAll }
                }
            }
        }
    }
}

@Composable
private fun ArrowButton(glyph: String, onClick: () -> Unit) {
    val source = rememberFocusSource()
    // Touch-only: the D-pad cycles the row itself, so these must not take focus.
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

@Composable
private fun OptionRow(
    label: String,
    checked: Boolean,
    compact: Boolean,
    showBox: Boolean,
    accent: Boolean = false,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 32.dp else 44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (checked && !showBox) scheme.primary else scheme.surfaceContainer)
            .focusRing(source, 6.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (showBox) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (checked) scheme.primary else scheme.surfaceContainerHighest)
            )
        }
        Text(
            label,
            style = if (checked || accent) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
            color = when {
                checked && !showBox -> scheme.onPrimary
                accent -> scheme.primary
                else -> scheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
