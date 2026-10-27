package com.cortinadev.dogmatix.ui.screens.settings

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.UpdateError
import com.cortinadev.dogmatix.data.service.UpdateState
import com.cortinadev.dogmatix.data.service.VersionCheckerService
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** Settings → "Updates" and "Check for updates on start", and the update dialog (see VersionCheckerService). */
@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    private val service: VersionCheckerService,
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    val state: StateFlow<UpdateState> = service.state
    val dialogOpen: StateFlow<Boolean> = service.dialogOpen
    val installPending: StateFlow<Boolean> = service.installPending
    val autoCheck: StateFlow<Boolean> = settingsRepository.updateAutoCheck.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val currentVersion: String get() = service.currentVersion

    fun setAutoCheck(enabled: Boolean) = viewModelScope.launch { settingsRepository.setUpdateAutoCheck(enabled) }
    fun checkNow() = service.checkNow()
    fun openDialog() = service.openDialog()
    fun closeDialog() = service.closeDialog()
    fun download() = service.download()
    fun cancelDownload() = service.cancelDownload()
    fun installIntent(file: File) = service.installIntent(file)
    fun onInstallerOpened() = service.onInstallerOpened()
}

/** The row: what is known about the latest version; A checks, or opens the dialog when there is something to install. */
@Composable
internal fun UpdatesRow(viewModel: AppUpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val current = viewModel.currentVersion
    val hasRelease = state is UpdateState.Available || state is UpdateState.Downloading || state is UpdateState.Ready ||
        (state as? UpdateState.Failed)?.release != null
    val action = { if (hasRelease) viewModel.openDialog() else if (state !is UpdateState.Checking) viewModel.checkNow() }
    SettingRow(
        title = stringResource(R.string.settings_updates),
        hint = when (val s = state) {
            UpdateState.Idle -> stringResource(R.string.settings_updates_hint_idle, current)
            UpdateState.Checking -> stringResource(R.string.settings_updates_hint_checking)
            UpdateState.UpToDate -> stringResource(R.string.settings_updates_hint_up_to_date, current)
            is UpdateState.Available -> stringResource(R.string.settings_updates_hint_available, s.release.version, current)
            is UpdateState.Downloading -> stringResource(R.string.settings_updates_hint_downloading, s.release.version, progressLabel(s))
            is UpdateState.Ready -> stringResource(R.string.settings_updates_hint_ready, s.release.version)
            is UpdateState.Failed -> s.release?.let { stringResource(R.string.settings_updates_hint_available, it.version, current) }
                ?: stringResource(R.string.settings_updates_hint_failed, current)
        },
        onClick = action
    ) {
        val label = when (state) {
            is UpdateState.Ready -> R.string.update_install
            else -> if (hasRelease) R.string.update_now else R.string.settings_updates_check
        }
        PillButton(stringResource(label), action)
    }
}

@Composable
internal fun AutoUpdateCheckRow(viewModel: AppUpdateViewModel = hiltViewModel()) {
    val enabled by viewModel.autoCheck.collectAsState()
    SettingRow(
        title = stringResource(R.string.settings_update_auto),
        hint = stringResource(R.string.settings_update_auto_hint),
        onClick = { viewModel.setAutoCheck(!enabled) },
        onAdjust = { viewModel.setAutoCheck(it > 0) }
    ) {
        ThemedSwitch(enabled) { viewModel.setAutoCheck(it) }
    }
}

/**
 * The update dialog, hosted by the shell: the startup notice of a new version, then its download
 * and the hand-over to the system installer. Focus starts on the action that moves on; B closes
 * (a download goes on and the dialog comes back when it is ready).
 */
