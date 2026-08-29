package com.cortinadev.dogmatix.ui.screens.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus

/**
 * Edits a secret (TorBox key, RomM token…). Opens with the field focused, B cancels;
 * [onTest] tries the value against the service without saving it.
 */
@Composable
fun ApiKeyDialog(
    title: String,
    hint: String,
    value: String,
    label: String = stringResource(R.string.api_key_label),
    onTest: ((String) -> Unit)? = null,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(value) }
    val fieldFocus = rememberInitialFocus()

    AlertDialog(
        modifier = Modifier.closeOnGamepadB(onDismiss),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(hint)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus)
                )
            }
        },
        confirmButton = {
            DialogButton(text = stringResource(R.string.dialog_save), onClick = { onSave(text.trim()); onDismiss() })
        },
        dismissButton = {
            if (onTest != null) DialogButton(text = stringResource(R.string.settings_test), enabled = text.isNotBlank(), onClick = { onTest(text.trim()) })
            DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss)
        }
    )
}

/** `abcd…wxyz` for the settings hint, or "Not set". */
@Composable
fun maskedSecret(value: String): String =
    if (value.isBlank()) stringResource(R.string.settings_not_set)
    else if (value.length <= 8) "••••" else value.take(4) + "••••" + value.takeLast(4)
