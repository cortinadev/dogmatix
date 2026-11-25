package com.cortinadev.dogmatix.ui.screens.sources.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.WideDialogProperties
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.components.wideDialog
import com.cortinadev.dogmatix.ui.screens.sources.UrlImportState
import com.cortinadev.dogmatix.util.SourcesUrl
import com.cortinadev.dogmatix.util.SourcesUrlProblem

/**
 * Import sources from a web address (Sources → Import → From URL, and the onboarding's sources
 * step). Opens with the address field focused, holding the last address used; B or Cancel close it
 * (and stop a download under way). Import, or Done on the keyboard, downloads it; an error stays in
 * the dialog with the address as typed, and [onImported] runs once the sources were replaced.
 *
 * The progress or the error goes right under the field, as part of it: with the keyboard open the
 * window only slides up as far as the focused field, so anything further down stays hidden. In
 * landscape (on the K56 the keyboard leaves ~125 dp) the buttons go on the field's row for the same
 * reason, and the message is held to three small lines so the field still fits above the keyboard
 * (a field taller than that space is not slid into view at all).
 */
@Composable
fun ImportUrlDialog(
    lastUrl: String,
    state: UrlImportState,
    onImport: (String) -> Unit,
    onEdited: () -> Unit,
    onImported: () -> Unit,
    onDismiss: () -> Unit
) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(lastUrl, TextRange(lastUrl.length))) }
    // The last address can still be loading when the dialog opens: it fills a field nobody touched.
    LaunchedEffect(lastUrl) {
        if (field.text.isEmpty() && lastUrl.isNotEmpty()) field = TextFieldValue(lastUrl, TextRange(lastUrl.length))
    }
    LaunchedEffect(state) { if (state == UrlImportState.Done) onImported() }
    val busy = state == UrlImportState.Downloading || state == UrlImportState.Importing
    // Landscape: on a handheld the keyboard leaves little more than the field's height above it.
    val compact = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fieldFocus = rememberInitialFocus()
    val cancelFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val start = {
        if (!busy && field.text.isNotBlank()) {
            keyboard?.hide()
            onImport(field.text)
        }
    }
    val messageStyle = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    // Text only: nothing here takes the focus.
    val status: (@Composable () -> Unit)? = when (state) {
        UrlImportState.Downloading, UrlImportState.Importing -> {
            {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(if (state == UrlImportState.Importing) R.string.sources_import_url_replacing else R.string.sources_import_url_downloading),
                        style = messageStyle
                    )
                }
            }
        }
        is UrlImportState.Failed -> {
            { Text(failureText(state), style = messageStyle, maxLines = if (compact) 3 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis) }
        }
        else -> null
    }

    @Composable
    fun AddressField(modifier: Modifier) = OutlinedTextField(
        value = field,
        onValueChange = {
            if (it.text != field.text) onEdited()
            field = it
        },
        label = { Text(stringResource(R.string.sources_import_url_label)) },
        placeholder = { Text(stringResource(R.string.sources_import_url_placeholder)) },
        supportingText = status,
        singleLine = true,
        isError = state is UrlImportState.Failed,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { start() }),
        modifier = modifier.focusRequester(fieldFocus)
    )

    // Stays enabled while busy (the line under the field says what is going on): a button that turns
    // disabled under the cursor drops the focus, and the D-pad would have nowhere to go.
    @Composable
    fun ImportButton() = DialogButton(
        text = stringResource(R.string.sources_import_url_action),
        onClick = start,
        enabled = field.text.isNotBlank()
    )

    @Composable
    fun CancelButton() = DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus)

    AlertDialog(
        modifier = Modifier.wideDialog(max = if (compact) 720.dp else 640.dp).closeOnGamepadB(onDismiss),
        properties = WideDialogProperties,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sources_import_url_title)) },
        text = {
            Column {
                Text(stringResource(R.string.sources_import_url_hint))
                Spacer(modifier = Modifier.height(12.dp))
                if (compact) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // ▶ reaches the buttons on its own; ▼ has nothing below, so it goes to Cancel too.
                        AddressField(Modifier.weight(1f).focusProperties { down = cancelFocus })
                        // Level with the box of the field (its floating label sits 8 dp above it).
                        Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CancelButton()
                            ImportButton()
                        }
                    }
                } else {
                    AddressField(Modifier.fillMaxWidth())
                }
            }
        },
        // In landscape the buttons are on the field's row.
        confirmButton = { if (!compact) ImportButton() },
        dismissButton = if (compact) null else ({ CancelButton() })
    )
}

@Composable
private fun failureText(failure: UrlImportState.Failed): String = when (failure.problem) {
    SourcesUrlProblem.INVALID_ADDRESS -> stringResource(R.string.sources_import_url_invalid)
    SourcesUrlProblem.UNREACHABLE -> stringResource(R.string.sources_import_url_unreachable)
    SourcesUrlProblem.HTTP -> stringResource(R.string.sources_import_url_http, failure.httpCode)
    SourcesUrlProblem.TOO_LARGE -> stringResource(R.string.sources_import_url_too_large, SourcesUrl.MAX_MB)
    SourcesUrlProblem.NOT_SOURCES -> stringResource(R.string.sources_import_url_not_sources)
    SourcesUrlProblem.BUSY -> stringResource(R.string.sources_import_url_busy)
    SourcesUrlProblem.UNREADABLE -> stringResource(R.string.sources_import_file_unreadable)
    SourcesUrlProblem.OTHER -> stringResource(R.string.sources_import_failed, failure.detail.orEmpty())
}
