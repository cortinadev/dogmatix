package com.cortinadev.dogmatix.ui.screens.download

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.ui.common.Gamepad
import com.cortinadev.dogmatix.ui.common.GamepadButton
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus

@Composable
fun DownloadScreen(
    navController: NavController,
    viewModel: DownloadViewModel = hiltViewModel()
) {
    val downloads by viewModel.downloads.collectAsState()
    val details by viewModel.downloadDetails.collectAsState()
    val uploads by viewModel.uploads.collectAsState()
    val showDeleteConfirmation by viewModel.showDeleteConfirmation.collectAsState()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val active = downloads.count { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }
    val completed = downloads.count { it.status == DownloadStatus.COMPLETED }

    // Row under the D-pad cursor: X deletes it (the in-row buttons are touch-only — they sit
    // inside the focused row's bounds, out of reach of directional focus search).
    var focusedRow by remember { mutableStateOf<DownloadItemModel?>(null) }
    LaunchedEffect(Unit) {
        Gamepad.presses.collect { button ->
            if (button == GamepadButton.X) {
                // Re-read the live status: it may have changed since the row took focus.
                downloads.find { it.fileName == focusedRow?.fileName }?.let { row ->
                    if (row.status == DownloadStatus.COMPLETED || row.status == DownloadStatus.STOPPED ||
                        row.status == DownloadStatus.FAILED) {
                        viewModel.deleteDownloadWithConfirmation(row.fileName, row.status == DownloadStatus.COMPLETED)
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp, vertical = 12.dp)
    ) {
        // The section name already lives in the tabs; only the summary stays.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                stringResource(R.string.downloads_summary, active, completed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (downloads.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.downloads_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 6.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(downloads, key = { it.fileName }) { item ->
                    DownloadItem(
                        item = item,
                        details = details[item.fileName],
                        upload = uploads[item.fileName],
                        compact = isLandscape,
                        viewModel = viewModel,
                        onRowFocused = { focusedRow = it }
                    )
                }
            }
        }
    }

    showDeleteConfirmation?.let { fileName ->
        AlertDialog(
            modifier = Modifier.closeOnGamepadB { viewModel.cancelDeleteConfirmation() },
            onDismissRequest = { viewModel.cancelDeleteConfirmation() },
            title = { Text(text = stringResource(R.string.delete_download_title)) },
            text = { Text(text = stringResource(R.string.delete_download_message)) },
            confirmButton = {
                DialogButton(stringResource(R.string.delete_file), onClick = { viewModel.confirmDeleteRemoveFile(fileName) })
            },
            dismissButton = {
                DialogButton(stringResource(R.string.keep_file), onClick = { viewModel.confirmDeleteKeepFile(fileName) }, initialFocus = rememberInitialFocus())
            }
        )
    }
}
