package com.cortinadev.dogmatix.ui.screens.sources.components

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.ScanKind
import com.cortinadev.dogmatix.data.service.ScanReport
import com.cortinadev.dogmatix.data.service.ScanReportEntry
import com.cortinadev.dogmatix.data.service.SourceScanStatus
import com.cortinadev.dogmatix.data.state.ScanProgress
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.components.scanEtaText
import com.cortinadev.dogmatix.ui.components.scanPercentText
import com.cortinadev.dogmatix.ui.theme.LocalDogmatixTokens
import com.cortinadev.dogmatix.util.ScanFailure
import com.cortinadev.dogmatix.util.SourceLabels

/**
 * "Rescan all" (incremental, the main one) and "Full rescan" (every source read again) side by
 * side. While a scan runs they stay focusable but do nothing, so the D-pad focus is not lost.
 */
@Composable
fun ScanActions(isRescanning: Boolean, onRescan: () -> Unit, onFullRescan: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ScanButton(stringResource(R.string.sources_rescan_all), R.drawable.ic_retry, prominent = true, busy = isRescanning, onClick = onRescan)
        ScanButton(stringResource(R.string.scan_full), null, prominent = false, busy = isRescanning, onClick = onFullRescan)
    }
}

@Composable
private fun ScanButton(label: String, icon: Int?, prominent: Boolean, busy: Boolean, onClick: () -> Unit) {
    val source = rememberFocusSource()
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(shape)
            .then(if (prominent) Modifier.background(scheme.surfaceContainerHigh) else Modifier.border(1.dp, scheme.outlineVariant, shape))
            .focusRing(source)
            .clickable(interactionSource = source, indication = null) { if (!busy) onClick() }
            .alpha(if (busy) 0.5f else 1f)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/**
 * The scan line under the actions: while scanning, a flat bar with the percentage, the time left
 * and what is being read; afterwards, when the last scan ran and how it went (A opens its report).
 * One focusable card for both, so the D-pad focus stays put when a scan starts or ends.
 */
@Composable
fun ScanStatusCard(isRescanning: Boolean, progress: ScanProgress?, message: String, report: ScanReport?, onOpenReport: () -> Unit) {
    val source = rememberFocusSource()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(LocalDogmatixTokens.current.card)
            .focusRing(source)
            .clickable(interactionSource = source, indication = null) { if (!isRescanning && report != null) onOpenReport() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isRescanning || report == null) ScanProgressContent(progress, message, Modifier.weight(1f))
        else LastScanContent(report, Modifier.weight(1f))
    }
}

