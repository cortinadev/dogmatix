package com.cortinadev.dogmatix.ui.screens.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.util.FrontendCheck
import com.cortinadev.dogmatix.util.FrontendCheck.Detail
import com.cortinadev.dogmatix.util.FrontendCheck.Frontend
import com.cortinadev.dogmatix.util.FrontendCheck.Repair
import com.cortinadev.dogmatix.util.FrontendCheck.Status

/** "Check frontends" while it is open: [running] during a check or a repair, [findings] of the last check. */
data class FrontendCheckUi(
    val running: Boolean = true,
    val findings: List<FrontendCheck.Finding> = emptyList()
)

/**
 * The frontend check: ✓ / ⚠ per point, grouped by frontend. Every line takes focus, so the
 * D-pad scrolls through the whole report; A on a warning runs its Repair (the same setup as its
 * Settings row) and the report is checked again. Focus starts on the first warning that can be
 * repaired, or on Close; B closes.
 */
@Composable
fun FrontendCheckDialog(
    state: FrontendCheckUi,
    onRepair: (Repair) -> Unit,
    onRecheck: () -> Unit,
    onDismiss: () -> Unit
) {
    val close = rememberInitialFocus()
    val rowFocus = remember(state.findings) { state.findings.map { FocusRequester() } }
    LaunchedEffect(state.findings, state.running) {
        if (state.running) return@LaunchedEffect
        val target = state.findings.indexOfFirst { it.canRepair }.let { if (it >= 0) rowFocus[it] else close }
        repeat(5) {
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }

    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.frontend_check_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (state.findings.isEmpty()) {
                    Text(
                        stringResource(if (state.running) R.string.frontend_check_running else R.string.msg_operation_failed),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Frontend.entries.forEach { frontend ->
                    val items = state.findings.withIndex().filter { it.value.frontend == frontend }
                    if (items.isEmpty()) return@forEach
                    Text(
                        stringResource(frontend.titleRes),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                    )
                    items.forEach { (index, finding) ->
                        FindingRow(finding, rowFocus[index]) { if (!state.running) onRepair(finding.repair) }
                    }
                    if (frontend == Frontend.SHORTCUTS) {
                        Text(
                            stringResource(R.string.frontend_check_daijisho_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp, top = 4.dp)
                        )
                    }
                }
                if (state.running && state.findings.isNotEmpty()) {
                    Text(
                        stringResource(R.string.frontend_check_running),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            DialogButton(text = stringResource(R.string.dialog_close), onClick = onDismiss, initialFocus = close)
        },
        dismissButton = {
            DialogButton(text = stringResource(R.string.frontend_check_again), onClick = onRecheck, enabled = !state.running)
        }
    )
}

private val FrontendCheck.Finding.canRepair: Boolean get() = status == Status.WARN && repair != Repair.NONE

@Composable
private fun FindingRow(finding: FrontendCheck.Finding, focus: FocusRequester, onRepair: () -> Unit) {
    val source = rememberFocusSource()
    val ok = finding.status == Status.OK
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .clip(RoundedCornerShape(8.dp))
            .focusRing(source)
            // Every line is focusable so the D-pad can scroll the report; only warnings act.
            .clickable(interactionSource = source, indication = null) { if (finding.canRepair) onRepair() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            if (ok) "✓" else "⚠",
            style = MaterialTheme.typography.titleMedium,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(20.dp)
        )
        Text(detailText(finding), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (finding.canRepair) {
            // Not focusable: the line is the focus target, this only says what A does.
            Box(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.frontend_check_repair), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private val Frontend.titleRes: Int
    get() = when (this) {
        Frontend.ESDE -> R.string.frontend_check_section_esde
        Frontend.IISU -> R.string.frontend_check_section_iisu
        Frontend.SHORTCUTS -> R.string.frontend_check_section_shortcuts
    }

@Composable
private fun detailText(finding: FrontendCheck.Finding): String {
    val missing = finding.missing.size
    val names = finding.missing.take(MAX_NAMES).joinToString(", ") + if (missing > MAX_NAMES) ", …" else ""
    return when (finding.detail) {
        Detail.ESDE_FOLDER_OK -> stringResource(R.string.fc_esde_folder_ok)
        Detail.ESDE_FOLDER_NOT_SET -> stringResource(R.string.fc_esde_folder_not_set)
        Detail.ESDE_FOLDER_NO_ACCESS -> stringResource(R.string.fc_esde_folder_no_access)
        Detail.ESDE_NOT_INSTALLED -> stringResource(R.string.fc_esde_not_installed)
        Detail.ESDE_FIND_RULE_OK -> stringResource(R.string.fc_esde_find_rule_ok)
        Detail.ESDE_FIND_RULE_MISSING -> stringResource(R.string.fc_esde_find_rule_missing)
        Detail.ESDE_FIND_RULE_OTHER_BUILD -> stringResource(R.string.fc_esde_find_rule_other, names)
        Detail.ESDE_SYSTEMS_OK -> stringResource(R.string.fc_esde_systems_ok, finding.total)
        Detail.ESDE_SYSTEMS_MISSING -> stringResource(R.string.fc_esde_systems_missing, missing, finding.total, names)
        Detail.ESDE_NO_SYSTEMS -> stringResource(R.string.fc_esde_no_systems)
        Detail.ESDE_GAMELISTS_OK -> stringResource(R.string.fc_esde_gamelists_ok, finding.total)
        Detail.ESDE_GAMELISTS_MISSING -> stringResource(R.string.fc_esde_gamelists_missing, missing, finding.total, names)
        Detail.ESDE_COVERS_OK -> stringResource(R.string.fc_esde_covers_ok, finding.total)
        Detail.ESDE_COVERS_MISSING -> stringResource(R.string.fc_esde_covers_missing, missing, finding.total, names)
        Detail.IISU_FOLDER_OK -> stringResource(R.string.fc_iisu_folder_ok)
        Detail.IISU_FOLDER_NOT_SET -> stringResource(R.string.fc_iisu_folder_not_set)
        Detail.IISU_FOLDER_NO_ACCESS -> stringResource(R.string.fc_iisu_folder_no_access)
        Detail.IISU_WRONG_FOLDER -> stringResource(R.string.fc_iisu_wrong_folder)
        Detail.IISU_NOT_INSTALLED -> stringResource(R.string.fc_iisu_not_installed)
        Detail.IISU_FILE_MISSING -> stringResource(R.string.fc_iisu_file_missing)
        Detail.IISU_FILE_CORRUPT -> stringResource(R.string.fc_iisu_file_corrupt)
        Detail.IISU_NO_CONSOLES -> stringResource(R.string.fc_iisu_no_consoles)
        Detail.IISU_EMULATORS_OK -> stringResource(R.string.fc_iisu_emulators_ok, finding.total)
        Detail.IISU_EMULATORS_MISSING -> stringResource(R.string.fc_iisu_emulators_missing, missing, finding.total, names)
        Detail.SHORTCUTS_OK -> stringResource(R.string.fc_shortcuts_ok, finding.total)
        Detail.SHORTCUTS_MISSING -> stringResource(R.string.fc_shortcuts_missing, missing, finding.total, names)
        Detail.SHORTCUTS_NO_FOLDER -> stringResource(R.string.fc_shortcuts_no_folder)
        Detail.SHORTCUTS_NO_CONSOLES -> stringResource(R.string.fc_shortcuts_no_consoles)
    }
}

/** Names listed in a warning before the rest becomes "…". */
private const val MAX_NAMES = 4
