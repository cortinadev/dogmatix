package com.cortinadev.dogmatix.ui.screens.collection.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.ScanProgress
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.Legend
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.screens.collection.CollectionAnalysis
import com.cortinadev.dogmatix.ui.screens.collection.CollectionNav
import com.cortinadev.dogmatix.ui.screens.collection.CollectionViewModel
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.HealthSeverity

/**
 * Publishes [entries] as the button legend while [active] (the zone holding the focus); clears
 * only its own legend, as the other screens do, so a zone taking over is never wiped.
 */
@Composable
internal fun PublishLegend(entries: List<LegendEntry>, active: Boolean) {
    val published = remember(entries, active) { if (active) Legend(entries) else null }
    LaunchedEffect(published) { if (published != null) Gamepad.legendOverride.value = published }
    DisposableEffect(published) {
        onDispose { if (published != null && Gamepad.legendOverride.value === published) Gamepad.legendOverride.value = null }
    }
}

/** The legend entries every pane ends with. */
@Composable
internal fun paneLegendTail(): List<LegendEntry> = listOf(
    LegendEntry("B", stringResource(R.string.pad_back)),
    LegendEntry("ZL · ZR", stringResource(R.string.pad_section))
)

/** X analyses the library again, or stops the analysis under way (the list, Summary and Health). */
@Composable
internal fun analyzeLegendEntry(viewModel: CollectionViewModel): LegendEntry {
    val progress by viewModel.progress.collectAsState()
    return LegendEntry("X", stringResource(if (progress?.partial == false) R.string.pad_stop else R.string.collection_analyze))
}

/** Small upper-case heading between the blocks of a pane. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 14.dp, top = 16.dp, bottom = 6.dp)
    )
}

/**
 * The focusable row of every pane: [title] over [subtitle], optional [leading] and [trailing].
 * A (or a tap) runs [onClick]; the ring shows where the D-pad is.
 */
@Composable
internal fun CollectionRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleColor: Color = Color.Unspecified,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    /** Under the subtitle: a bar, for the rows of the statistics. */
    extra: (@Composable () -> Unit)? = null,
    onClick: () -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) scheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .focusRing(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            extra?.let {
                Box(modifier = Modifier.padding(top = 6.dp)) { it() }
            }
        }
        trailing?.invoke(this)
    }
}

/** Trailing figure of a row (a size, a count). */
@Composable
internal fun RowFigure(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 56.dp))
}

/** The square tick of selectable rows, the same as in Downloads. */
@Composable
internal fun TickBox(ticked: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (ticked) scheme.primary else scheme.surfaceContainer)
            .then(if (ticked) Modifier else Modifier.border(1.dp, scheme.outlineVariant, RoundedCornerShape(8.dp))),
        contentAlignment = Alignment.Center
    ) {
        if (ticked) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = stringResource(R.string.selection_selected),
                tint = scheme.onPrimary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/** A plain bar for a share (0..1): the only kind of chart the tools draw. */
@Composable
internal fun FlatBar(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0.01f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
    }
}

/** A text button in the tonal style of the app; [focus] lets a pane hand the D-pad to it. */
@Composable
internal fun CollectionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    focus: FocusRequester? = null
) {
    val scheme = MaterialTheme.colorScheme
    val source = rememberFocusSource()
    Box(
        modifier = modifier
            .then(focus?.let { Modifier.focusRequester(it) } ?: Modifier)
            .height(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(scheme.surfaceContainerHigh)
            .focusRing(source)
            // Disabled, it still takes the focus: a pane's entry point must not vanish when nothing is ticked.
            .then(
                if (enabled) Modifier.clickable(interactionSource = source, indication = null, onClick = onClick)
                else Modifier.focusable(interactionSource = source)
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> scheme.onSurfaceVariant
                destructive -> scheme.error
                else -> scheme.onSurface
            },
            maxLines = 1
        )
    }
}

/** The line at the top of a pane: a summary on the left, the pane's main action on the right. */
@Composable
internal fun PaneHeader(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        action?.invoke()
    }
}

/**
 * What a tool shows before there is an analysis: the call to run one (or its progress), or the
 * hint to pick the ROMs folder first. [entryFocus] goes to its button.
 */
