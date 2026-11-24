package com.cortinadev.dogmatix.ui.screens.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.NotConvertible
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.util.RomConversions

/**
 * "Convert to XISO?" (or RVZ, or "XISO / RVZ" for a mixed selection): each game found on disk with its size now and after, or why it stays as it
 * is (already converted, not found…), where the original goes and what it saves. The focus starts
 * on Cancel (it is there while the images are read, and stays); B closes.
 */
@Composable
internal fun ConversionDialog(prompt: DownloadViewModel.ConversionPrompt, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val ready = prompt.ready
    // One label, or "XISO / RVZ" when the ticked rows convert to different formats.
    val format = RomConversions.labels(ready.mapNotNull { it.target?.format }).takeIf { it.isNotEmpty() }
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (format != null) pluralStringResource(R.plurals.conversion_confirm_title, ready.size, ready.size, format)
                else stringResource(R.string.conversion_convert)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (prompt.loading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.conversion_confirm_loading), style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Lines(prompt)
                }
            }
        },
        confirmButton = {
            if (!prompt.loading && ready.isNotEmpty()) DialogButton(stringResource(R.string.conversion_convert), onClick = onConfirm)
        },
        dismissButton = {
            DialogButton(
                stringResource(if (!prompt.loading && ready.isEmpty()) R.string.dialog_close else R.string.dialog_cancel),
                onClick = onDismiss,
                initialFocus = rememberInitialFocus()
            )
        }
    )
}

@Composable
private fun Lines(prompt: DownloadViewModel.ConversionPrompt) {
    val ready = prompt.ready
    prompt.items.take(MAX_LINES).forEach { item ->
        val target = item.target
        val inspection = item.inspection
        val line = when {
            target == null -> stringResource(R.string.conversion_not_found_line, item.title)
            inspection?.convertible == true -> inspection.outputBytes?.let {
                stringResource(R.string.conversion_confirm_line, item.title, formatBytes(target.inputBytes), formatBytes(it))
            } ?: stringResource(R.string.conversion_confirm_skip, item.title, formatBytes(target.inputBytes))
            else -> stringResource(R.string.conversion_confirm_skip, item.title, reasonText(inspection?.reason))
        }
        Text(line, style = MaterialTheme.typography.bodyMedium)
    }
    (prompt.items.size - MAX_LINES).takeIf { it > 0 }?.let {
        Text(stringResource(R.string.collection_and_more, it), style = MaterialTheme.typography.bodyMedium)
    }
    if (ready.isEmpty()) {
        Text(stringResource(R.string.conversion_confirm_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text(stringResource(R.string.conversion_confirm_trash), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val saved = ready.sumOf { RomConversions.savedBytes(it.target!!.inputBytes, it.inspection?.outputBytes) ?: 0L }
        if (saved > 0) Text(stringResource(R.string.conversion_confirm_saves, formatBytes(saved)), style = MaterialTheme.typography.bodyMedium)
        if (ready.any { RomConversions.losesDatMatch(it.target!!.main.name) }) {
            Text(stringResource(R.string.conversion_confirm_wbfs), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun reasonText(reason: NotConvertible?): String = stringResource(
    when (reason) {
        NotConvertible.ALREADY_CONVERTED -> R.string.conversion_error_already
        NotConvertible.DAMAGED -> R.string.conversion_error_damaged
        NotConvertible.INCOMPLETE -> R.string.conversion_error_incomplete
        NotConvertible.UNRECOGNISED, null -> R.string.conversion_error_unrecognised
    }
)

private const val MAX_LINES = 6
