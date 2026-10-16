package com.cortinadev.dogmatix.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.repository.ConsoleRepository
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.DatInfo
import com.cortinadev.dogmatix.data.service.DatStore
import com.cortinadev.dogmatix.data.service.StagedDat
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.util.ConsoleFormatter
import com.cortinadev.dogmatix.util.DownloadCheck
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Settings → "Verify downloads" and "DAT files" (the DATs downloads are checked against). */
@HiltViewModel
class DownloadChecksViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val consoleRepository: ConsoleRepository,
    private val datStore: DatStore
) : ViewModel() {

    val verifyDownloads: StateFlow<Boolean> = settingsRepository.verifyDownloads.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val datAutoDownload: StateFlow<Boolean> = settingsRepository.datAutoDownload.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val dats: StateFlow<List<DatInfo>> = datStore.dats
    /** Library consoles by id, sorted by name (where an imported DAT can go). */
    val consoleIds: StateFlow<List<String>> = consoleRepository.getAllConsoles()
        .map { list -> list.map { it.id }.sortedBy { ConsoleFormatter.getConsoleDisplayName(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _staged = MutableStateFlow<StagedDat?>(null)
    /** An imported DAT waiting for its console. */
    val staged: StateFlow<StagedDat?> = _staged.asStateFlow()

    private val _reading = MutableStateFlow(false)
    val reading: StateFlow<Boolean> = _reading.asStateFlow()

    init {
        viewModelScope.launch { datStore.refresh() }
    }

    fun setVerifyDownloads(enabled: Boolean) = viewModelScope.launch { settingsRepository.setVerifyDownloads(enabled) }

    fun setDatAutoDownload(enabled: Boolean) = viewModelScope.launch { settingsRepository.setDatAutoDownload(enabled) }

    fun import(uri: Uri) {
        viewModelScope.launch {
            _reading.value = true
            val staged = runCatching { datStore.stage(uri, consoleRepository.getAllConsoles().first().map { it.id }) }.getOrNull()
            _reading.value = false
            if (staged == null) ToastUtil.showError(context, context.getString(R.string.dat_invalid))
            else _staged.value = staged
        }
    }

    fun assign(consoleId: String) {
        val staged = _staged.value ?: return
        _staged.value = null
        viewModelScope.launch {
            runCatching { datStore.commit(staged, consoleId) }
                .onSuccess { ToastUtil.showSuccess(context, context.getString(R.string.dat_saved, ConsoleFormatter.getConsoleDisplayName(consoleId))) }
                .onFailure { ToastUtil.showError(context, it.message ?: context.getString(R.string.dat_invalid)) }
        }
    }

    fun cancelImport() {
        _staged.value?.let(datStore::discard)
        _staged.value = null
    }

    fun remove(info: DatInfo) {
        viewModelScope.launch { datStore.remove(info) }
    }

    override fun onCleared() {
        _staged.value?.let(datStore::discard)
    }
}

@Composable
internal fun VerifyDownloadsRow(viewModel: DownloadChecksViewModel = hiltViewModel()) {
    val enabled by viewModel.verifyDownloads.collectAsState()
    SettingRow(
        title = stringResource(R.string.settings_verify_downloads),
        hint = stringResource(R.string.settings_verify_downloads_hint),
        onClick = { viewModel.setVerifyDownloads(!enabled) },
        onAdjust = { viewModel.setVerifyDownloads(it > 0) }
    ) {
        ThemedSwitch(enabled) { viewModel.setVerifyDownloads(it) }
    }
}

/** The row, and the DAT dialogs it opens (list → import → console; or list → remove). */
@Composable
internal fun DatFilesRow(viewModel: DownloadChecksViewModel = hiltViewModel()) {
    val dats by viewModel.dats.collectAsState()
    val staged by viewModel.staged.collectAsState()
    var showList by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::import)
    }

    SettingRow(
        title = stringResource(R.string.settings_dat_files),
        hint = if (dats.isEmpty()) stringResource(R.string.settings_dat_files_hint_none)
               else pluralStringResource(R.plurals.settings_dat_files_hint, dats.size, dats.size),
        onClick = { showList = true }
    ) {
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    staged?.let { pending ->
        AssignDatDialog(viewModel, pending)
    }
    if (showList && staged == null) {
        DatListDialog(
            viewModel = viewModel,
            onImport = { picker.launch(arrayOf("*/*")) },
            onDismiss = { showList = false }
        )
    }
}

@Composable
private fun DatListDialog(viewModel: DownloadChecksViewModel, onImport: () -> Unit, onDismiss: () -> Unit) {
    val dats by viewModel.dats.collectAsState()
    val auto by viewModel.datAutoDownload.collectAsState()
    val reading by viewModel.reading.collectAsState()
    var removing by remember { mutableStateOf<DatInfo?>(null) }
    val first = rememberInitialFocus()

    removing?.let { info ->
        RemoveDatDialog(info, onConfirm = { viewModel.remove(info); removing = null }, onDismiss = { removing = null })
    }

    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_dat_files)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                SettingRow(
                    title = stringResource(R.string.dat_auto_download),
                    hint = stringResource(R.string.dat_auto_download_hint),
                    onClick = { viewModel.setDatAutoDownload(!auto) },
                    onAdjust = { viewModel.setDatAutoDownload(it > 0) },
                    modifier = Modifier.focusRequester(first)
                ) {
                    ThemedSwitch(auto) { viewModel.setDatAutoDownload(it) }
                }
                if (reading) {
                    Text(
                        stringResource(R.string.dat_reading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                } else if (dats.isEmpty()) {
                    Text(
                        stringResource(R.string.dat_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                dats.forEach { info ->
                    DatRow(info) { removing = info }
                }
            }
        },
        confirmButton = { DialogButton(text = stringResource(R.string.details_close), onClick = onDismiss) },
        dismissButton = { DialogButton(text = stringResource(R.string.dat_import), onClick = onImport, enabled = !reading) }
    )
}

/** One stored DAT; A asks whether to remove it. */
@Composable
private fun DatRow(info: DatInfo, onClick: () -> Unit) {
    val source = rememberFocusSource()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .focusRing(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            stringResource(R.string.dat_row, ConsoleFormatter.getConsoleDisplayName(info.consoleId), originLabel(info)),
            style = MaterialTheme.typography.bodyLarge,
            // Two lines: a long console name would push out the origin, the only thing that tells
            // a console's DATs apart (its No-Intro one and an imported one).
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            listOf(info.name, info.version, pluralStringResource(R.plurals.dat_entries, info.entries, info.entries))
                .filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun originLabel(info: DatInfo): String = when (info.origin) {
    DownloadCheck.NO_INTRO -> stringResource(R.string.check_against_no_intro)
    DownloadCheck.REDUMP -> stringResource(R.string.check_against_redump)
    else -> stringResource(R.string.dat_origin_imported)
}

/** Destructive, so the initial focus is on Cancel. */
@Composable
private fun RemoveDatDialog(info: DatInfo, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancel = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dat_remove_title)) },
        text = {
            Text(stringResource(R.string.dat_remove_text, info.name.ifBlank { originLabel(info) }, ConsoleFormatter.getConsoleDisplayName(info.consoleId)))
        },
        confirmButton = { DialogButton(text = stringResource(R.string.dat_remove), onClick = onConfirm) },
        dismissButton = { DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancel) }
    )
}

