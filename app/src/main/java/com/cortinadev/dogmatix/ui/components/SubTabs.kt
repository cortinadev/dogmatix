package com.cortinadev.dogmatix.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Landscape: width of the side panels (the Library's filters, Collection's tools, Settings'
 * categories), the same in all three so the list beside them starts at the same place.
 */
val SidePanelWidth: Dp = 216.dp

/**
 * Portrait: the sub-sections of a screen (Settings categories, Collection tools) as tabs fixed at
 * the top, scrolling sideways when they do not fit. Every tab stays composed (a plain scrolling
 * row, not a lazy one), so the focus can be handed to a tab that is off screen.
 */
@Composable
fun SubTabs(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
    }
}

/**
 * One tab of [SubTabs], underlined in the accent when [selected] (like the section tabs). The
 * selected tab scrolls into view when it changes; [badge] is a short figure after the title.
 */
@Composable
fun SubTab(
    title: String,
    selected: Boolean,
    focus: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    badge: String? = null,
    badgeAlert: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) { if (selected) bringIntoView.bringIntoView() }
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .bringIntoViewRequester(bringIntoView)
            .focusRequester(focus)
            .onFocusChanged { if (it.isFocused) onFocused() }
            // The underline goes before the clip: inside it the rounded corners (the focus ring's) cut its ends.
            .drawBehind {
                if (selected) {
                    drawRect(
                        color = scheme.primary,
                        topLeft = Offset(0f, size.height - 2.dp.toPx()),
                        size = Size(size.width, 2.dp.toPx())
                    )
                }
            }
            .clip(RoundedCornerShape(6.dp))
            .focusRing(source, 6.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = 1
        )
        badge?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = if (badgeAlert) scheme.error else scheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
