package com.cortinadev.dogmatix.ui.screens.settings.ia

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.IaAuth
import com.cortinadev.dogmatix.data.service.IaSession
import com.cortinadev.dogmatix.data.service.IaSessionCheck
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.screens.settings.SettingRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the session dialog shows under its text: nothing yet, a check under way, or its answer. */
sealed interface IaCheckState {
    data object Idle : IaCheckState
    data object Running : IaCheckState
    data class Done(val result: IaSessionCheck) : IaCheckState
}

/** Settings → Internet Archive: the saved archive.org session, its check and signing out (see [IaAuth]). */
@HiltViewModel
class IaSessionViewModel @Inject constructor(private val iaAuth: IaAuth) : ViewModel() {
    val session: StateFlow<IaSession?> = iaAuth.session.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _dialogOpen = MutableStateFlow(false)
    val dialogOpen: StateFlow<Boolean> = _dialogOpen.asStateFlow()
    private val _check = MutableStateFlow<IaCheckState>(IaCheckState.Idle)
    val check: StateFlow<IaCheckState> = _check.asStateFlow()
    private var checkJob: Job? = null

    fun openDialog() {
        _check.value = IaCheckState.Idle
        _dialogOpen.value = true
    }

    fun closeDialog() {
        checkJob?.cancel()
        _dialogOpen.value = false
    }

    fun checkSession() {
        if (_check.value == IaCheckState.Running) return
        _check.value = IaCheckState.Running
        checkJob = viewModelScope.launch { _check.value = IaCheckState.Done(iaAuth.check()) }
    }

    fun logout() {
        closeDialog()
        viewModelScope.launch { iaAuth.logout() }
    }
}

/**
 * The row: without a session A opens the archive.org sign-in page ([onSignIn]); with one, a
 * dialog to check it or sign out.
 */
@Composable
internal fun IaSessionRow(onSignIn: () -> Unit, viewModel: IaSessionViewModel = hiltViewModel()) {
    val session by viewModel.session.collectAsState()
    val action = { if (session == null) onSignIn() else viewModel.openDialog() }
    SettingRow(
        title = stringResource(R.string.settings_ia_session),
        hint = session?.let { stringResource(R.string.settings_ia_session_as, it.email) } ?: stringResource(R.string.settings_ia_session_none),
        onClick = action
    ) {
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    IaSessionDialog(viewModel)
}

/** Check (whoami) or sign out; focus starts on Cancel and B closes. */
@Composable
private fun IaSessionDialog(viewModel: IaSessionViewModel) {
    val open by viewModel.dialogOpen.collectAsState()
    val session by viewModel.session.collectAsState()
    val check by viewModel.check.collectAsState()
    val current = session
    if (!open || current == null) return
    val cancelFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(viewModel::closeDialog),
        onDismissRequest = viewModel::closeDialog,
        title = { Text(stringResource(R.string.settings_ia_session)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_ia_session_as, current.email))
                Text(
                    stringResource(R.string.ia_logout_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when (val c = check) {
                    IaCheckState.Idle -> Unit
                    IaCheckState.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.romm_testing), style = MaterialTheme.typography.bodySmall)
                    }
                    is IaCheckState.Done -> when (val r = c.result) {
                        is IaSessionCheck.Valid -> Text(
                            stringResource(R.string.ia_session_valid, r.name),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary
                        )
                        IaSessionCheck.Expired -> Text(
                            stringResource(R.string.ia_session_expired),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                        )
                        IaSessionCheck.Failed -> Text(
                            stringResource(R.string.ia_session_check_failed),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        dismissButton = {
            DialogButton(stringResource(R.string.dialog_cancel), onClick = viewModel::closeDialog, initialFocus = cancelFocus)
        },
        confirmButton = {
            Row {
                DialogButton(stringResource(R.string.ia_session_check), onClick = viewModel::checkSession)
                DialogButton(stringResource(R.string.ia_logout), onClick = viewModel::logout)
            }
        }
    )
}
