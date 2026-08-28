package com.cortinadev.dogmatix.ui.components

import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

/**
 * Gamepad B (and Back / Escape) closes the dialog this modifier is applied to.
 * Dialogs are their own window, so the shortcut bus in [com.cortinadev.dogmatix.ui.common.Gamepad]
 * never sees these keys: they must be handled on the dialog content itself.
 */
fun Modifier.closeOnGamepadB(onDismiss: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && (event.key == Key.ButtonB || event.key == Key.Back || event.key == Key.Escape)) {
        onDismiss(); true
    } else false
}

/** Requests focus on first composition so the D-pad lands inside the dialog, not behind it. */
@Composable
fun rememberInitialFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    return requester
}

/** Dialog text button with the accent focus ring; [initialFocus] takes focus when the dialog opens. */
@Composable
fun DialogButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    initialFocus: FocusRequester? = null
) {
    val source = rememberFocusSource()
    val modifier = (initialFocus?.let { Modifier.focusRequester(it) } ?: Modifier).focusRing(source, 20.dp)
    TextButton(onClick = onClick, enabled = enabled, interactionSource = source, modifier = modifier) {
        Text(text)
    }
}
