package com.cortinadev.dogmatix.ui.components

import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.ui.common.Gamepad

/**
 * Gamepad B (and Back / Escape) closes the dialog this modifier is applied to.
 * Dialogs are their own window, so the shortcut bus in [com.cortinadev.dogmatix.ui.common.Gamepad]
 * never sees these keys: they must be handled on the dialog content itself.
 */
fun Modifier.closeOnGamepadB(onDismiss: () -> Unit): Modifier = swapFaceButtons().onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && (event.key == Key.ButtonB || event.key == Key.Back || event.key == Key.Escape)) {
        onDismiss(); true
    } else false
}

/**
 * Applies the "swap A/B and X/Y" setting inside a window of its own — a dialog or the modal
 * filter sheet, whose keys never reach `MainActivity.dispatchKeyEvent`. The pressed key is
 * swallowed and the swapped one dispatched again from the window root, so everything below
 * (Compose's own "confirm = click" included) only ever sees the swapped key.
 */
fun Modifier.swapFaceButtons(): Modifier = this then SwapFaceButtonsElement

private data object SwapFaceButtonsElement : ModifierNodeElement<SwapFaceButtonsNode>() {
    override fun create() = SwapFaceButtonsNode()
    override fun update(node: SwapFaceButtonsNode) = Unit
    override fun InspectorInfo.inspectableProperties() { name = "swapFaceButtons" }
}

private class SwapFaceButtonsNode : Modifier.Node(), KeyInputModifierNode, CompositionLocalConsumerModifierNode {
    // Preview order is root → focused element, so this runs before the dialog's own shortcuts.
    override fun onPreKeyEvent(event: KeyEvent): Boolean {
        val native = event.nativeKeyEvent
        val swapped = Gamepad.remap(native)
        if (swapped === native) return false
        // From the decor view so the window itself (a dialog's Back handling) sees the key too.
        Gamepad.withoutRemapping { currentValueOf(LocalView).rootView.dispatchKeyEvent(swapped) }
        return true
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = false
}

/**
 * Requests focus on first composition so the D-pad lands inside the dialog, not behind it.
 * Retried for a few frames: the target may not be attached on the first one, and without
 * focus the first gamepad press only "wakes" the dialog instead of acting.
 */
@Composable
fun rememberInitialFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(5) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }
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
