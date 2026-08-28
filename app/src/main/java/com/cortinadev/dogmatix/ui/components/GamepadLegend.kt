package com.cortinadev.dogmatix.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
        entries.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val color = keyColors[entry.key] ?: scheme.outline
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
                        .border(1.5.dp, color, RoundedCornerShape(10.dp))
                        .padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(entry.key, style = MaterialTheme.typography.labelSmall, color = if (keyColors.containsKey(entry.key)) color else scheme.onSurface)
                }
                Text(entry.label, style = MaterialTheme.typography.bodySmall, color = scheme.secondary)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            trailing()
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
