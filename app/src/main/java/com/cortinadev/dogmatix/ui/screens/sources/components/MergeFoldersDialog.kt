package com.cortinadev.dogmatix.ui.screens.sources.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.FolderMergeService
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource

/**
 * Offered when several folders in the download directory match the same console.
 * The user picks the folder to keep (the rest are merged into it) or leaves everything as is.
 */
@Composable
fun MergeFoldersDialog(
    consoleName: String,
    folders: List<String>,
    inProgress: Boolean,
    result: FolderMergeService.Result?,
    onMerge: (String) -> Unit,
    onKeep: () -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember(folders) { mutableStateOf(folders.firstOrNull()) }

    AlertDialog(
        onDismissRequest = { if (!inProgress) onDismiss() },
        title = { Text(stringResource(R.string.merge_folders_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    result != null -> Text(
                        stringResource(R.string.merge_folders_result, result.moved, result.duplicates, result.skipped, result.failed, result.removedFolders),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    inProgress -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.merge_folders_working), style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> {
                        Text(
                            stringResource(R.string.merge_folders_hint, consoleName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        folders.forEach { folder ->
                            FolderRow(folder, selected == folder) { selected = folder }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                result != null -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.merge_folders_done)) }
                else -> TextButton(
                    onClick = { selected?.let(onMerge) },
                    enabled = !inProgress && selected != null
                ) { Text(stringResource(R.string.merge_folders_confirm)) }
            }
        },
        dismissButton = {
            if (result == null) {
                TextButton(onClick = onKeep, enabled = !inProgress) { Text(stringResource(R.string.merge_folders_keep)) }
            }
        }
    )
}

@Composable
private fun FolderRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(scheme.surfaceContainer)
            .focusRing(source, 6.dp)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(if (selected) scheme.primary else scheme.surfaceContainerHighest)
        )
        Text(
            label,
            style = if (selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface
        )
    }
}
