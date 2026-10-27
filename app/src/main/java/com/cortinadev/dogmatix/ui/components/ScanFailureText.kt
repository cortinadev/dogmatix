package com.cortinadev.dogmatix.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.util.IaSource
import com.cortinadev.dogmatix.util.ScanFailure

/** Why a source could not be read, in words (the scan report and "Test" in the source dialog). */
@Composable
fun scanFailureText(failure: ScanFailure?, httpCode: Int?, detail: String?): String {
    val reason = stringResource(
        when (failure) {
            ScanFailure.RATE_LIMITED -> R.string.scan_failure_rate_limited
            ScanFailure.SERVER -> R.string.scan_failure_server
            ScanFailure.TIMEOUT -> R.string.scan_failure_timeout
            ScanFailure.CONNECTION -> R.string.scan_failure_connection
            ScanFailure.UNREACHABLE -> R.string.scan_failure_unreachable
            ScanFailure.OFFLINE -> R.string.scan_failure_offline
            ScanFailure.SECURE -> R.string.scan_failure_secure
            ScanFailure.NOT_FOUND -> R.string.scan_failure_not_found
            ScanFailure.DENIED -> R.string.scan_failure_denied
            ScanFailure.HTTP -> R.string.scan_failure_http
            ScanFailure.NO_LISTING -> R.string.scan_failure_no_listing
            ScanFailure.EMPTY -> R.string.scan_failure_empty
            ScanFailure.TORRENT_METADATA -> R.string.scan_failure_torrent_metadata
            ScanFailure.OTHER, null -> R.string.scan_failure_other
        }
    )
    val withCode = httpCode?.let { stringResource(R.string.scan_failure_with_code, reason, it) } ?: reason
    // archive.org refused the saved session: it has most likely expired.
    if (detail == IaSource.SESSION_EXPIRED_DETAIL) return "$withCode · ${stringResource(R.string.ia_session_expired)}"
    // Errors we have no words for (RomM set-up, odd answers, a page recipe) carry their own message.
    return if ((failure == ScanFailure.OTHER || failure == null) && !detail.isNullOrBlank()) "$withCode: $detail" else withCode
}
