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
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.model.DownloadStatus

@Composable
fun DownloadScreen(
    navController: NavController,
    viewModel: DownloadViewModel = hiltViewModel()
) {
    val downloads by viewModel.downloads.collectAsState()
    val details by viewModel.downloadDetails.collectAsState()
    val showDeleteConfirmation by viewModel.showDeleteConfirmation.collectAsState()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val active = downloads.count { it.status == DownloadStatus.DOWNLOADING }
    val completed = downloads.count { it.status == DownloadStatus.COMPLETED }

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
                        compact = isLandscape,
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    showDeleteConfirmation?.let { fileName ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelDeleteConfirmation() },
            title = { Text(text = stringResource(R.string.delete_download_title)) },
            text = { Text(text = stringResource(R.string.delete_download_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDeleteRemoveFile(fileName) }) {
                    Text(stringResource(R.string.delete_file))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.confirmDeleteKeepFile(fileName) }) {
                    Text(stringResource(R.string.keep_file))
                }
            }
        )
    }
}
