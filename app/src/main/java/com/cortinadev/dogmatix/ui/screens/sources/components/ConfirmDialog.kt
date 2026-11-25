package com.cortinadev.dogmatix.ui.screens.sources.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.WideDialogProperties
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.ui.components.wideDialog

/**
 * Yes / no confirmation for destructive actions. Focus starts on Cancel so a stray A press
 * on the gamepad does nothing; B cancels as well. An [otherText] action goes left of Cancel
 * (◀ from it, as the confirmation is ▶) and does not close the dialog by itself; with it the
 * dialog is wider, so the three buttons stay in one row (in a wrapped row ◀ ▶ lose their way).
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    otherText: String? = null,
    onOther: () -> Unit = {}
) {
    val cancelFocus = rememberInitialFocus()
    val wide = otherText != null
    AlertDialog(
        modifier = (if (wide) Modifier.wideDialog(max = 560.dp) else Modifier).closeOnGamepadB(onDismiss),
        properties = if (wide) WideDialogProperties else DialogProperties(),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            DialogButton(text = confirmText, onClick = { onConfirm(); onDismiss() })
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                otherText?.let { DialogButton(text = it, onClick = onOther) }
                DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus)
            }
        }
    )
}
