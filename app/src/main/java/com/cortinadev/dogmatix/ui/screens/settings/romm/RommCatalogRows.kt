package com.cortinadev.dogmatix.ui.screens.settings.romm

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.FrontendSetupException
import com.cortinadev.dogmatix.data.service.RommEsdeCoverService
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.focusRing
import com.cortinadev.dogmatix.ui.components.rememberFocusSource
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.screens.settings.PillButton
import com.cortinadev.dogmatix.ui.screens.settings.SettingRow
import com.cortinadev.dogmatix.ui.screens.settings.ThemedSwitch
import com.cortinadev.dogmatix.util.ToastUtil
import java.text.DateFormat
import java.util.Date

/**
 * The rows Settings → RomM gains for the server catalogue: read it again, upload the games on
 * disk the server lacks, keep the favourites in step and copy the covers into ES-DE. Their
 * dialogs are composed here too, so the screen only adds the rows to its grid.
 */
@Composable
fun rememberRommCatalogRows(viewModel: RommCatalogViewModel = hiltViewModel()): List<@Composable () -> Unit> {
    val ui by viewModel.ui.collectAsState()
    val missing by viewModel.missing.collectAsState()
    val coversOutcome by viewModel.coversOutcome.collectAsState()
    val context = LocalContext.current
    var confirmCovers by remember { mutableStateOf(false) }

    val esdeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            viewModel.onEsdeDirPicked(context, it.toString())
            confirmCovers = true
        }
    }
    val unconfigured = stringResource(R.string.romm_catalog_unconfigured)
    // Every action needs the catalogue: without URL, token and a mapped console they only say so.
    fun whenConfigured(action: () -> Unit) {
        if (ui.catalog.configured) action() else ToastUtil.showInfo(context, unconfigured)
    }
    // The ES-DE folder is the one "Configure ES-DE" uses; asked for here the first time.
    fun startCovers() = whenConfigured {
        if (ui.esdeDirectory.isBlank()) esdeLauncher.launch(null) else confirmCovers = true
    }
    fun refresh() = whenConfigured(viewModel::refresh)
    fun checkMissing() = whenConfigured(viewModel::checkMissing)

    missing?.let { MissingDialog(it, onUpload = viewModel::uploadMissing, onDismiss = viewModel::dismissMissing) }
    if (confirmCovers) {
        CoversDialog(
            onWrite = { replace -> confirmCovers = false; viewModel.writeCovers(replace) },
            onDismiss = { confirmCovers = false }
        )
    }
    coversOutcome?.let { CoversOutcomeDialog(it, onDismiss = viewModel::consumeCoversOutcome) }

    val catalog = ui.catalog
    val catalogHint = when {
        !catalog.configured -> stringResource(R.string.romm_catalog_unconfigured)
        catalog.refreshing -> stringResource(R.string.romm_catalog_refreshing)
        catalog.error != null -> stringResource(R.string.romm_catalog_error, catalog.error)
        catalog.refreshedAt > 0 -> stringResource(R.string.romm_catalog_hint, catalog.games, timeOf(catalog.refreshedAt))
        else -> stringResource(R.string.romm_catalog_never)
    }
    val uploads = ui.uploads
    val missingHint = when {
        uploads != null && uploads.uploading > 0 ->
            stringResource(R.string.romm_uploading, (uploads.done + uploads.failed + 1).coerceAtMost(uploads.total), uploads.total)
        uploads != null -> stringResource(R.string.romm_uploaded_summary, uploads.done, uploads.failed)
        else -> stringResource(R.string.romm_missing_hint)
    }
    val favourites = ui.favourites
    val favouritesHint = when {
        !ui.favouritesSync -> stringResource(R.string.romm_fav_sync_hint)
        favourites.running -> stringResource(R.string.romm_fav_sync_running)
        favourites.error != null -> stringResource(R.string.romm_fav_sync_error, favourites.error)
        favourites.syncedAt > 0 -> stringResource(
            R.string.romm_fav_sync_done, timeOf(favourites.syncedAt),
            favourites.starred, favourites.unstarred, favourites.added, favourites.removed
        )
        else -> stringResource(R.string.romm_fav_sync_hint)
    }
    val covers = ui.covers
    val coversHint = if (covers != null && covers.total > 0) stringResource(R.string.romm_covers_progress, covers.done, covers.total)
        else stringResource(R.string.romm_covers_esde_hint)

    return listOf(
        {
            SettingRow(title = stringResource(R.string.romm_catalog), hint = catalogHint, onClick = ::refresh) {
                PillButton(stringResource(R.string.romm_refresh), ::refresh)
            }
        },
        {
            SettingRow(title = stringResource(R.string.romm_missing), hint = missingHint, onClick = ::checkMissing) {
                PillButton(stringResource(R.string.romm_missing_check), ::checkMissing)
            }
        },
        {
            SettingRow(
                title = stringResource(R.string.romm_fav_sync),
                hint = favouritesHint,
                onClick = { viewModel.setFavouritesSync(context, !ui.favouritesSync) },
                onAdjust = { viewModel.setFavouritesSync(context, it > 0) }
            ) {
                ThemedSwitch(ui.favouritesSync) { viewModel.setFavouritesSync(context, it) }
            }
        },
        {
            SettingRow(title = stringResource(R.string.romm_covers_esde), hint = coversHint, onClick = ::startCovers) {
                PillButton(stringResource(R.string.romm_covers_write), ::startCovers)
            }
        }
    )
}

