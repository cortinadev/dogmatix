package com.cortinadev.dogmatix.ui.screens.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.DaijishoConfigService
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus

/**
 * The three values Daijishō's *Add an emulator* form needs, each with a Copy button.
 *
 * Daijishō has no importable configuration for players, so this is as far as automation goes:
 * Dogmatix has already written the shortcuts, and the entry itself is typed (or pasted) into
 * Daijishō by hand. Opens with the first Copy button focused so the D-pad lands inside the
 * dialog; B closes.
 */
@Composable
fun DaijishoSetupDialog(
    setup: DaijishoConfigService.Setup,
    onDismiss: () -> Unit
) {
    val firstCopy = rememberInitialFocus()

    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.daijisho_dialog_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.daijisho_dialog_body, setup.shortcutsWritten),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Field(stringResource(R.string.daijisho_field_name), setup.name, initialFocus = firstCopy)
                Field(stringResource(R.string.daijisho_field_arguments), setup.amStartArguments)
                Field(stringResource(R.string.daijisho_field_regex), setup.acceptedFilenameRegex)
            }
        },
        confirmButton = {
            DialogButton(text = stringResource(R.string.dialog_close), onClick = onDismiss)
        }
    )
}

@Composable
private fun Field(label: String, value: String, initialFocus: FocusRequester? = null) {
    // The Copy button owns the whole copy: the value appears once, so label and clipboard
    // can never drift apart.
    val clipboard = LocalClipboardManager.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        DialogButton(
            text = stringResource(R.string.dialog_copy),
            onClick = { clipboard.setText(AnnotatedString(value)) },
            initialFocus = initialFocus
        )
    }
}
