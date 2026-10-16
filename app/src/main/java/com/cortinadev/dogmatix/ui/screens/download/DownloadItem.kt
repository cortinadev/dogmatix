package com.cortinadev.dogmatix.ui.screens.download

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.model.DownloadableFileWithTags
import com.cortinadev.dogmatix.data.model.LibraryGame
import com.cortinadev.dogmatix.data.service.UploadState
import com.cortinadev.dogmatix.data.service.UploadStatus
import com.cortinadev.dogmatix.ui.components.checkLabel
import com.cortinadev.dogmatix.ui.components.mismatchText
import com.cortinadev.dogmatix.ui.components.rememberCheckResult
import com.cortinadev.dogmatix.util.CheckState
import com.cortinadev.dogmatix.ui.components.GameCover
import com.cortinadev.dogmatix.ui.components.TagRow
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.stripExtension
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.DownloadFailure
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * One download. [details] is the indexed file behind it (console + tags) so two
 * versions of the same game can be told apart; null when it is no longer indexed. With
 * [showCover], the square cover of its game ([game]) goes in front, as in the Library.
 *
 * While [selectionMode] is on the row is a checkbox: A / a tap ticks it instead of
 * running its action, and the per-row buttons step aside for the selection bar.
 *
 * A row waiting in the download queue shows its turn ([queueTurn]) or that it is [held]; while
 * it is [moving] (reorder mode) its buttons are the queue ones: up, down, first, hold, done.
 */
