package com.cortinadev.dogmatix.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Accent ring drawn while the element owns keyboard / D-pad focus.
 * Pair it with a `clickable(interactionSource = source, ...)` on the same element.
 */
@Composable
fun Modifier.focusRing(
    interactionSource: MutableInteractionSource,
    cornerRadius: Dp = 8.dp,
    width: Dp = 1.5.dp
): Modifier {
    val focused by interactionSource.collectIsFocusedAsState()
    val color = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent
    return this.border(width, color, RoundedCornerShape(cornerRadius))
}

@Composable
fun rememberFocusSource(): MutableInteractionSource = remember { MutableInteractionSource() }
