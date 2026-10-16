package com.cortinadev.dogmatix.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A small label; [colors] tints it (console colour, tag group), otherwise the plain neutral look. */
@Composable
fun TagChip(text: String, emphasized: Boolean = false, modifier: Modifier = Modifier, colors: ChipColors? = null) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = colors?.content ?: if (emphasized) scheme.onSurface else scheme.secondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(
                colors?.background ?: if (emphasized) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh,
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

/**
 * Console chip first (emphasized; in its console's colour when [consoleId] is given), then the
 * file's tags in the colour of their group, and the extension in neutral grey.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagRow(
    console: String,
    tags: List<String>,
    extension: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    consoleId: String? = null
) {
    val palette = tagPalette()
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        maxLines = maxLines
    ) {
        TagChip(console, emphasized = true, colors = consoleId?.let(palette::console))
        tags.forEach { TagChip(it, colors = palette.tag(it)) }
        if (extension.isNotBlank()) TagChip(extension.trimStart('.').uppercase())
    }
}
