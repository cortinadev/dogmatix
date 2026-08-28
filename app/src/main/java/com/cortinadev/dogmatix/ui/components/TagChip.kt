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

@Composable
fun TagChip(text: String, emphasized: Boolean = false, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (emphasized) scheme.onSurface else scheme.secondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(
                if (emphasized) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh,
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}

/** Console chip first (emphasized), then the file's tags and extension. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagRow(
    console: String,
    tags: List<String>,
    extension: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        maxLines = maxLines
    ) {
        TagChip(console, emphasized = true)
        tags.forEach { TagChip(it) }
        if (extension.isNotBlank()) TagChip(extension.trimStart('.').uppercase())
    }
}
