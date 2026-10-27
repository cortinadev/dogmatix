package com.cortinadev.dogmatix.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** For an `AlertDialog` that sets its own width instead of the platform's 560 dp cap (see [wideDialog]). */
val WideDialogProperties = DialogProperties(usePlatformDefaultWidth = false)

/**
 * Width of the form dialogs of Sources: up to [max] dp, or the screen minus a margin on a narrow
 * one. Goes on the dialog's own modifier together with [WideDialogProperties]; a plain `widthIn`
 * would not do, because the dialog caps its content at 560 dp underneath.
 */
@Composable
fun Modifier.wideDialog(max: Dp = 720.dp): Modifier {
    val screen = LocalConfiguration.current.screenWidthDp.dp
    return width(minOf(max, screen - 32.dp))
}