@Composable
fun DownloadItem(
    item: DownloadItemModel,
    details: DownloadableFileWithTags?,
    compact: Boolean,
    viewModel: DownloadViewModel,
    modifier: Modifier = Modifier,
    upload: UploadState? = null,
    game: LibraryGame? = null,
    showCover: Boolean = false,
    /** Why a FAILED row failed, and when it will be retried on its own (null = it will not). */
    failure: DownloadFailure? = null,
    retryAt: Long? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    /** Where ▲ goes from this row: the selection bar sits off-centre, so focus search never picks it. */
    focusUp: FocusRequester? = null,
    onToggleSelection: () -> Unit = {},
    onRowFocused: (DownloadItemModel, Boolean) -> Unit = { _, _ -> },
    // dl-cola
    /** Turn in line (1 = next to start) while it waits, not held. */
    queueTurn: Int? = null,
    held: Boolean = false,
    moving: Boolean = false,
    queueActions: QueueRowActions? = null
) {
    val queued = queueTurn != null || held
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val source = rememberFocusSource()
    val status = item.status
    val statusColor = if (queued) scheme.onSurfaceVariant else when (status) {
        DownloadStatus.COMPLETED -> scheme.tertiary
        DownloadStatus.FAILED -> scheme.error
        DownloadStatus.STOPPED, DownloadStatus.PAUSED -> scheme.onSurfaceVariant
        else -> scheme.primary
    }
    val statusIcon = if (moving) R.drawable.ic_reorder else if (held) R.drawable.ic_hold else when (status) {
        DownloadStatus.COMPLETED -> R.drawable.ic_check
        DownloadStatus.FAILED -> R.drawable.ic_error
        DownloadStatus.STOPPED -> R.drawable.ic_stop
        DownloadStatus.PAUSED -> R.drawable.ic_pause
        DownloadStatus.COPYING -> R.drawable.ic_folder
        DownloadStatus.UNZIPPING -> R.drawable.ic_extract
        DownloadStatus.DOWNLOADING -> R.drawable.ic_arrow_down
        DownloadStatus.QUEUED -> R.drawable.ic_web
    }
    val statusLabel = if (held) stringResource(R.string.status_held)
    else if (queueTurn != null) stringResource(R.string.status_waiting, queueTurn)
    else when (status) {
        DownloadStatus.QUEUED -> stringResource(R.string.status_queued_debrid, viewModel.debridLabel.collectAsState().value, (item.progress * 100).toInt())
        DownloadStatus.COMPLETED -> stringResource(R.string.status_completed)
        DownloadStatus.FAILED -> retryAt?.let {
            stringResource(R.string.status_retry_at, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)))
        } ?: stringResource(R.string.status_failed)
        DownloadStatus.STOPPED -> stringResource(R.string.status_stopped)
        DownloadStatus.PAUSED -> stringResource(R.string.status_paused)
        DownloadStatus.COPYING -> stringResource(R.string.status_copying)
        DownloadStatus.UNZIPPING -> stringResource(R.string.status_extracting)
        DownloadStatus.DOWNLOADING -> stringResource(R.string.status_downloading, (item.progress * 100).toInt())
    }
    val timestamp = remember(item.startedAt, item.finishedAt, status) {
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val at = if (item.isFinished) item.finishedAt ?: item.startedAt else item.startedAt
        if (at > 0L) fmt.format(Date(at)) else null
    }
    val timeLabel = timestamp?.let {
        if (item.isFinished) stringResource(R.string.download_finished_at, it)
        else stringResource(R.string.download_started_at, it)
    }
    val failureLabel = if (status == DownloadStatus.FAILED) failure?.let { stringResource(it.labelRes) } else null
    // Checksum / DAT verdict, only for the file on disk now (a new download hides the old one).
    val check = rememberCheckResult(item.fileName)?.takeIf { status == DownloadStatus.COMPLETED }
    val checkText = check?.let { checkLabel(it) }
    val detail = when (if (queued) null else status) {
        DownloadStatus.DOWNLOADING -> stringResource(R.string.download_speed, item.downloadSpeed) +
            "  ·  ${formatBytes(item.downloadedBytes)} / ${formatBytes(item.fileSize)}"
        else -> listOfNotNull(failureLabel, formatBytes(item.fileSize), checkText).joinToString("  ·  ")
    } + (timeLabel?.let { "  ·  $it" } ?: "")
    val busy = status == DownloadStatus.COPYING || status == DownloadStatus.UNZIPPING ||
        (status == DownloadStatus.QUEUED && item.progress <= 0f)
    val actionSize: Dp = if (compact) 36.dp else 44.dp

    // A (or a tap) on the row runs the primary action; the side buttons stay for touch and
    // are skipped by D-pad focus search (they sit inside the focused row's bounds).
    // Every running transfer pauses: web downloads keep their partial file, torrents their session.
    val primaryAction: () -> Unit = {
        when {
            // A held row is let go: it starts when its turn comes.
            held -> queueActions?.onToggleHold?.invoke()
            status == DownloadStatus.DOWNLOADING -> viewModel.pauseDownload(item.fileName)
            status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING || status == DownloadStatus.UNZIPPING ->
                scope.launch { viewModel.cancelDownload(item.fileName) }
            status == DownloadStatus.COMPLETED || status == DownloadStatus.STOPPED ||
            status == DownloadStatus.FAILED || status == DownloadStatus.PAUSED ->
                scope.launch { viewModel.retryDownload(item.fileName) }
            else -> Unit
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected || moving) scheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .focusRing(source)
            .focusProperties { focusUp?.let { up = it } }
            .onFocusChanged { onRowFocused(item, it.isFocused) }
            .combinedClickable(
                interactionSource = source,
                indication = null,
                onClick = { if (selectionMode) onToggleSelection() else primaryAction() },
                // Long press is how touch enters selection mode (the pad uses SELECT).
                onLongClick = onToggleSelection
            )
            .defaultMinSize(minHeight = if (compact) 70.dp else 88.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // The status also reads as text on the right, so the badge doubles as the checkbox.
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (selectionMode && selected) scheme.primary else scheme.surfaceContainer)
                .then(
                    if (selectionMode && !selected) Modifier.border(1.dp, scheme.outlineVariant, RoundedCornerShape(8.dp))
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            when {
                selectionMode && selected -> Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = stringResource(R.string.selection_selected),
                    tint = scheme.onPrimary,
                    modifier = Modifier.size(14.dp)
                )
                selectionMode -> Unit
                else -> Icon(
                    painterResource(statusIcon),
                    contentDescription = status.name,
                    tint = statusColor,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        if (showCover) {
            val coverSize = if (compact) 56.dp else 48.dp
            when {
                game != null -> GameCover(game, coverSize)
                details != null -> GameCover(details.file.consoleId, details.file.fileName, details.file.name, coverSize)
                // No longer indexed: an empty square keeps the names lined up.
                else -> Box(Modifier.size(coverSize).clip(RoundedCornerShape(coverSize / 6)).background(scheme.surfaceContainer))
            }
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stripExtension(item.name),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(statusLabel, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
            }
            if (busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = statusColor,
                    trackColor = scheme.surfaceContainerHigh,
                    strokeCap = StrokeCap.Round
                )
            } else {
                LinearProgressIndicator(
                    progress = { if (status == DownloadStatus.COMPLETED) 1f else item.progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = statusColor,
                    trackColor = scheme.surfaceContainerHigh,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {}
                )
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
            check?.mismatch?.takeIf { check.state == CheckState.MISMATCH }?.let { mismatch ->
                Text(mismatchText(mismatch), style = MaterialTheme.typography.bodySmall, color = scheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            upload?.let { up ->
                val percent = (up.progress * 100).toInt()
                val (label, color) = when (up.status) {
                    UploadStatus.UPLOADING -> stringResource(if (up.resuming) R.string.romm_upload_resuming else R.string.romm_upload_progress, percent) to scheme.primary
                    UploadStatus.PREPARING -> stringResource(R.string.romm_upload_preparing, percent) to scheme.primary
                    UploadStatus.WAITING -> stringResource(R.string.romm_upload_waiting) to scheme.onSurfaceVariant
                    UploadStatus.DONE -> stringResource(R.string.romm_uploaded) to scheme.tertiary
                    UploadStatus.FAILED -> (up.retryAt?.let {
                        stringResource(R.string.romm_upload_retry_at, up.message, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)))
                    } ?: stringResource(R.string.romm_upload_failed, up.message)) to scheme.error
                }
                Text(label, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            details?.let {
                TagRow(
                    console = ConsoleFormatter.getConsoleShortName(it.file.consoleId),
                    tags = it.tags,
                    extension = it.file.fileExtension,
                    maxLines = if (compact) 1 else Int.MAX_VALUE,
                    consoleId = it.file.consoleId
                )
            }
        }

        // The selection bar owns the actions while ticking rows.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!selectionMode && queued && queueActions != null) {
                QueueRowButtons(moving, held, if (moving) 36.dp else actionSize, queueActions) { scope.launch { viewModel.cancelDownload(item.fileName) } }
            } else when (if (selectionMode) null else status) {
                DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.UNZIPPING -> {
                    if (status == DownloadStatus.DOWNLOADING) {
                        ActionButton(R.drawable.ic_pause, stringResource(R.string.download_pause), actionSize, scheme.onSurface) {
                            viewModel.pauseDownload(item.fileName)
                        }
                    }
                    ActionButton(R.drawable.ic_stop, stringResource(R.string.download_cancel), actionSize, scheme.onSurface) {
                        scope.launch { viewModel.cancelDownload(item.fileName) }
                    }
                }
                null, DownloadStatus.COPYING -> Unit
                DownloadStatus.PAUSED -> {
                    ActionButton(R.drawable.ic_play, stringResource(R.string.download_resume), actionSize, scheme.primary) {
                        scope.launch { viewModel.retryDownload(item.fileName) }
                    }
                    ActionButton(R.drawable.ic_trash, stringResource(R.string.download_delete), actionSize, scheme.error) {
                        viewModel.deleteDownloadWithConfirmation(item.fileName, false)
                    }
                }
                DownloadStatus.COMPLETED, DownloadStatus.STOPPED, DownloadStatus.FAILED -> {
                    if (upload?.status == UploadStatus.FAILED || upload?.status == UploadStatus.WAITING) {
                        val description = if (upload.status == UploadStatus.WAITING) R.string.romm_upload_now else R.string.romm_upload_retry
                        ActionButton(R.drawable.ic_arrow_up, stringResource(description), actionSize, scheme.primary) {
                            viewModel.retryUpload(item.fileName)
                        }
                    }
                    ActionButton(R.drawable.ic_retry, stringResource(R.string.download_retry), actionSize, scheme.onSurface) {
                        scope.launch { viewModel.retryDownload(item.fileName) }
                    }
                    ActionButton(R.drawable.ic_trash, stringResource(R.string.download_delete), actionSize, scheme.error) {
                        viewModel.deleteDownloadWithConfirmation(item.fileName, status == DownloadStatus.COMPLETED)
                    }
                }
            }
        }
    }
}

/** What a row waiting in line can do; the screen wires them to the queue. */
class QueueRowActions(
    val onReorder: () -> Unit,
    val onUp: () -> Unit,
    val onDown: () -> Unit,
    val onFirst: () -> Unit,
    val onToggleHold: () -> Unit,
    val onDone: () -> Unit
)

/** Touch buttons of a waiting row: reorder (enters the move mode) or, while moving, the moves themselves. */
@Composable
private fun QueueRowButtons(moving: Boolean, held: Boolean, size: Dp, actions: QueueRowActions, onStop: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val holdIcon = if (held) R.drawable.ic_play else R.drawable.ic_hold
    val holdLabel = stringResource(if (held) R.string.download_queue_release else R.string.download_queue_hold)
    if (moving) {
        ActionButton(R.drawable.ic_arrow_up, stringResource(R.string.download_queue_up), size, scheme.onSurface, onClick = actions.onUp)
        ActionButton(R.drawable.ic_arrow_down, stringResource(R.string.download_queue_down), size, scheme.onSurface, onClick = actions.onDown)
        ActionButton(R.drawable.ic_queue_top, stringResource(R.string.download_queue_next), size, scheme.onSurface, onClick = actions.onFirst)
        ActionButton(holdIcon, holdLabel, size, scheme.onSurface, onClick = actions.onToggleHold)
        ActionButton(R.drawable.ic_check, stringResource(R.string.download_queue_done), size, scheme.primary, onClick = actions.onDone)
    } else {
        ActionButton(R.drawable.ic_reorder, stringResource(R.string.download_queue_reorder), size, scheme.onSurface, onClick = actions.onReorder)
        ActionButton(holdIcon, holdLabel, size, if (held) scheme.primary else scheme.onSurface, onClick = actions.onToggleHold)
        ActionButton(R.drawable.ic_stop, stringResource(R.string.download_cancel), size, scheme.onSurface, onClick = onStop)
    }
}

@Composable
internal fun ActionButton(
    icon: Int,
    description: String,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val source = rememberFocusSource()
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .focusRing(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(icon), contentDescription = description, tint = tint, modifier = Modifier.size(16.dp))
    }
}

/** Short text for each failure reason. */
private val DownloadFailure.labelRes: Int
    get() = when (this) {
        DownloadFailure.NETWORK -> R.string.failure_network
        DownloadFailure.TIMEOUT -> R.string.failure_timeout
        DownloadFailure.SERVER -> R.string.failure_server
        DownloadFailure.RATE_LIMITED -> R.string.failure_rate_limited
        DownloadFailure.NOT_FOUND -> R.string.failure_not_found
        DownloadFailure.DENIED -> R.string.failure_denied
        DownloadFailure.HTTP -> R.string.failure_http
        DownloadFailure.NO_SPACE -> R.string.failure_no_space
        DownloadFailure.STORAGE -> R.string.failure_storage
        DownloadFailure.INCOMPLETE -> R.string.failure_incomplete
        DownloadFailure.TORRENT_METADATA -> R.string.failure_torrent_metadata
        DownloadFailure.TORRENT -> R.string.failure_torrent
        DownloadFailure.DEBRID_ACCOUNT -> R.string.failure_debrid_account
        DownloadFailure.DEBRID -> R.string.failure_debrid
        DownloadFailure.UNKNOWN -> R.string.failure_unknown
        DownloadFailure.WRONG_CONTENT -> R.string.failure_wrong_content
    }
