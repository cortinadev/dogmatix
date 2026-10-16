package com.cortinadev.dogmatix.ui.screens.collection.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.LegendEntry
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.ui.screens.collection.CollectionNav
import com.cortinadev.dogmatix.ui.screens.collection.CollectionTool
import com.cortinadev.dogmatix.ui.screens.collection.CollectionViewModel
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.DiskFile
import com.cortinadev.dogmatix.util.FileParsingUtils
import com.cortinadev.dogmatix.util.HealthFinding
import com.cortinadev.dogmatix.util.HealthFix
import com.cortinadev.dogmatix.util.HealthKind
import com.cortinadev.dogmatix.util.HealthSeverity
import com.cortinadev.dogmatix.util.MultiDisc
import kotlinx.coroutines.launch

private const val MAX_ITEMS = 100

/**
 * Health: one row per kind of problem, worst first. A unfolds it: its action first (repair them,
 * free up space, open Settings or Sources), then each case; A on a case goes to its fix or to the
 * game in the Library. B folds it again.
 */
@Composable
fun HealthPane(viewModel: CollectionViewModel, active: Boolean, entryFocus: FocusRequester, nav: CollectionNav, modifier: Modifier = Modifier) {
    AnalysisGate(viewModel, active, entryFocus, nav) { analysis ->
        val scope = rememberCoroutineScope()
        var expanded by rememberSaveable { mutableStateOf<String?>(null) }
        val groupFocus = remember { HealthKind.entries.associateWith { FocusRequester() } }
        val groups = remember(analysis.findings) {
            analysis.findings.groupBy { it.kind }.entries
                .sortedWith(compareBy({ entry -> entry.value.minOf { it.severity.ordinal } }, { it.key.ordinal }))
                .map { it.key to it.value }
        }
        val repairIds = remember(analysis.repairs) { analysis.repairs.mapTo(HashSet()) { it.id } }
        val collapse = {
            val kind = expanded?.let { HealthKind.valueOf(it) }
            expanded = null
            kind?.let {
                scope.launch {
                    withFrameNanos { }
                    runCatching { groupFocus.getValue(it).requestFocus() }
                }
            }
        }
        BackHandler(enabled = expanded != null) { collapse() }

        PublishLegend(
            listOf(LegendEntry("A", stringResource(if (expanded != null) R.string.pad_select else R.string.pad_show)), analyzeLegendEntry(viewModel)) +
                (if (expanded != null) listOf(LegendEntry("B", stringResource(R.string.pad_close)), LegendEntry("ZL · ZR", stringResource(R.string.pad_section))) else paneLegendTail()),
            active
        )

        val counts = HealthSeverity.entries.associateWith { s -> analysis.findings.count { it.severity == s } }
        LazyColumn(modifier = modifier, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            item {
                PaneHeader(
                    if (analysis.findings.isEmpty()) stringResource(R.string.collection_health_all_good)
                    else stringResource(
                        R.string.collection_health_summary,
                        counts.getValue(HealthSeverity.HIGH), counts.getValue(HealthSeverity.MEDIUM), counts.getValue(HealthSeverity.LOW)
                    )
                )
            }
            groups.forEachIndexed { index, (kind, findings) ->
                val severity = findings.minBy { it.severity.ordinal }.severity
                val open = expanded == kind.name
                item(key = "g:" + kind.name) {
                    CollectionRow(
                        title = groupTitle(kind, findings),
                        subtitle = stringResource(kind.hintRes),
                        selected = open,
                        modifier = Modifier
                            .focusRequester(groupFocus.getValue(kind))
                            .then(if (index == 0) Modifier.focusRequester(entryFocus) else Modifier),
                        leading = { SeverityDot(severity) },
                        trailing = { Text(severityLabel(severity), style = MaterialTheme.typography.labelMedium, color = severityColor(severity)) },
                        onClick = { if (open) collapse() else expanded = kind.name }
                    )
                }
                if (open) {
                    val repairs = findings.count { it.id in repairIds }
                    val fix = kind.fix?.takeIf { it != HealthFix.OPEN_LIBRARY && (it != HealthFix.REPAIR || repairs > 0) }
                    if (fix != null) {
                        item(key = "a:" + kind.name) {
                            CollectionRow(
                                title = actionLabel(fix, repairs),
                                titleColor = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp),
                                onClick = {
                                    when (fix) {
                                        HealthFix.REPAIR -> { viewModel.focusRepairs(kind); nav.openTool(CollectionTool.REPAIR) }
                                        HealthFix.FREE_SPACE -> nav.openTool(CollectionTool.FREE_SPACE)
                                        HealthFix.OPEN_SETTINGS -> nav.openSettings()
                                        HealthFix.OPEN_SOURCES -> nav.openSources()
                                        HealthFix.OPEN_LIBRARY -> Unit
                                    }
                                }
                            )
                        }
                    }
                    items(findings.take(MAX_ITEMS), key = { "f:" + it.id }) { finding ->
                        CollectionRow(
                            title = itemTitle(finding),
                            subtitle = itemDetail(finding),
                            modifier = Modifier.padding(start = 16.dp),
                            onClick = {
                                when {
                                    // A set is named without extension; a sheet or playlist by its file name.
                                    kind.fix == HealthFix.OPEN_LIBRARY -> viewModel.openInLibrary(
                                        finding.consoleId,
                                        if (kind == HealthKind.INCOMPLETE_SET) finding.subject else MultiDisc.stripExtension(finding.subject)
                                    )
                                    kind.fix == HealthFix.REPAIR && finding.id in repairIds -> {
                                        viewModel.focusRepair(finding.id)
                                        nav.openTool(CollectionTool.REPAIR)
                                    }
                                }
                            }
                        )
                    }
                    if (findings.size > MAX_ITEMS) item(key = "m:" + kind.name) {
                        EmptyLine(stringResource(R.string.collection_and_more, findings.size - MAX_ITEMS))
                    }
                }
            }
        }
    }
}

