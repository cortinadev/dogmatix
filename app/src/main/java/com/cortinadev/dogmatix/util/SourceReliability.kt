package com.cortinadev.dogmatix.util

import java.util.Locale
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * How downloads from one origin went lately. The counts are weights that halve every
 * [SourceReliability.HALF_LIFE_MS], so an outage from last month weighs little against
 * this week's downloads; [updatedAt] is when they were last brought up to date.
 */
data class OriginStats(
    val successes: Double = 0.0,
    val failures: Double = 0.0,
    /** The failures that do not go away on their own (file gone, access denied, a web page instead of the file…). */
    val hardFailures: Double = 0.0,
    /** Weighted average speed of the transfers long enough to tell, bytes/s; 0 = not known yet. */
    val bytesPerSecond: Double = 0.0,
    val totalBytes: Long = 0L,
    val lastFailure: DownloadFailure? = null,
    val lastFailureAt: Long = 0L,
    val lastSuccessAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    val attempts: Double get() = successes + failures
}

/**
 * What a download attempt is recorded under: the host of a web address (a source or one of its
 * mirrors; RomM is a web address too), the debrid service, or a torrent by its info-hash.
 */
object DownloadOrigins {

    fun forDebrid(provider: String): String = "debrid:" + provider.lowercase(Locale.ROOT)

    /** A magnet by its info-hash; a `.torrent` address (no hash in it) by the address itself. */
    fun forTorrent(magnetOrUrl: String): String =
        "torrent:" + (DebridMatcher.infoHashFromMagnet(magnetOrUrl) ?: magnetOrUrl.trim())

    /** The host of a web address, without `www.`; null for anything else. */
    fun forWeb(url: String): String? = MirrorUrls.hostOf(url)?.removePrefix("www.")

    /** Where an indexed file comes from (what the version ranking breaks ties with). */
    fun forFile(downloadUrl: String, torrentMagnet: String?): String? =
        if (torrentMagnet != null) forTorrent(torrentMagnet) else forWeb(downloadUrl)

    /**
     * A source address as typed in Sources: torrents the way the scraper recognises them,
     * `romm://` by the host of the RomM server ([rommBaseUrl], empty when not set up).
     */
    fun forSourceUrl(url: String, rommBaseUrl: String): String? {
        val u = url.trim()
        return when {
            u.startsWith("magnet:", ignoreCase = true) || u.endsWith(".torrent", ignoreCase = true) -> forTorrent(u)
            RommSource.isSource(u) -> forWeb(rommBaseUrl)
            else -> forWeb(u)
        }
    }
}

/** Track record arithmetic and the order of a download's alternatives. Pure, for the tests. */
object SourceReliability {

    const val HALF_LIFE_MS = 14L * 24 * 60 * 60 * 1000
    /** Weight of a new speed sample in the average. */
    const val SPEED_WEIGHT = 0.3
    /** Shorter transfers say little about a server's speed (handshakes, tiny files). */
    const val MIN_SAMPLE_BYTES = 1L * 1024 * 1024
    const val MIN_SAMPLE_MS = 1_000L
    /** A hard failure as the latest outcome puts the origin last for this long. */
    const val DEMOTE_MS = 12L * 60 * 60 * 1000
    /** Below this the stats are noise left by decay: nothing to show. */
    private const val MIN_ATTEMPTS_SHOWN = 0.05

    /** The device's own trouble (a full disk, a folder without access) says nothing about the origin. */
    fun countsAgainstOrigin(failure: DownloadFailure): Boolean =
        failure != DownloadFailure.NO_SPACE && failure != DownloadFailure.STORAGE

    /** [stats] with its weights decayed up to [now]. */
    fun decayed(stats: OriginStats, now: Long): OriginStats {
        val elapsed = now - stats.updatedAt
        if (elapsed <= 0L) return stats
        val factor = 0.5.pow(elapsed.toDouble() / HALF_LIFE_MS)
        return stats.copy(
            successes = stats.successes * factor,
            failures = stats.failures * factor,
            hardFailures = stats.hardFailures * factor,
            updatedAt = now
        )
    }

