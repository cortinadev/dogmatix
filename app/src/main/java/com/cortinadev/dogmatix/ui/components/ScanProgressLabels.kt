package com.cortinadev.dogmatix.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.util.ScanEtaFormat

/** "42%" for a scan's progress (0..1). */
@Composable
fun scanPercentText(fraction: Float): String = stringResource(R.string.scan_percent, (fraction * 100).toInt().coerceIn(0, 100))

/** "~3 min left", "~1 h 20 min left", "~<1 min left". */
@Composable
fun scanEtaText(etaMs: Long): String {
    val duration = when (val hm = ScanEtaFormat.hoursAndMinutes(etaMs)) {
        null -> stringResource(R.string.scan_duration_less_minute)
        else -> if (hm.first == 0) stringResource(R.string.scan_duration_minutes, hm.second)
                else stringResource(R.string.scan_duration_hours, hm.first, hm.second)
    }
    return stringResource(R.string.scan_eta, duration)
}
