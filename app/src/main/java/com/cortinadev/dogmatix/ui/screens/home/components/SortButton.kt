package com.cortinadev.dogmatix.ui.screens.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.SortOption
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource

/** The list's sort orders as listed: (option, full label, short label for the pill). */
@Composable
private fun sortChoices(consoleSort: Boolean) = listOfNotNull(
    Triple(SortOption.NAME_ASC, stringResource(R.string.sort_name_asc), stringResource(R.string.sort_name_asc)),
    Triple(SortOption.NAME_DESC, stringResource(R.string.sort_name_desc), stringResource(R.string.sort_name_desc)),
    Triple(SortOption.SIZE_DESC, stringResource(R.string.sort_size_desc), stringResource(R.string.sort_size_desc_short)),
    Triple(SortOption.SIZE_ASC, stringResource(R.string.sort_size_asc), stringResource(R.string.sort_size_asc_short)),
    if (consoleSort) Triple(SortOption.CONSOLE, stringResource(R.string.sort_console), stringResource(R.string.sort_console)) else null,
    Triple(SortOption.DECADE_ASC, stringResource(R.string.sort_decade_asc), stringResource(R.string.sort_decade_asc_short)),
    Triple(SortOption.DECADE_DESC, stringResource(R.string.sort_decade_desc), stringResource(R.string.sort_decade_desc_short))
)

/**
 * Sort order pill beside the search box. A (or a tap) opens the list of orders with the current one
 * focused; ▲ ▼ pick, A applies, B closes. ◀ goes to the search box ([onLeft]): on this pill the
 * arrows move between the two, they do not change the order. Highlighted while the order is not
 * the default (A → Z), since any other order replaces the recommendations with the list.
 */
@Composable
fun SortButton(
    sort: SortOption,
    onSortChange: (SortOption) -> Unit,
    onLeft: () -> Unit,
    modifier: Modifier = Modifier,
    /** Offer "Console" (only with more than one console in play). */
    consoleSort: Boolean = true,
    focusRequester: FocusRequester = remember { FocusRequester() },
    /** Whether the list is open: the screen shows its own legend meanwhile. */
    onMenuChange: (Boolean) -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    val choices = sortChoices(consoleSort)
    var menuOpen by remember { mutableStateOf(false) }
    val current = choices.indexOfFirst { it.first == sort }.coerceAtLeast(0)
    val itemFocus = remember(choices.size) { List(choices.size) { FocusRequester() } }
    val active = sort != SortOption.NAME_ASC
    val fg = if (active) scheme.onPrimary else scheme.onSurface

    fun open(value: Boolean) {
        menuOpen = value
        onMenuChange(value)
    }
    // The list is its own window: land on the current order, then back on the pill once it closes.
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(menuOpen) {
        if (menuOpen) {
            for (attempt in 0 until 5) {
                if (runCatching { itemFocus[current].requestFocus() }.isSuccess) break
                withFrameNanos { }
            }
        } else if (wasOpen) {
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
        wasOpen = menuOpen
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .height(44.dp)
                .focusRequester(focusRequester)
                .clip(RoundedCornerShape(10.dp))
                .background(if (active) scheme.primary else scheme.surfaceContainerHigh)
                // An accent ring would vanish on the accent fill.
                .focusRing(source, 10.dp, width = if (active) 2.dp else 1.5.dp, ringColor = if (active) scheme.onSurface else null)
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.DirectionLeft) return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) onLeft()
                    true
                }
                .clickable(interactionSource = source, indication = null) { open(true) }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(painterResource(R.drawable.ic_sort), contentDescription = stringResource(R.string.filter_sort), tint = fg, modifier = Modifier.size(16.dp))
            Text(choices[current].third, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { open(false) },
            modifier = Modifier.closeOnGamepadB { open(false) }
        ) {
            choices.forEachIndexed { index, (option, label, _) ->
                val itemSource = rememberFocusSource()
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            fontWeight = if (option == sort) FontWeight.Bold else null,
                            color = if (option == sort) scheme.primary else scheme.onSurface
                        )
                    },
                    onClick = { open(false); onSortChange(option) },
                    interactionSource = itemSource,
                    modifier = Modifier.focusRequester(itemFocus[index]).focusRing(itemSource, 6.dp)
                )
            }
        }
    }
}