private fun timeOf(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

/** Checking, then the games on disk the server lacks by platform; nothing is uploaded until "Upload". */
@Composable
private fun MissingDialog(state: RommMissingState, onUpload: () -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // A mass upload can be many gigabytes: the dialog opens on Cancel.
    val cancelFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.romm_missing)) },
        text = {
            when (state) {
                RommMissingState.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.romm_missing_checking))
                }
                is RommMissingState.Failed -> Text(stringResource(R.string.romm_missing_failed, state.message))
                is RommMissingState.Ready -> if (state.games == 0) Text(stringResource(R.string.romm_missing_none)) else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pluralStringResource(R.plurals.romm_missing_found, state.games, state.games))
                    // Lines take the focus one by one so the D-pad can scroll the list.
                    LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                        state.groups.forEach { group ->
                            item(key = "h:" + group.console) {
                                Text(group.console, style = MaterialTheme.typography.labelLarge, color = scheme.primary, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                            }
                            items(group.games) { name ->
                                val source = rememberFocusSource()
                                Text(
                                    name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .focusRing(source, 6.dp)
                                        .focusable(interactionSource = source)
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val ready = state as? RommMissingState.Ready
            if (ready != null && ready.games > 0) DialogButton(text = stringResource(R.string.romm_missing_upload), onClick = onUpload)
        },
        dismissButton = {
            DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus)
        }
    )
}

/** What the covers batch does, with the choice of replacing what ES-DE already shows. */
@Composable
private fun CoversDialog(onWrite: (replace: Boolean) -> Unit, onDismiss: () -> Unit) {
    var replace by remember { mutableStateOf(false) }
    val writeFocus = rememberInitialFocus()
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.romm_covers_esde)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.romm_covers_dialog_body))
                val source = rememberFocusSource()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .focusRing(source)
                        .clickable(interactionSource = source, indication = null) { replace = !replace }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.romm_covers_replace), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    ThemedSwitch(replace) { replace = it }
                }
            }
        },
        confirmButton = {
            DialogButton(text = stringResource(R.string.romm_covers_write), onClick = { onWrite(replace) }, initialFocus = writeFocus)
        },
        dismissButton = { DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss) }
    )
}

@Composable
private fun CoversOutcomeDialog(outcome: RommEsdeCoverService.Outcome, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val closeFocus = rememberInitialFocus()
    val message = when (outcome) {
        is RommEsdeCoverService.Outcome.Done -> with(outcome.result) {
            if (written + kept + failed == 0) stringResource(R.string.romm_covers_none)
            else stringResource(R.string.romm_covers_result, written, kept, failed)
        }
        is RommEsdeCoverService.Outcome.Failed -> when (val e = outcome.error) {
            is FrontendSetupException -> e.userMessage(context)
            is RommEsdeCoverService.NoEsdeFolderException -> stringResource(R.string.frontend_folder_not_writable)
            else -> stringResource(R.string.romm_covers_failed, e.message ?: e.javaClass.simpleName)
        }
    }
    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.romm_covers_esde)) },
        text = { Text(message) },
        confirmButton = { DialogButton(text = stringResource(R.string.details_close), onClick = onDismiss, initialFocus = closeFocus) }
    )
}
