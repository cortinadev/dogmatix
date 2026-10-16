package com.cortinadev.dogmatix.data.local.entity

import androidx.room.Entity

/**
 * The last scan of one source (a console's URL entry): what the incremental rescan needs to
 * recognise an unchanged source ([fingerprint], the listing validators, [contentHash],
 * [fileCount]) and what the scan report shows ([status], [failure], [newFiles]).
 * A failed scan leaves the validators of the last good one: the rows it wrote are still there.
 */
@Entity(tableName = "source_scans", primaryKeys = ["consoleId", "sourceUrl"])
data class SourceScanEntity(
    val consoleId: String,
    val sourceUrl: String,
    /** [com.cortinadev.dogmatix.util.SourceScanCheck.fingerprint] of the set-up the rows were built with. */
    val fingerprint: String,
    val etag: String?,
    val lastModified: String?,
    /** The mirror that answered, when it was not the source's own address. */
    val servedBy: String?,
    val contentHash: String?,
    /** Files the last successful scan wrote; -1 when none ever succeeded. */
    val fileCount: Int,
    val newFiles: Int,
    /** A [com.cortinadev.dogmatix.data.service.SourceScanStatus] name. */
    val status: String,
    /** A [com.cortinadev.dogmatix.util.ScanFailure] name when [status] is FAILED. */
    val failure: String?,
    val httpCode: Int?,
    val detail: String?,
    val scannedAt: Long,
    /** Start time of the scan run this result belongs to: the report shows the latest run. */
    val runId: Long,
    /** A [com.cortinadev.dogmatix.data.service.ScanKind] name. */
    val runKind: String
)
