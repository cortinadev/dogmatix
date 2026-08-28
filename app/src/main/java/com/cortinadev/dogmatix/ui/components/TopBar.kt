package com.cortinadev.dogmatix.ui.components

import com.cortinadev.dogmatix.R

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.di.RescanStateEntryPoint
import dagger.hilt.android.EntryPointAccessors

/** Spinner + message while sources are being rescanned; renders nothing otherwise. */
@Composable
fun RescanIndicator(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val rescanStateHolder = EntryPointAccessors.fromApplication(
        context.applicationContext,
        RescanStateEntryPoint::class.java
    ).rescanStateHolder()

    val isRescanning by rescanStateHolder.isRescanning.collectAsState()
    val progressMessage by rescanStateHolder.progressMessage.collectAsState()
    val torrentFetchProgress by rescanStateHolder.torrentFetchProgress.collectAsState()

    if (!isRescanning) return

    val displayMessage = when {
        torrentFetchProgress.isNotEmpty() -> torrentFetchProgress
        progressMessage.isNotEmpty() -> progressMessage
        else -> stringResource(R.string.sources_rescanning_sources)
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = displayMessage,
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