@Composable
private fun ScanProgressContent(progress: ScanProgress?, message: String, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(stringResource(R.string.scan_progress_title), progress?.let { scanPercentText(it.fraction) }).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            progress?.etaMs?.let {
                Text(scanEtaText(it), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        val barModifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).height(4.dp)
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = barModifier,
                color = scheme.primary,
                trackColor = scheme.surfaceContainerHigh,
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
                drawStopIndicator = {}
            )
        } else {
            LinearProgressIndicator(modifier = barModifier, color = scheme.primary, trackColor = scheme.surfaceContainerHigh, strokeCap = StrokeCap.Butt, gapSize = 0.dp)
        }
        if (message.isNotEmpty()) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LastScanContent(report: ScanReport, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        Text(stringResource(R.string.scan_last_title, whenAndKind(report)), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            summaryLine(report),
            style = MaterialTheme.typography.bodySmall,
            color = if (report.failed > 0) scheme.error else scheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
    Text("›", style = MaterialTheme.typography.titleLarge, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
}

/**
 * Source by source: failed first (with the reason), then updated, then unchanged, each with its
 * files. The rows take the D-pad focus so a long report scrolls; Close starts focused, B closes.
 */
@Composable
fun ScanReportDialog(report: ScanReport, canRetry: Boolean, onRetryFailed: () -> Unit, onDismiss: () -> Unit) {
    val closeFocus = rememberInitialFocus()
    val maxListHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scan_report_title)) },
        text = {
            Column {
                Text(whenAndKind(report), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(summaryLine(report), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                if (report.entries.isEmpty()) {
                    Text(stringResource(R.string.scan_report_empty), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                } else {
                    LazyColumn(
                        modifier = Modifier.padding(top = 8.dp).heightIn(max = maxListHeight),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(report.entries, key = { it.consoleId + "|" + it.url }) { ReportRow(it) }
                    }
                }
            }
        },
        confirmButton = {
            DialogButton(stringResource(R.string.dialog_close), onClick = onDismiss, initialFocus = closeFocus)
        },
        dismissButton = if (report.failed > 0 && canRetry) {
            { DialogButton(stringResource(R.string.scan_report_retry_failed), onClick = { onRetryFailed(); onDismiss() }) }
        } else null
    )
}

@Composable
private fun ReportRow(entry: ScanReportEntry) {
    val source = rememberFocusSource()
    val scheme = MaterialTheme.colorScheme
    val (statusText, statusColor) = when (entry.status) {
        SourceScanStatus.FAILED -> stringResource(R.string.scan_status_failed) to scheme.error
        SourceScanStatus.UPDATED -> stringResource(R.string.scan_status_updated) to scheme.primary
        SourceScanStatus.UNCHANGED -> stringResource(R.string.scan_status_unchanged) to scheme.onSurfaceVariant
    }
    val outcome = if (entry.status == SourceScanStatus.FAILED) failureText(entry) else buildList {
        add(pluralStringResource(R.plurals.scan_files_count, entry.files, entry.files))
        if (entry.newFiles > 0) add(pluralStringResource(R.plurals.scan_new_files_count, entry.newFiles, entry.newFiles))
        entry.servedBy?.let { add(stringResource(R.string.scan_via_mirror, SourceLabels.short(it))) }
    }.joinToString(" · ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .focusRing(source, 6.dp)
            .focusable(interactionSource = source)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.consoleName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(statusText, style = MaterialTheme.typography.labelMedium, color = statusColor, modifier = Modifier.padding(start = 8.dp))
        }
        Text(SourceLabels.short(entry.url), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            outcome,
            style = MaterialTheme.typography.bodySmall,
            color = if (entry.status == SourceScanStatus.FAILED) scheme.error else scheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun failureText(entry: ScanReportEntry): String {
    val reason = stringResource(
        when (entry.failure) {
            ScanFailure.RATE_LIMITED -> R.string.scan_failure_rate_limited
            ScanFailure.SERVER -> R.string.scan_failure_server
            ScanFailure.TIMEOUT -> R.string.scan_failure_timeout
            ScanFailure.CONNECTION -> R.string.scan_failure_connection
            ScanFailure.OFFLINE -> R.string.scan_failure_offline
            ScanFailure.SECURE -> R.string.scan_failure_secure
            ScanFailure.NOT_FOUND -> R.string.scan_failure_not_found
            ScanFailure.DENIED -> R.string.scan_failure_denied
            ScanFailure.HTTP -> R.string.scan_failure_http
            ScanFailure.NO_LISTING -> R.string.scan_failure_no_listing
            ScanFailure.EMPTY -> R.string.scan_failure_empty
            ScanFailure.TORRENT_METADATA -> R.string.scan_failure_torrent_metadata
            ScanFailure.OTHER, null -> R.string.scan_failure_other
        }
    )
    val withCode = entry.httpCode?.let { stringResource(R.string.scan_failure_with_code, reason, it) } ?: reason
    // Errors we have no words for (RomM set-up, odd answers) carry their own message.
    return if ((entry.failure == ScanFailure.OTHER || entry.failure == null) && !entry.detail.isNullOrBlank()) "$withCode: ${entry.detail}" else withCode
}

@Composable
private fun whenAndKind(report: ScanReport): String {
    val context = LocalContext.current
    val time = DateUtils.formatDateTime(
        context, report.finishedAt,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
    )
    val kind = stringResource(
        when (report.kind) {
            ScanKind.MANUAL -> R.string.scan_kind_manual
            ScanKind.FULL -> R.string.scan_kind_full
            ScanKind.CONSOLE -> R.string.scan_kind_console
            ScanKind.AUTO -> R.string.scan_kind_auto
            ScanKind.RETRY -> R.string.scan_kind_retry
            ScanKind.NEW_SOURCES -> R.string.scan_kind_new_sources
        }
    )
    return "$time · $kind"
}

@Composable
private fun summaryLine(report: ScanReport): String = buildList {
    add(pluralStringResource(R.plurals.scan_sources_count, report.entries.size, report.entries.size))
    if (report.updated > 0) add(pluralStringResource(R.plurals.scan_updated_count, report.updated, report.updated))
    if (report.unchanged > 0) add(pluralStringResource(R.plurals.scan_unchanged_count, report.unchanged, report.unchanged))
    if (report.failed > 0) add(pluralStringResource(R.plurals.scan_failed_count, report.failed, report.failed))
    if (report.newFiles > 0) add(pluralStringResource(R.plurals.scan_new_files_count, report.newFiles, report.newFiles))
}.joinToString(" · ")