@Composable
private fun SeverityDot(severity: HealthSeverity) {
    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(severityColor(severity)))
}

/** What a group's first row says it does. */
@Composable
private fun actionLabel(fix: HealthFix, repairs: Int): String = when (fix) {
    HealthFix.REPAIR -> pluralStringResource(R.plurals.collection_health_repair_these, repairs, repairs)
    HealthFix.FREE_SPACE -> stringResource(R.string.collection_open_free_space)
    HealthFix.OPEN_SETTINGS -> stringResource(R.string.collection_open_settings)
    HealthFix.OPEN_SOURCES -> stringResource(R.string.collection_open_sources)
    HealthFix.OPEN_LIBRARY -> ""
}

@Composable
private fun groupTitle(kind: HealthKind, findings: List<HealthFinding>): String {
    val n = findings.size
    return when (kind) {
        HealthKind.STALE_SOURCES -> findings.first().count.let { days ->
            if (days < 0) stringResource(R.string.collection_health_sources_never)
            else pluralStringResource(R.plurals.collection_health_sources_days, days, days)
        }
        else -> pluralStringResource(kind.titleRes, n, n)
    }
}

@Composable
private fun itemTitle(finding: HealthFinding): String = when (finding.kind) {
    HealthKind.PERMISSION_LOST -> FileParsingUtils.toUserReadablePath(finding.subject)
    HealthKind.EMPTY_FOLDER -> finding.folder?.let { listOf(it.topFolder, it.path).filter { p -> p.isNotEmpty() }.joinToString("/") } ?: finding.subject
    HealthKind.STALE_SOURCES -> stringResource(R.string.nav_sources)
    else -> finding.subject
}

