package com.cortinadev.dogmatix.ui.screens.settings.romm

import android.view.KeyEvent
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.ServerCertificate
import com.cortinadev.dogmatix.ui.components.DialogButton
import com.cortinadev.dogmatix.ui.components.closeOnGamepadB
import com.cortinadev.dogmatix.ui.components.rememberInitialFocus
import com.cortinadev.dogmatix.util.CertPin
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * "Trust this certificate?" for a RomM server whose certificate the device refuses: who it is
 * for, who issued it, its validity and SHA-256 fingerprint. Focus starts on Cancel (trusting is
 * the risky choice), B cancels, ▲ ▼ scroll the details when they do not fit.
 */
@Composable
fun CertificateDialog(
    certificate: ServerCertificate,
    changed: Boolean,
    onTrust: () -> Unit,
    onDismiss: () -> Unit
) {
    val cancelFocus = rememberInitialFocus()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val format = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val host = CertPin.hostOf(certificate.origin)
    val from = format.format(Date(certificate.notBefore))
    val until = format.format(Date(certificate.notAfter))
    val expired = CertPin.isExpired(certificate.notAfter, System.currentTimeMillis())

    AlertDialog(
        modifier = Modifier
            .closeOnGamepadB(onDismiss)
            .onPreviewKeyEvent { event ->
                val code = event.nativeKeyEvent.keyCode
                if (scroll.maxValue == 0 || (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN)) return@onPreviewKeyEvent false
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    scope.launch { scroll.animateScrollBy(if (code == KeyEvent.KEYCODE_DPAD_DOWN) 120f else -120f) }
                }
                true
            },
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (changed) R.string.romm_cert_changed_title else R.string.romm_cert_untrusted_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(if (changed) R.string.romm_cert_changed_message else R.string.romm_cert_untrusted_message, host))
                val detail = MaterialTheme.typography.bodySmall
                Text(stringResource(R.string.romm_cert_subject, CertPin.displayName(certificate.subject)), style = detail)
                Text(stringResource(R.string.romm_cert_issuer, CertPin.displayName(certificate.issuer)), style = detail)
                Text(
                    stringResource(if (expired) R.string.romm_cert_validity_expired else R.string.romm_cert_validity, from, until),
                    style = detail,
                    color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(stringResource(R.string.romm_cert_fingerprint, CertPin.format(certificate.fingerprint)), style = detail)
                if (!certificate.hostnameMatches) {
                    Text(stringResource(R.string.romm_cert_name_mismatch, host.substringBeforeLast(':')), style = detail, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            DialogButton(text = stringResource(R.string.romm_cert_trust), onClick = onTrust)
        },
        dismissButton = {
            DialogButton(text = stringResource(R.string.dialog_cancel), onClick = onDismiss, initialFocus = cancelFocus)
        }
    )
}
