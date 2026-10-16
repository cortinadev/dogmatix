package com.cortinadev.dogmatix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.util.ColorMath
import kotlin.math.roundToInt

/** Swatches offered first: the family and tag defaults plus a few more, spread around the wheel. */
private val presets: List<Int> = listOf(
    0xFFFF5A5F, 0xFFFF7AA8, 0xFFE86BD6, 0xFFA57BFF, 0xFF7C6CF2, 0xFF5B8CFF, 0xFF4E99FF, 0xFF2FA8FF,
    0xFF2ED6B8, 0xFF5CC93B, 0xFF8FCF3C, 0xFFE7C24A, 0xFFFFB300, 0xFFFF8A3D, 0xFFB08968, 0xFF9AA0AE
).map { it.toInt() }

/**
 * Picks a colour with the D-pad as well as by touch: preset swatches (A takes one) and hue /
 * saturation / brightness rows that ◀ ▶ adjust; the hex code is shown, not typed (a text field
 * on the D-pad path would pop the keyboard up and trap the pad). OK confirms, "Default" hands
 * back null (the built-in colour), B closes without changes. [sample] is the text of the preview
 * chip, tinted the way the chip will look ([strong] for console chips).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerDialog(
    title: String,
    initial: Color,
    sample: String,
    strong: Boolean,
    onPick: (Int?) -> Unit,
    onDismiss: () -> Unit
) {
    val palette = tagPalette()
    val start = initial.toArgb() or 0xFF000000.toInt()
    var argb by remember { mutableIntStateOf(start) }
    // HSV kept apart from the colour so the hue survives saturation or brightness at 0.
    var hsv by remember { mutableStateOf(ColorMath.toHsv(start)) }
    val initialFocus = rememberInitialFocus()
    val startsOnPreset = start in presets

    fun take(color: Int) {
        argb = color
        hsv = ColorMath.toHsv(color)
    }
    fun adjust(next: ColorMath.Hsv) {
        hsv = next
        argb = ColorMath.fromHsv(next)
    }

    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(Color(argb)))
                    TagChip(sample, emphasized = strong, colors = palette.tint(Color(argb), strong))
                    Text(ColorMath.toHex(argb), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.forEach { color ->
                        val source = rememberFocusSource()
                        val selected = color == argb
                        Box(
                            modifier = (if (startsOnPreset && color == start) Modifier.focusRequester(initialFocus) else Modifier)
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(color))
                                .border(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                                .focusRing(source, 15.dp)
                                .clickable(interactionSource = source, indication = null) { take(color) }
                        )
                    }
                }
                AdjustRow(
                    label = stringResource(R.string.color_picker_hue),
                    value = "${hsv.hue.roundToInt()}°",
                    onAdjust = { adjust(hsv.copy(hue = ((hsv.hue + it * HUE_STEP) % 360f + 360f) % 360f)) },
                    modifier = if (startsOnPreset) Modifier else Modifier.focusRequester(initialFocus)
                )
                AdjustRow(
                    label = stringResource(R.string.color_picker_saturation),
                    value = "${(hsv.saturation * 100).roundToInt()} %",
                    onAdjust = { adjust(hsv.copy(saturation = (hsv.saturation + it * LEVEL_STEP).coerceIn(0f, 1f))) }
                )
                AdjustRow(
                    label = stringResource(R.string.color_picker_brightness),
                    value = "${(hsv.value * 100).roundToInt()} %",
                    onAdjust = { adjust(hsv.copy(value = (hsv.value + it * LEVEL_STEP).coerceIn(0f, 1f))) }
                )
            }
        },
        confirmButton = {
            DialogButton(text = stringResource(R.string.dialog_confirm), onClick = { onPick(argb); onDismiss() })
        },
        dismissButton = {
            Row {
                DialogButton(text = stringResource(R.string.color_picker_default), onClick = { onPick(null); onDismiss() })
                DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss)
            }
        }
    )
}

/** One focusable row: D-pad ◀ ▶ (or the arrows, by touch) step its value. */
@Composable
private fun AdjustRow(label: String, value: String, onAdjust: (Int) -> Unit, modifier: Modifier = Modifier) {
    val source = rememberFocusSource()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .focusRing(source)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onAdjust(-1); true }
                    Key.DirectionRight -> { onAdjust(1); true }
                    else -> false
                }
            }
            .clickable(interactionSource = source, indication = null) { onAdjust(1) }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        StepArrow("◀") { onAdjust(-1) }
        Text(value, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, modifier = Modifier.width(64.dp))
        StepArrow("▶") { onAdjust(1) }
    }
}

/** Touch-only arrow: the row it sits in already answers the D-pad, so it never takes focus. */
@Composable
private fun StepArrow(glyph: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, style = MaterialTheme.typography.labelMedium)
    }
}

private const val HUE_STEP = 10f
private const val LEVEL_STEP = 0.05f
