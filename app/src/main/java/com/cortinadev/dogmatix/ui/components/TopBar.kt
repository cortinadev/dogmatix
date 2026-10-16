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

/** Progress ring + percentage, time left and message while sources are being rescanned; renders nothing otherwise. */
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
    val progress by rescanStateHolder.progress.collectAsState()

    if (!isRescanning) return

    val message = when {
        torrentFetchProgress.isNotEmpty() -> torrentFetchProgress
        progressMessage.isNotEmpty() -> progressMessage
        else -> stringResource(R.string.sources_rescanning_sources)
    }
    // Percentage and time left first: the message is what gets cut when space runs out.
    val displayMessage = progress?.let { p ->
        listOfNotNull(scanPercentText(p.fraction), p.etaMs?.let { scanEtaText(it) }, message).joinToString(" · ")
    } ?: message

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        val fraction = progress?.fraction
        if (fraction != null) {
            CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
        }
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
