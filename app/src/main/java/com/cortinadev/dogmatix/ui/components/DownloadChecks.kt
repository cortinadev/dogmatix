package com.cortinadev.dogmatix.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.DownloadVerifier
import com.cortinadev.dogmatix.data.service.SourceStatsService
import com.cortinadev.dogmatix.di.DownloadChecksEntryPoint
import com.cortinadev.dogmatix.util.CheckResult
import com.cortinadev.dogmatix.util.CheckState
import com.cortinadev.dogmatix.util.DownloadCheck
import com.cortinadev.dogmatix.util.HashKind
import com.cortinadev.dogmatix.util.MismatchDetail
import com.cortinadev.dogmatix.util.SourceReliability
import com.cortinadev.dogmatix.util.UnknownReason
import dagger.hilt.android.EntryPointAccessors

@Composable
private fun rememberDownloadChecks(): DownloadChecksEntryPoint {
    val app = LocalContext.current.applicationContext
    return remember(app) { EntryPointAccessors.fromApplication(app, DownloadChecksEntryPoint::class.java) }
}

@Composable
fun rememberDownloadVerifier(): DownloadVerifier = rememberDownloadChecks().downloadVerifier()

@Composable
fun rememberSourceStats(): SourceStatsService = rememberDownloadChecks().sourceStats()

/** The latest checksum / DAT verdict of a download; null when it was never checked. */
@Composable
fun rememberCheckResult(fileName: String): CheckResult? {
    val results by rememberDownloadVerifier().results.collectAsState()
    return results[fileName]
}

/** The reference a verdict names, for display. */
@Composable
private fun againstLabel(against: String?): String = when {
    against == null -> ""
    against == DownloadCheck.ROMM -> stringResource(R.string.check_against_romm)
    against == DownloadCheck.IA -> stringResource(R.string.check_against_ia)
    against == DownloadCheck.NO_INTRO -> stringResource(R.string.check_against_no_intro)
    against == DownloadCheck.REDUMP -> stringResource(R.string.check_against_redump)
    against.startsWith(DownloadCheck.IMPORTED_PREFIX) -> stringResource(R.string.check_against_imported)
    else -> against
}

/** Short text for the detail line of a finished download: "Verified · No-Intro", "Checking…"… */
@Composable
fun checkLabel(result: CheckResult): String = when (result.state) {
    CheckState.CHECKING -> stringResource(R.string.check_checking)
    CheckState.VERIFIED -> if (result.total > result.verified)
        stringResource(R.string.check_verified_partial, againstLabel(result.against), result.verified, result.total)
    else stringResource(R.string.check_verified, againstLabel(result.against))
    CheckState.MISMATCH -> stringResource(R.string.check_mismatch, againstLabel(result.against))
    CheckState.UNKNOWN -> when (result.reason) {
        UnknownReason.NOT_LISTED -> stringResource(R.string.check_unknown_not_listed, againstLabel(result.against))
        UnknownReason.UNSUPPORTED -> stringResource(R.string.check_unknown_unsupported)
        UnknownReason.NOT_FOUND -> stringResource(R.string.check_unknown_not_found)
        UnknownReason.ERROR -> stringResource(R.string.check_unknown_error)
        UnknownReason.NO_REFERENCE, null -> stringResource(R.string.check_unknown_no_reference)
    }
}

/** What did not match: "Game (USA).gba: CRC32 is 1a2b3c4d, expected 9d4f1e18". */
@Composable
fun mismatchText(detail: MismatchDetail): String {
    val what = stringResource(
        when (detail.what) {
            HashKind.SIZE -> R.string.check_hash_size
            HashKind.CRC32 -> R.string.check_hash_crc32
            HashKind.MD5 -> R.string.check_hash_md5
            HashKind.SHA1 -> R.string.check_hash_sha1
        }
    )
    fun shown(value: String) = if (detail.what == HashKind.SIZE) value else value.take(12) + if (value.length > 12) "…" else ""
    return stringResource(R.string.check_mismatch_detail, DownloadCheck.leafOf(detail.file), what, shown(detail.actual), shown(detail.expected))
}

/** A discreet "97 % successful · 4.1 MB/s" under a source address, when its origin has a record. */
@Composable
fun SourceReliabilityLine(url: String, modifier: Modifier = Modifier) {
    val service = rememberSourceStats()
    val stats by service.stats.collectAsState()
    val romm by service.rommBaseUrl.collectAsState()
    val summary = remember(stats, romm, url) {
        service.originOfSource(url)?.let { SourceReliability.summary(stats[it], System.currentTimeMillis()) }
    } ?: return
    val locale = LocalConfiguration.current.locales[0]
    val (percent, speed) = summary
    Text(
        text = speed?.let { stringResource(R.string.sources_url_reliability, percent, SourceReliability.speedText(it, locale)) }
            ?: stringResource(R.string.sources_url_reliability_no_speed, percent),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}
