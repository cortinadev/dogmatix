package com.cortinadev.dogmatix.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.cortinadev.dogmatix.util.DownloadFailures
import com.cortinadev.dogmatix.util.OriginStats

/**
 * Track record of one download origin (a host, a debrid service, a torrent), see
 * [com.cortinadev.dogmatix.util.SourceReliability]. The counts are decayed weights.
 */
@Entity(tableName = "source_stats")
data class SourceStatEntity(
    @PrimaryKey val origin: String,
    val successes: Double,
    val failures: Double,
    val hardFailures: Double,
    val bytesPerSecond: Double,
    val totalBytes: Long,
    /** A [com.cortinadev.dogmatix.util.DownloadFailure] name; null after successes only. */
    val lastFailure: String?,
    val lastFailureAt: Long,
    val lastSuccessAt: Long,
    val updatedAt: Long
) {
    fun toStats() = OriginStats(
        successes = successes, failures = failures, hardFailures = hardFailures,
        bytesPerSecond = bytesPerSecond, totalBytes = totalBytes,
        lastFailure = DownloadFailures.fromName(lastFailure),
        lastFailureAt = lastFailureAt, lastSuccessAt = lastSuccessAt, updatedAt = updatedAt
    )

    companion object {
        fun of(origin: String, s: OriginStats) = SourceStatEntity(
            origin, s.successes, s.failures, s.hardFailures, s.bytesPerSecond, s.totalBytes,
            s.lastFailure?.name, s.lastFailureAt, s.lastSuccessAt, s.updatedAt
        )
    }
}

/**
 * A web transfer that started and has not finished yet: what its first response said, so the
 * next attempt (or the next run) can continue the partial file with `Range` + `If-Range`. Kept
 * apart from `download_history`, whose row is replaced whenever a download is started again.
 */
@Entity(tableName = "download_resume")
data class DownloadResumeEntity(
    @PrimaryKey val fileName: String,
    /** Strong ETag or Last-Modified of the first response; null when it gave neither. */
    val validator: String?,
    /** Origin that gave [validator] (see DownloadOrigins): only that server can honour it. */
    val host: String?,
    /** Full length of the file; -1 = not given. */
    val totalBytes: Long,
    val updatedAt: Long
)

/** The checksum / DAT verdict on a finished download, a [com.cortinadev.dogmatix.util.CheckResult] as JSON. */
@Entity(tableName = "download_checks")
data class DownloadCheckEntity(
    @PrimaryKey val fileName: String,
    val result: String,
    val checkedAt: Long
)
