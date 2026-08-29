package com.cortinadev.dogmatix.ui.components

import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.theme.StatusDanger
import com.cortinadev.dogmatix.ui.theme.StatusInfo
import com.cortinadev.dogmatix.ui.theme.StatusSuccess

data class LegendEntry(val key: String, val label: String)

private val keyColors = mapOf(
    "A" to StatusSuccess,
    "B" to StatusDanger,
    "X" to StatusInfo,
    "Y" to Color(0xFFF5C400)
)

/** Bottom strip listing what each gamepad button does on the current screen. */
@Composable
fun GamepadLegend(entries: List<LegendEntry>, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    HorizontalDivider(color = scheme.outlineVariant, thickness = 1.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Narrow (4:3) screens cannot fit every entry: keep each on one line and let the strip scroll.
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        entries.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val color = keyColors[entry.key] ?: scheme.outline
                KeyGlyph(entry.key, color, if (keyColors.containsKey(entry.key)) color else scheme.onSurface)
                Text(entry.label, style = MaterialTheme.typography.bodySmall, color = scheme.secondary, softWrap = false)
            }
        }
        }
        if (trailing != null) trailing()
    }
}

/**
 * Rounded pill with the button name. The glyph is drawn by hand centred on its ink bounds
 * (Paint.getTextBounds) rather than on the font's line box, which sits visibly off-centre.
 */
@Composable
private fun KeyGlyph(text: String, ring: Color, ink: Color) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val paint = remember(text, density) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.manrope_variable)
            fontVariationSettings = "'wght' 600"
            textSize = with(density) { 10.5.sp.toPx() }
            letterSpacing = 0.02f
        }
    }
    val bounds = remember(paint, text) { Rect().also { paint.getTextBounds(text, 0, text.length, it) } }
    val minSide = 20.dp
    val width = with(density) { maxOf(minSide.toPx(), bounds.width() + 10.dp.toPx()) }
    val height = with(density) { minSide.toPx() }
    val inkColor = ink.toArgb()
    Canvas(
        modifier = Modifier
            .size(with(density) { width.toDp() }, minSide)
            .border(1.5.dp, ring, RoundedCornerShape(10.dp))
    ) {
        drawIntoCanvas { canvas ->
            paint.color = inkColor
            // Shift so the ink box, not the baseline box, is centred in the pill.
            val x = width / 2f - bounds.exactCenterX()
            val y = height / 2f - bounds.exactCenterY()
            canvas.nativeCanvas.drawText(text, x, y, paint)
        }
    }
}

/** "23.4 GB free" for the status strip; hidden when unknown. */
@Composable
fun FreeSpaceText(freeBytes: Long?) {
    if (freeBytes == null) return
    Text(
        stringResource(R.string.free_space, formatBytes(freeBytes)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1
    )
}