@Composable
fun UpdateDialog(viewModel: AppUpdateViewModel = hiltViewModel()) {
    val open by viewModel.dialogOpen.collectAsState()
    val state by viewModel.state.collectAsState()
    val installPending by viewModel.installPending.collectAsState()
    val context = LocalContext.current

    fun install(file: File) {
        try {
            context.startActivity(viewModel.installIntent(file))
        } catch (_: ActivityNotFoundException) {
            ToastUtil.showError(context, context.getString(R.string.update_no_installer))
        }
        viewModel.onInstallerOpened()
    }

    // "Update" was pressed: the installer opens as soon as the APK is ready.
    LaunchedEffect(state, installPending) {
        val ready = state as? UpdateState.Ready ?: return@LaunchedEffect
        if (installPending) install(ready.file)
    }
    if (!open) return
    val current = viewModel.currentVersion

    when (val s = state) {
        is UpdateState.Available -> {
            AlertDialog(
                modifier = Modifier.closeOnGamepadB(viewModel::closeDialog),
                onDismissRequest = viewModel::closeDialog,
                title = { Text(stringResource(R.string.update_dialog_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.update_dialog_text, s.release.name, current))
                        if (s.release.summary.isNotBlank()) {
                            Text(s.release.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (s.release.apkSize > 0) {
                            Text(
                                stringResource(R.string.update_dialog_size, formatBytes(s.release.apkSize)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = { DialogButton(stringResource(R.string.update_now), viewModel::download, initialFocus = rememberWindowInitialFocus()) },
                dismissButton = { DialogButton(stringResource(R.string.update_later), viewModel::closeDialog) }
            )
        }
        is UpdateState.Downloading -> {
            AlertDialog(
                modifier = Modifier.closeOnGamepadB(viewModel::closeDialog),
                onDismissRequest = viewModel::closeDialog,
                title = { Text(stringResource(R.string.update_downloading_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val fraction = s.fraction
                        if (fraction != null) {
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                strokeCap = StrokeCap.Round
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                strokeCap = StrokeCap.Round
                            )
                        }
                        Text(
                            if (s.release.apkSize > 0) stringResource(R.string.update_downloading_progress, formatBytes(s.bytes), formatBytes(s.release.apkSize))
                            else formatBytes(s.bytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = { DialogButton(stringResource(R.string.update_hide), viewModel::closeDialog, initialFocus = rememberWindowInitialFocus()) },
                dismissButton = { DialogButton(stringResource(R.string.dialog_cancel), viewModel::cancelDownload) }
            )
        }
        is UpdateState.Ready -> {
            AlertDialog(
                modifier = Modifier.closeOnGamepadB(viewModel::closeDialog),
                onDismissRequest = viewModel::closeDialog,
                title = { Text(stringResource(R.string.update_ready_title, s.release.version)) },
                text = { Text(stringResource(R.string.update_ready_text)) },
                confirmButton = { DialogButton(stringResource(R.string.update_install), { install(s.file) }, initialFocus = rememberWindowInitialFocus()) },
                dismissButton = { DialogButton(stringResource(R.string.update_later), viewModel::closeDialog) }
            )
        }
        is UpdateState.Failed -> {
            val uriHandler = LocalUriHandler.current
            val release = s.release
            AlertDialog(
                modifier = Modifier.closeOnGamepadB(viewModel::closeDialog),
                onDismissRequest = viewModel::closeDialog,
                title = { Text(stringResource(R.string.update_failed_title)) },
                text = { Text(stringResource(errorRes(s.error))) },
                confirmButton = {
                    val primary = rememberWindowInitialFocus()
                    when {
                        // Signed with another key, or not newer: retrying won't help; the release page explains the way out.
                        (s.error == UpdateError.SIGNATURE || s.error == UpdateError.OLDER) && release != null && release.pageUrl.isNotBlank() ->
                            DialogButton(stringResource(R.string.update_open_page), {
                                viewModel.closeDialog()
                                runCatching { uriHandler.openUri(release.pageUrl) }
                            }, initialFocus = primary)
                        release != null -> DialogButton(stringResource(R.string.update_retry), viewModel::download, initialFocus = primary)
                        else -> DialogButton(stringResource(R.string.update_retry), viewModel::checkNow, initialFocus = primary)
                    }
                },
                dismissButton = { DialogButton(stringResource(R.string.details_close), viewModel::closeDialog) }
            )
        }
        // Nothing to show (a check in flight, or the app is up to date).
        else -> Unit
    }
}

/**
 * Initial focus for the update dialog, read inside its window (call it from a button slot). The
 * startup notice opens while the activity is still starting: the frames `rememberInitialFocus`
 * retries are over before the dialog's window has focus, and the first A press then only woke
 * the focus, on "Later". Asked again whenever that window gets the focus.
 */
@Composable
private fun rememberWindowInitialFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) {
        if (!windowFocused) return@LaunchedEffect
        repeat(5) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            withFrameNanos { }
        }
    }
    return requester
}

@Composable
private fun progressLabel(state: UpdateState.Downloading): String =
    state.fraction?.let { "${(it * 100).toInt()} %" } ?: formatBytes(state.bytes)

private fun errorRes(error: UpdateError): Int = when (error) {
    UpdateError.NETWORK -> R.string.update_error_network
    UpdateError.BAD_APK -> R.string.update_error_bad_apk
    UpdateError.SIGNATURE -> R.string.update_error_signature
    UpdateError.STORAGE -> R.string.update_error_storage
    UpdateError.OLDER -> R.string.update_error_older
}
