package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadStatus

/**
 * Bringing the Downloads list back after the process died: which rows were running (they start
 * again once the app is open, in their places in line), what every row comes back as, and when
 * the automatic retries that were pending run.
 */
object DownloadRestore {

    /** Running (or waiting for its turn) when the process died. */
    fun wasInFlight(status: DownloadStatus): Boolean =
        status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING ||
            status == DownloadStatus.COPYING || status == DownloadStatus.UNZIPPING

    /**
     * In-flight rows come back STOPPED until they are started again. A paused torrent comes back
     * STOPPED too: the startup sweep clears the cache its resume relied on. A paused web download
     * keeps its partial file, so it stays paused.
     */
    fun statusAfterRestart(status: DownloadStatus, isTorrent: Boolean): DownloadStatus = when {
        wasInFlight(status) -> DownloadStatus.STOPPED
        status == DownloadStatus.PAUSED && isTorrent -> DownloadStatus.STOPPED
        else -> status
    }

    /**
     * A paused web download without a resume record starts again from zero when it is resumed:
     * its server could not carry it on (see [HttpResume.canResume]), or it was paused before the
     * first answer. The row says so after a restart as well. [status] is the one it came back as
     * ([statusAfterRestart]): torrents and debrid rows come back stopped and never say it.
     */
    fun restartsFromZero(status: DownloadStatus, isTorrent: Boolean, hasResumeRecord: Boolean): Boolean =
        status == DownloadStatus.PAUSED && !isTorrent && !hasResumeRecord

    /** An automatic retry saved with its row: due at [retryAt], [attemptsDone] retries into its chain. */
    data class SavedRetry(val fileName: String, val retryAt: Long, val attemptsDone: Int, val failure: DownloadFailure?)

    /**
     * When each saved retry runs now (epoch millis, one per file): what was left of its wait, at
     * once when it is overdue, and never later than a fresh wait would be (the clock may have
     * gone back). Dropped when automatic retries were turned off, or lowered below the chain.
     */
    fun pendingRetries(saved: List<SavedRetry>, maxAttempts: Int, now: Long): Map<String, Long> =
        saved.filter { AutoRetryPolicy.shouldRetry(it.failure, it.attemptsDone, maxAttempts) }
            .associate { it.fileName to it.retryAt.coerceIn(now, now + AutoRetryPolicy.delayMs(it.attemptsDone)) }
}
