package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.local.entity.DownloadHistoryEntity
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.util.DownloadRestore.SavedRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRestoreTest {

    private val minute = 60_000L
    private val now = 1_800_000_000_000L

    private fun row(status: DownloadStatus, torrent: Boolean = false, held: Boolean = false, order: Long = 0) = DownloadHistoryEntity(
        fileName = "Game (Europe).zip", name = "Game", consoleId = "snes", downloadUrl = "https://example.org/Game.zip",
        fileSize = 1000, fileExtension = "zip", torrentFileIndex = if (torrent) 3 else null,
        torrentMagnet = if (torrent) "magnet:?xt=urn:btih:abc" else null, status = status.name,
        startedAt = 1L, finishedAt = null, queueOrder = order, queueHeld = held
    )

    @Test
    fun whatWasRunningStartsAgainAndComesBackStoppedMeanwhile() {
        for (status in listOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.COPYING, DownloadStatus.UNZIPPING)) {
            assertTrue(row(status).wasInFlight)
            assertEquals(DownloadStatus.STOPPED, row(status).toItem().status)
        }
        for (status in listOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.STOPPED, DownloadStatus.PAUSED)) {
            assertFalse(row(status).wasInFlight)
        }
        // A held row waiting in line is in flight too: it goes back to the queue, still held.
        assertTrue(row(DownloadStatus.DOWNLOADING, held = true).wasInFlight)
    }

    @Test
    fun aPausedWebDownloadStaysPausedAndAPausedTorrentComesBackStopped() {
        // The web partial is still in its folder; the torrent's cache is swept at startup.
        assertEquals(DownloadStatus.PAUSED, row(DownloadStatus.PAUSED).toItem().status)
        assertEquals(DownloadStatus.STOPPED, row(DownloadStatus.PAUSED, torrent = true).toItem().status)
        assertEquals(DownloadStatus.COMPLETED, row(DownloadStatus.COMPLETED).toItem().status)
        assertEquals(1f, row(DownloadStatus.COMPLETED).toItem().progress)
        assertEquals(DownloadStatus.FAILED, row(DownloadStatus.FAILED, torrent = true).toItem().status)
    }

    @Test
    fun newRowsCarryNoPendingRetry() {
        val fresh = row(DownloadStatus.DOWNLOADING)
        assertEquals(null, fresh.retryAt)
        assertEquals(0, fresh.retryCount)
    }

    @Test
    fun aPendingRetryRunsWithWhatWasLeftOfItsWait() {
        val saved = listOf(
            SavedRetry("later", retryAt = now + 3 * minute, attemptsDone = 1, failure = DownloadFailure.NETWORK),
            SavedRetry("overdue", retryAt = now - 40 * minute, attemptsDone = 0, failure = DownloadFailure.SERVER)
        )
        val plans = DownloadRestore.pendingRetries(saved, maxAttempts = 3, now = now)
        assertEquals(now + 3 * minute, plans["later"])
        // Overdue while the app was closed: at once.
        assertEquals(now, plans["overdue"])
    }

    @Test
    fun neverLaterThanAFreshWait() {
        // The clock went back a day: the second retry of a chain waits its 5 minutes, not 24 hours.
        val saved = listOf(SavedRetry("a", retryAt = now + 24 * 60 * minute, attemptsDone = 1, failure = null))
        assertEquals(now + 5 * minute, DownloadRestore.pendingRetries(saved, maxAttempts = 3, now = now)["a"])
    }

    @Test
    fun retriesTurnedOffOrUsedUpAreDropped() {
        val saved = listOf(
            SavedRetry("a", now + minute, attemptsDone = 0, failure = DownloadFailure.NETWORK),
            SavedRetry("spent", now + minute, attemptsDone = 3, failure = DownloadFailure.NETWORK),
            SavedRetry("missing", now + minute, attemptsDone = 0, failure = DownloadFailure.NOT_FOUND)
        )
        assertTrue(DownloadRestore.pendingRetries(saved, maxAttempts = 0, now = now).isEmpty())
        // Lowered from 5 to 3 with a chain three retries in, and a reason that never goes away by itself.
        assertEquals(setOf("a"), DownloadRestore.pendingRetries(saved, maxAttempts = 3, now = now).keys)
    }

    @Test
    fun pausedWebRowWithoutResumeRecordSaysItStartsAgain() {
        // archive.org's ZIP viewer keeps no record: after a restart the paused row still says so.
        assertTrue(DownloadRestore.restartsFromZero(DownloadStatus.PAUSED, isTorrent = false, hasResumeRecord = false))
        // A record means the next attempt carries it on with Range.
        assertFalse(DownloadRestore.restartsFromZero(DownloadStatus.PAUSED, isTorrent = false, hasResumeRecord = true))
        // Torrents and debrid rows (torrent index set) come back stopped; other statuses never say it.
        val torrent = DownloadRestore.statusAfterRestart(DownloadStatus.PAUSED, isTorrent = true)
        assertFalse(DownloadRestore.restartsFromZero(torrent, isTorrent = true, hasResumeRecord = false))
        assertFalse(DownloadRestore.restartsFromZero(DownloadStatus.STOPPED, isTorrent = false, hasResumeRecord = false))
        assertFalse(DownloadRestore.restartsFromZero(DownloadStatus.FAILED, isTorrent = false, hasResumeRecord = false))
    }

    @Test
    fun oneRetryPerFile() {
        val saved = listOf(
            SavedRetry("a", now + minute, 0, DownloadFailure.NETWORK),
            SavedRetry("a", now + 2 * minute, 0, DownloadFailure.NETWORK)
        )
        assertEquals(1, DownloadRestore.pendingRetries(saved, maxAttempts = 3, now = now).size)
    }
}