@Composable
private fun itemDetail(finding: HealthFinding): String? {
    val file = finding.files.firstOrNull()
    return when (finding.kind) {
        HealthKind.PERMISSION_LOST -> finding.consoleId?.let { ConsoleFormatter.getConsoleShortName(it) } ?: stringResource(R.string.collection_download_folder)
        HealthKind.LOW_SPACE -> stringResource(R.string.collection_free_bytes, formatBytes(finding.bytes))
        HealthKind.BROKEN_REFERENCE -> stringResource(R.string.collection_missing_files, finding.missing.joinToString(", "))
        HealthKind.INCOMPLETE_SET -> stringResource(R.string.collection_missing_discs, finding.missing.joinToString(", "), finding.count)
        HealthKind.MISSING_PLAYLIST -> pluralStringResource(R.plurals.collection_discs, finding.count, finding.count) + "  ·  " + location(file)
        HealthKind.WRONG_CONSOLE -> location(file) + (finding.targetConsoleId?.let { "  →  " + ConsoleFormatter.getConsoleShortName(it) } ?: "")
        HealthKind.PARTIAL_DOWNLOAD, HealthKind.EXTRACTED_ARCHIVE -> location(file) + "  ·  " + formatBytes(finding.files.sumOf { it.size })
        HealthKind.EMPTY_FOLDER -> consoleLabel(finding.consoleId, finding.folder?.topFolder.orEmpty())
        HealthKind.STALE_SOURCES -> null
        else -> location(file)
    }
}

/** Where a file sits, from its console folder: `psx/Game.m3u`. */
@Composable
internal fun location(file: DiskFile?): String {
    file ?: return ""
    val top = file.topFolder.ifEmpty { stringResource(R.string.collection_root_folder) }
    return listOf(top, file.folderPath).filter { it.isNotEmpty() }.joinToString("/")
}

private val HealthKind.titleRes: Int
    get() = when (this) {
        HealthKind.PERMISSION_LOST -> R.plurals.collection_health_permission
        HealthKind.LOW_SPACE -> R.plurals.collection_health_low_space
        HealthKind.ZERO_BYTES -> R.plurals.collection_health_zero_bytes
        HealthKind.BROKEN_REFERENCE -> R.plurals.collection_health_broken_reference
        HealthKind.INCOMPLETE_SET -> R.plurals.collection_health_incomplete_set
        HealthKind.WRONG_CONSOLE -> R.plurals.collection_health_wrong_console
        HealthKind.PARTIAL_DOWNLOAD -> R.plurals.collection_health_partial
        HealthKind.MISSING_PLAYLIST -> R.plurals.collection_health_missing_playlist
        HealthKind.EXTRACTED_ARCHIVE -> R.plurals.collection_health_extracted_archive
        HealthKind.EMPTY_FOLDER -> R.plurals.collection_health_empty_folder
        HealthKind.FOREIGN_EXTENSION -> R.plurals.collection_health_foreign_extension
        HealthKind.STALE_SOURCES -> R.plurals.collection_health_sources_days
    }

private val HealthKind.hintRes: Int
    get() = when (this) {
        HealthKind.PERMISSION_LOST -> R.string.collection_health_permission_hint
        HealthKind.LOW_SPACE -> R.string.collection_health_low_space_hint
        HealthKind.ZERO_BYTES -> R.string.collection_health_zero_bytes_hint
        HealthKind.BROKEN_REFERENCE -> R.string.collection_health_broken_reference_hint
        HealthKind.INCOMPLETE_SET -> R.string.collection_health_incomplete_set_hint
        HealthKind.WRONG_CONSOLE -> R.string.collection_health_wrong_console_hint
        HealthKind.PARTIAL_DOWNLOAD -> R.string.collection_health_partial_hint
        HealthKind.MISSING_PLAYLIST -> R.string.collection_health_missing_playlist_hint
        HealthKind.EXTRACTED_ARCHIVE -> R.string.collection_health_extracted_archive_hint
        HealthKind.EMPTY_FOLDER -> R.string.collection_health_empty_folder_hint
        HealthKind.FOREIGN_EXTENSION -> R.string.collection_health_foreign_extension_hint
        HealthKind.STALE_SOURCES -> R.string.collection_health_sources_hint
    }