/** Which console an imported DAT is for: focus starts on the one its header names, if any. */
@Composable
private fun AssignDatDialog(viewModel: DownloadChecksViewModel, staged: StagedDat) {
    val consoleIds by viewModel.consoleIds.collectAsState()
    val first = rememberInitialFocus()
    val focusOn = staged.guessedConsoleId?.takeIf { it in consoleIds } ?: consoleIds.firstOrNull()
    val cancel = remember { FocusRequester() }
    LaunchedEffect(consoleIds.isEmpty()) { if (consoleIds.isEmpty()) runCatching { cancel.requestFocus() } }

    AlertDialog(
        modifier = Modifier.closeOnGamepadB(viewModel::cancelImport),
        onDismissRequest = viewModel::cancelImport,
        title = { Text(stringResource(R.string.dat_assign_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    listOf(staged.header.name, pluralStringResource(R.plurals.dat_entries, staged.entries, staged.entries))
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                if (consoleIds.isEmpty()) {
                    Text(stringResource(R.string.dat_no_consoles), style = MaterialTheme.typography.bodyMedium)
                }
                consoleIds.forEach { id ->
                    val source = rememberFocusSource()
                    Row(
                        modifier = (if (id == focusOn) Modifier.focusRequester(first) else Modifier)
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .focusRing(source)
                            .clickable(interactionSource = source, indication = null) { viewModel.assign(id) }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(ConsoleFormatter.getConsoleDisplayName(id), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(ConsoleFormatter.getConsoleShortName(id), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { DialogButton(text = stringResource(R.string.dialog_cancel), onClick = viewModel::cancelImport, initialFocus = cancel) }
    )
}
