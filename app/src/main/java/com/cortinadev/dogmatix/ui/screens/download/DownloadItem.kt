package com.cortinadev.dogmatix.ui.screens.download

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.cortinadev.dogmatix.data.service.UploadState
import com.cortinadev.dogmatix.data.service.UploadStatus
import com.cortinadev.dogmatix.ui.components.TagRow
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.stripExtension
import com.cortinadev.dogmatix.util.ConsoleFormatter
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * One download. [details] is the indexed file behind it (console + tags) so two
 * versions of the same game can be told apart; null when it is no longer indexed.
 */
@Composable
fun DownloadItem(
    item: DownloadItemModel,
    details: DownloadableFileWithTags?,
    compact: Boolean,
    viewModel: DownloadViewModel,
    modifier: Modifier = Modifier,
    upload: UploadState? = null,
    onRowFocused: (DownloadItemModel) -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val source = rememberFocusSource()
    val status = item.status
    val statusColor = when (status) {
        DownloadStatus.COMPLETED -> scheme.tertiary
        DownloadStatus.FAILED -> scheme.error
        DownloadStatus.STOPPED -> scheme.onSurfaceVariant
        else -> scheme.primary
    }
    val statusIcon = when (status) {
        DownloadStatus.COMPLETED -> R.drawable.ic_check
        DownloadStatus.FAILED -> R.drawable.ic_error
        DownloadStatus.STOPPED -> R.drawable.ic_stop
        DownloadStatus.COPYING -> R.drawable.ic_folder
        DownloadStatus.UNZIPPING -> R.drawable.ic_extract
        DownloadStatus.DOWNLOADING -> R.drawable.ic_arrow_down
        DownloadStatus.QUEUED -> R.drawable.ic_web
    }
    val statusLabel = when (status) {
        DownloadStatus.QUEUED -> stringResource(R.string.status_queued_debrid, viewModel.debridLabel.collectAsState().value, (item.progress * 100).toInt())
        DownloadStatus.COMPLETED -> stringResource(R.string.status_completed)
        DownloadStatus.FAILED -> stringResource(R.string.status_failed)
        DownloadStatus.STOPPED -> stringResource(R.string.status_stopped)
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
    val detail = when (status) {
        DownloadStatus.DOWNLOADING -> stringResource(R.string.download_speed, item.downloadSpeed) +
            "  ·  ${formatBytes(item.downloadedBytes)} / ${formatBytes(item.fileSize)}"
        else -> formatBytes(item.fileSize)
    } + (timeLabel?.let { "  ·  $it" } ?: "")
    val busy = status == DownloadStatus.COPYING || status == DownloadStatus.UNZIPPING ||
        (status == DownloadStatus.QUEUED && item.progress <= 0f)
    val actionSize: Dp = if (compact) 36.dp else 44.dp

    // A (or a tap) on the row runs the primary action; the side buttons stay for touch and
    // are skipped by D-pad focus search (they sit inside the focused row's bounds).
    val primaryAction: () -> Unit = {
        when (status) {
            DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.UNZIPPING ->
                scope.launch { viewModel.cancelDownload(item.fileName) }
            DownloadStatus.COMPLETED, DownloadStatus.STOPPED, DownloadStatus.FAILED ->
                scope.launch { viewModel.retryDownload(item.fileName) }
            DownloadStatus.COPYING -> Unit
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .focusRing(source)
            .onFocusChanged { if (it.isFocused) onRowFocused(item) }
            .clickable(interactionSource = source, indication = null, onClick = primaryAction)
            .defaultMinSize(minHeight = if (compact) 70.dp else 88.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(scheme.surfaceContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painterResource(statusIcon),
                contentDescription = status.name,
                tint = statusColor,
                modifier = Modifier.size(14.dp)
            )
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
            upload?.let { up ->
                val (label, color) = when (up.status) {
                    UploadStatus.UPLOADING -> stringResource(R.string.romm_upload_progress, (up.progress * 100).toInt()) to scheme.primary
                    UploadStatus.DONE -> stringResource(R.string.romm_uploaded) to scheme.tertiary
                    UploadStatus.FAILED -> stringResource(R.string.romm_upload_failed, up.message) to scheme.error
                }
                Text(label, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            details?.let {
                TagRow(
                    console = ConsoleFormatter.getConsoleShortName(it.file.consoleId),
                    tags = it.tags,
                    extension = it.file.fileExtension,
                    maxLines = if (compact) 1 else Int.MAX_VALUE
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (status) {
                DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.UNZIPPING -> ActionButton(R.drawable.ic_stop, stringResource(R.string.download_cancel), actionSize, scheme.onSurface) {
                    scope.launch { viewModel.cancelDownload(item.fileName) }
                }
                DownloadStatus.COPYING -> Unit
                DownloadStatus.COMPLETED, DownloadStatus.STOPPED, DownloadStatus.FAILED -> {
                    if (upload?.status == UploadStatus.FAILED) {
                        ActionButton(R.drawable.ic_arrow_up, stringResource(R.string.romm_upload_retry), actionSize, scheme.primary) {
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

@Composable
private fun ActionButton(icon: Int, description: String, size: Dp, tint: Color, onClick: () -> Unit) {
    val source = rememberFocusSource()
    Box(
        modifier = Modifier
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
