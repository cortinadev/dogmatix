package com.cortinadev.dogmatix.ui.screens.collection.toolkit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.MovePhase
import com.cortinadev.dogmatix.data.service.MoveProblem
import com.cortinadev.dogmatix.data.service.MoveProgress
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.util.ActionTypes

/**
 * The move in progress (or the last one) for Smart storage and Move the library: a flat bar with
 * files and bytes, the file being copied, and the actions of its state — Cancel while it runs,
 * Resume / Abandon once interrupted, OK when it ended. [firstAction] takes the pane's entry focus.
 */
@Composable
fun MoveProgressBlock(
    progress: MoveProgress,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    onAbandon: () -> Unit,
    onDismiss: () -> Unit,
    firstAction: FocusRequester? = null
) {
    var confirmAbandon by remember { mutableStateOf(false) }
    if (confirmAbandon) {
        ToolConfirmDialog(
            title = stringResource(R.string.move_abandon_title),
            message = stringResource(R.string.move_abandon_message),
            confirmText = stringResource(R.string.move_abandon),
            onConfirm = onAbandon,
            onDismiss = { confirmAbandon = false }
        )
    }
    val scheme = MaterialTheme.colorScheme
    val kind = stringResource(if (progress.job.type == ActionTypes.SMART_STORAGE) R.string.history_type_smart_storage else R.string.history_type_library_move)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (progress.job.undoOf != null) stringResource(R.string.move_title_back, kind, progress.job.subject)
            else stringResource(R.string.history_title_format, kind, progress.job.subject),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val status = when (progress.phase) {
            MovePhase.PREPARING -> stringResource(R.string.move_preparing)
            MovePhase.MOVING -> stringResource(R.string.move_progress, progress.filesDone, progress.filesTotal, formatBytes(progress.bytesDone), formatBytes(progress.bytesTotal))
            MovePhase.FINISHING -> stringResource(R.string.move_finishing)
            MovePhase.DONE -> stringResource(R.string.move_done, progress.filesTotal, formatBytes(progress.bytesTotal))
            MovePhase.INTERRUPTED -> stringResource(R.string.move_interrupted)
            MovePhase.FAILED -> when (progress.problem) {
                MoveProblem.NO_ROOM -> stringResource(R.string.move_no_room, formatBytes(progress.needBytes), formatBytes(progress.freeBytes))
                MoveProblem.OVERLAP -> stringResource(R.string.move_overlap)
                MoveProblem.BUSY -> stringResource(R.string.move_busy)
                MoveProblem.PERMISSION_LOST -> stringResource(R.string.move_permission_lost)
                MoveProblem.VOLUME_GONE -> stringResource(R.string.move_volume_gone)
                MoveProblem.CANNOT_OPEN, null -> stringResource(R.string.move_failed)
            }
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = if (progress.phase == MovePhase.FAILED) scheme.error else scheme.onSurfaceVariant)
        if (progress.running || progress.phase == MovePhase.INTERRUPTED) {
            UsageBar(if (progress.bytesTotal > 0) progress.bytesDone.toFloat() / progress.bytesTotal else 0f)
        }
        if (progress.running && progress.current.isNotEmpty()) {
            Text(progress.current, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (progress.conflicts > 0 || progress.failures > 0) {
            Text(
                pluralStringResource(R.plurals.move_left_behind, progress.conflicts + progress.failures, progress.conflicts + progress.failures),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error
            )
        }
        if (progress.unverified > 0) {
            Text(
                pluralStringResource(R.plurals.move_unverified, progress.unverified, progress.unverified),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error
            )
        }
        if (progress.running) {
            Text(stringResource(R.string.move_downloads_paused), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
    ToolButtons {
        when {
            progress.running -> ToolButton(stringResource(R.string.dialog_cancel), onCancel, focusRequester = firstAction)
            progress.phase == MovePhase.INTERRUPTED ||
                progress.phase == MovePhase.FAILED && (progress.problem == MoveProblem.CANNOT_OPEN || progress.canResume) -> {
                ToolButton(stringResource(R.string.move_resume), onResume, focusRequester = firstAction)
                ToolButton(stringResource(R.string.move_abandon), { confirmAbandon = true })
            }
            else -> ToolButton(stringResource(android.R.string.ok), onDismiss, focusRequester = firstAction)
        }
    }
}