    /** One attempt: [failure] null for a success. [bytes] moved in [millis] (both 0 when nothing was transferred). */
    fun withAttempt(stats: OriginStats?, failure: DownloadFailure?, bytes: Long, millis: Long, now: Long): OriginStats {
        val s = decayed(stats ?: OriginStats(updatedAt = now), now)
        val sample = if (bytes >= MIN_SAMPLE_BYTES && millis >= MIN_SAMPLE_MS) bytes * 1000.0 / millis else null
        val speed = when {
            sample == null -> s.bytesPerSecond
            s.bytesPerSecond <= 0.0 -> sample
            else -> s.bytesPerSecond + SPEED_WEIGHT * (sample - s.bytesPerSecond)
        }
        val moved = s.totalBytes + bytes.coerceAtLeast(0L)
        return if (failure == null) {
            s.copy(successes = s.successes + 1, bytesPerSecond = speed, totalBytes = moved, lastSuccessAt = now)
        } else {
            s.copy(
                failures = s.failures + 1,
                hardFailures = s.hardFailures + if (failure.transient) 0.0 else 1.0,
                bytesPerSecond = speed, totalBytes = moved,
                lastFailure = failure, lastFailureAt = now
            )
        }
    }

    /**
     * Success rate with one imagined success and one failure added, so an origin never tried
     * counts as 0.5, one good download as 0.67 and one bad one as 0.33.
     */
    fun reliability(stats: OriginStats?, now: Long): Double {
        val s = stats?.let { decayed(it, now) } ?: return 0.5
        return (s.successes + 1.0) / (s.attempts + 2.0)
    }

    /** The latest outcome was a failure that will not fix itself, a short while ago. */
    fun isDemoted(stats: OriginStats?, now: Long): Boolean =
        stats != null && stats.lastFailure?.transient == false &&
            stats.lastFailureAt > stats.lastSuccessAt && now - stats.lastFailureAt < DEMOTE_MS

    /**
     * [items] best first: recently broken origins last, then by reliability in steps of 10 % (within
     * a step the faster one wins), and otherwise in the order given. Without any history the order
     * does not change.
     */
    fun <T> order(items: List<T>, originOf: (T) -> String?, stats: Map<String, OriginStats>, now: Long): List<T> {
        if (items.size < 2 || items.none { item -> originOf(item)?.let { stats[it] } != null }) return items
        fun of(item: T) = originOf(item)?.let { stats[it] }
        return items.withIndex().sortedWith(
            compareBy<IndexedValue<T>> { isDemoted(of(it.value), now) }
                .thenByDescending { floor(reliability(of(it.value), now) * 10).toInt() }
                .thenByDescending { of(it.value)?.bytesPerSecond ?: 0.0 }
                .thenBy { it.index }
        ).map { it.value }
    }

    /** Share of attempts that worked (0..100) and the average speed; null when there is nothing to show. */
    fun summary(stats: OriginStats?, now: Long): Pair<Int, Double?>? {
        val s = stats?.let { decayed(it, now) } ?: return null
        if (s.attempts < MIN_ATTEMPTS_SHOWN) return null
        return (s.successes * 100.0 / s.attempts).roundToInt() to s.bytesPerSecond.takeIf { it > 0.0 }
    }

    /** "4.1 MB/s" / "820 KB/s" with the decimal separator of [locale]. */
    fun speedText(bytesPerSecond: Double, locale: Locale): String {
        val mb = bytesPerSecond / (1024 * 1024)
        return if (mb >= 1.0) String.format(locale, if (mb >= 10.0) "%.0f MB/s" else "%.1f MB/s", mb)
        else String.format(locale, "%.0f KB/s", (bytesPerSecond / 1024).coerceAtLeast(1.0))
    }
}