@Composable
internal fun NeedsAnalysis(
    progress: ScanProgress?,
    libraryConfigured: Boolean,
    entryFocus: FocusRequester,
    onAnalyze: () -> Unit,
    onCancel: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when {
                progress != null -> {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Text(scanningLabel(progress), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    CollectionButton(stringResource(R.string.collection_cancel_analysis), onCancel, focus = entryFocus)
                }
                !libraryConfigured -> {
                    Text(stringResource(R.string.collection_no_folder), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    CollectionButton(stringResource(R.string.collection_open_settings), onOpenSettings, focus = entryFocus)
                }
                else -> {
                    Text(stringResource(R.string.collection_needs_analysis), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    CollectionButton(stringResource(R.string.collection_analyze), onAnalyze, focus = entryFocus)
                }
            }
        }
    }
}

/**
 * Shows [content] once there is an analysis; until then the call to run one (or its progress).
 * While an older analysis is being worked out again, its tool keeps showing it.
 */
@Composable
internal fun AnalysisGate(
    viewModel: CollectionViewModel,
    active: Boolean,
    entryFocus: FocusRequester,
    nav: CollectionNav,
    content: @Composable (CollectionAnalysis) -> Unit
) {
    val analysis by viewModel.analysis.collectAsState()
    val snapshot by viewModel.snapshot.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val configured by viewModel.libraryConfigured.collectAsState()
    val current = analysis
    when {
        current != null -> content(current)
        // A cached snapshot is being turned into numbers: a moment, not a call to action.
        snapshot != null && progress == null -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        }
        else -> {
            PublishLegend(listOf(LegendEntry("A", stringResource(R.string.pad_select))) + paneLegendTail(), active)
            NeedsAnalysis(progress, configured, entryFocus, viewModel::analyze, viewModel::cancelAnalysis, nav.openSettings)
        }
    }
}

/** "Analysing… 1,234 files (psx)". */
@Composable
internal fun scanningLabel(progress: ScanProgress): String =
    if (progress.folder.isEmpty()) pluralStringResource(R.plurals.collection_scanning, progress.files, progress.files)
    else pluralStringResource(R.plurals.collection_scanning_folder, progress.files, progress.files, progress.folder)

/** "3 min ago", "2 h ago", "4 days ago" (no seconds: the analysis is not that fresh). */
@Composable
internal fun agoLabel(at: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = ((now - at).coerceAtLeast(0) / 60_000).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.collection_ago_now)
        minutes < 60 -> pluralStringResource(R.plurals.collection_ago_minutes, minutes, minutes)
        minutes < 24 * 60 -> pluralStringResource(R.plurals.collection_ago_hours, minutes / 60, minutes / 60)
        else -> pluralStringResource(R.plurals.collection_ago_days, minutes / (24 * 60), minutes / (24 * 60))
    }
}

/** Short console name, or the folder (or "root") when a file belongs to none. */
@Composable
internal fun consoleLabel(consoleId: String?, folder: String = ""): String = when {
    consoleId != null -> ConsoleFormatter.getConsoleShortName(consoleId)
    folder.isNotEmpty() -> folder
    else -> stringResource(R.string.collection_root_folder)
}

@Composable
internal fun severityColor(severity: HealthSeverity): Color = when (severity) {
    HealthSeverity.HIGH -> MaterialTheme.colorScheme.error
    HealthSeverity.MEDIUM -> MaterialTheme.colorScheme.primary
    HealthSeverity.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
internal fun severityLabel(severity: HealthSeverity): String = stringResource(
    when (severity) {
        HealthSeverity.HIGH -> R.string.collection_severity_high
        HealthSeverity.MEDIUM -> R.string.collection_severity_medium
        HealthSeverity.LOW -> R.string.collection_severity_low
    }
)

/**
 * The confirmation of every destructive action: focus starts on Cancel, B closes it.
 * [lines] preview what will happen.
 */
@Composable
internal fun ConfirmDialog(
    title: String,
    lines: List<String>,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { DialogButton(confirmLabel, onClick = onConfirm) },
        dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = rememberInitialFocus()) }
    )
}
