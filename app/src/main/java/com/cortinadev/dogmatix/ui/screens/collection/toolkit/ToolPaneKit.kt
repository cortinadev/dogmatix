package com.cortinadev.dogmatix.ui.screens.collection.toolkit

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.Legend
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus

/*
 * Pieces shared by the Collection tab's management tools (History, Smart storage, Move the
 * library, Collections): rows and buttons that take D-pad focus, a flat usage bar, the pane's
 * focus entry and its button legend. Same look as Settings: flat surfaces, accent focus ring.
 */

/**
 * Root of a tool pane: when focus comes in from outside (the tool list, a section switch) it
 * lands on [first], the pane's first useful element, instead of wherever focus search points.
 * While the focus is inside, [legend] is the button legend.
 */
@Composable
fun Modifier.toolPane(first: FocusRequester, legend: List<LegendEntry>): Modifier {
    var published by remember { mutableStateOf<Legend?>(null) }
    DisposableEffect(Unit) {
        onDispose { published?.let { if (Gamepad.legendOverride.value === it) Gamepad.legendOverride.value = null } }
    }
    return this
        .onFocusChanged { state ->
            if (state.hasFocus) {
                val mine = Legend(legend)
                published = mine
                Gamepad.legendOverride.value = mine
            } else {
                published?.let { if (Gamepad.legendOverride.value === it) Gamepad.legendOverride.value = null }
                published = null
            }
        }
        .focusProperties { onEnter = { runCatching { first.requestFocus() } } }
        .focusGroup()
}

/**
 * Brings the focus back to [first] when [key] changes and the element that had it went with the
 * change (a move's Cancel turning into OK, the block closing on OK, the Move button disabled
 * while it runs): without it the focus is left nowhere and the next D-pad press only finds it.
 * Goes before [toolPane] on the pane's root.
 */
@Composable
fun Modifier.refocusOnChange(key: Any?, first: FocusRequester): Modifier {
    val focus = remember { PaneFocus() }
    LaunchedEffect(key) {
        withFrameNanos { }
        withFrameNanos { }
        if (!focus.has && SystemClock.uptimeMillis() - focus.lostAt < 1_000) runCatching { first.requestFocus() }
    }
    return onFocusChanged {
        if (focus.has && !it.hasFocus) focus.lostAt = SystemClock.uptimeMillis()
        focus.has = it.hasFocus
    }
}

private class PaneFocus {
    var has = false
    var lostAt = 0L
}

/** The legend keys every tool pane ends with. */
@Composable
fun paneLegend(vararg entries: LegendEntry): List<LegendEntry> =
    entries.toList() + LegendEntry("B", stringResource(R.string.pad_back)) + LegendEntry("ZL · ZR", stringResource(R.string.pad_section))

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp)
    )
}

/** Plain explanatory text inside a pane. */
@Composable
fun PaneText(text: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}

/**
 * A focusable row: A / tap runs [onClick]; ◀ ▶ call [onAdjust] when given (steppers). A disabled
 * row stays focusable (the D-pad must be able to scroll past it) but is dimmed and does nothing.
 */
@Composable
fun ToolRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onAdjust: ((Int) -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onFocused: (() -> Unit)? = null,
    subtitleColor: androidx.compose.ui.graphics.Color? = null,
    trailing: @Composable () -> Unit = {}
) {
    val source = rememberFocusSource()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { if (it.isFocused) onFocused?.invoke() }
            .focusRing(source)
            .onPreviewKeyEvent { event ->
                if (onAdjust == null || !enabled || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onAdjust(-1); true }
                    Key.DirectionRight -> { onAdjust(1); true }
                    else -> false
                }
            }
            .clickable(interactionSource = source, indication = null) { if (enabled) onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f).alpha(if (enabled) 1f else 0.45f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        trailing()
    }
}

/** A pill button (Settings style). */
@Composable
fun ToolButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null
) {
    val source = rememberFocusSource()
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .focusRing(source)
            .clickable(interactionSource = source, indication = null) { if (enabled) onClick() }
            .padding(horizontal = 12.dp)
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** A row of [ToolButton]s with the pane's side gutter. */
@Composable
fun ToolButtons(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

/** Flat usage bar for a figure (free space, progress): no animation, no glow. */
@Composable
fun UsageBar(fraction: Float, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(6.dp),
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {}
    )
}

/** Confirmation with the focus on Cancel (the safe action); B cancels too. */
@Composable
fun ToolConfirmDialog(title: String, message: String, confirmText: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { DialogButton(text = confirmText, onClick = { onDismiss(); onConfirm() }) },
        dismissButton = { DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus) }
    )
}

/**
 * A line of text to type (a collection's name, a rule's words): focus starts in the field; A on
 * it opens the keyboard. [allowEmpty] lets an empty text through (clears a rule).
 */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    label: String = stringResource(R.string.collections_name),
    allowEmpty: Boolean = false
) {
    var name by remember { mutableStateOf(initial) }
    val fieldFocus = rememberInitialFocus()
    val valid = allowEmpty || name.isNotBlank()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                singleLine = true,
                label = { Text(label) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) { onDismiss(); onConfirm(name.trim()) } }),
                modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus)
            )
        },
        confirmButton = { DialogButton(text = confirmText, enabled = valid, onClick = { onDismiss(); onConfirm(name.trim()) }) },
        dismissButton = { DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss) }
    )
}
