package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.data.model.DownloadItemModel
import com.cortinadev.dogmatix.data.model.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadNoticesTest {

    private fun row(name: String, status: DownloadStatus, size: Long = 100L, done: Long = 0L, speed: Float = 0f) =
        DownloadItemModel(name = name, fileName = name, downloadSpeed = speed, progress = 0f, fileSize = size, downloadedBytes = done, status = status)

    @Test
    fun progressAddsUpTheBusyRows() {
        val rows = listOf(
            row("running", DownloadStatus.DOWNLOADING, size = 400, done = 100, speed = 1.5f),
            row("waiting", DownloadStatus.DOWNLOADING, size = 100),
            row("held", DownloadStatus.DOWNLOADING, size = 1000),
            row("unzipping", DownloadStatus.UNZIPPING, size = 500, done = 0),
            row("done", DownloadStatus.COMPLETED, size = 999, done = 999),
            row("paused", DownloadStatus.PAUSED, size = 999, done = 10)
        )
        val progress = QueueProgress.of(rows, waiting = setOf("waiting", "held"), held = setOf("held"))
        assertEquals(2, progress.running)
        assertEquals(1, progress.waiting)
        // Extraction comes after the transfer: its bytes are all in.
        assertEquals(600L, progress.doneBytes)
        assertEquals(1000L, progress.totalBytes)
        assertEquals(60, progress.percent)
        assertEquals(1.5f, progress.speed, 0.0001f)
        assertTrue(progress.canPause)
        assertTrue(progress.canResume)
        assertTrue(progress.canCancel)
    }

    @Test
    fun progressWithoutSizesHasNoPercent() {
        val progress = QueueProgress.of(listOf(row("a", DownloadStatus.DOWNLOADING, size = 0)), emptySet(), emptySet())
        assertNull(progress.percent)
        assertFalse(progress.canResume)
    }

    @Test
    fun aBatchReportsOnceTheQueueRunsDry() {
        val batch = DownloadBatch()
        assertNull(batch.update(listOf(row("a", DownloadStatus.DOWNLOADING), row("b", DownloadStatus.DOWNLOADING)), emptySet(), emptySet()))
        assertNull(batch.update(listOf(row("a", DownloadStatus.COMPLETED), row("b", DownloadStatus.DOWNLOADING)), emptySet(), emptySet()))
        val summary = batch.update(listOf(row("a", DownloadStatus.COMPLETED), row("b", DownloadStatus.FAILED)), emptySet(), emptySet())
        assertEquals(DownloadBatch.Summary(completed = 1, failed = 1), summary)
        // Reported once: the same idle list says nothing more.
        assertNull(batch.update(listOf(row("a", DownloadStatus.COMPLETED), row("b", DownloadStatus.FAILED)), emptySet(), emptySet()))
    }

    @Test
    fun rowsFinishedBeforeTheBatchAreNotCounted() {
        val batch = DownloadBatch()
        val old = row("old", DownloadStatus.COMPLETED)
        batch.update(listOf(old, row("new", DownloadStatus.DOWNLOADING)), emptySet(), emptySet())
        assertEquals(DownloadBatch.Summary(1, 0), batch.update(listOf(old, row("new", DownloadStatus.COMPLETED)), emptySet(), emptySet()))
    }

    @Test
    fun aPendingAutomaticRetryKeepsTheBatchOpen() {
        val batch = DownloadBatch()
        batch.update(listOf(row("a", DownloadStatus.DOWNLOADING)), emptySet(), emptySet())
        assertNull(batch.update(listOf(row("a", DownloadStatus.FAILED)), emptySet(), retrying = setOf("a")))
        batch.update(listOf(row("a", DownloadStatus.DOWNLOADING)), emptySet(), emptySet())
        assertEquals(DownloadBatch.Summary(1, 0), batch.update(listOf(row("a", DownloadStatus.COMPLETED)), emptySet(), emptySet()))
    }

    @Test
    fun heldRowsDoNotKeepTheBatchOpen() {
        val batch = DownloadBatch()
        batch.update(listOf(row("a", DownloadStatus.DOWNLOADING), row("h", DownloadStatus.DOWNLOADING)), setOf("h"), emptySet())
        val summary = batch.update(listOf(row("a", DownloadStatus.COMPLETED), row("h", DownloadStatus.DOWNLOADING)), setOf("h"), emptySet())
        assertEquals(DownloadBatch.Summary(1, 0), summary)
    }

    @Test
    fun aBatchStoppedOrDeletedByTheUserSaysNothing() {
        val batch = DownloadBatch()
        batch.update(listOf(row("a", DownloadStatus.DOWNLOADING), row("b", DownloadStatus.DOWNLOADING)), emptySet(), emptySet())
        // a stopped, b deleted from the list.
        assertNull(batch.update(listOf(row("a", DownloadStatus.STOPPED)), emptySet(), emptySet()))
        // Paused rows are not busy either: a fully paused batch ends without a notice.
        batch.update(listOf(row("c", DownloadStatus.DOWNLOADING)), emptySet(), emptySet())
        assertNull(batch.update(listOf(row("c", DownloadStatus.PAUSED)), emptySet(), emptySet()))
    }

    @Test
    fun busyMeansNotFinishedNotPausedNotHeld() {
        assertTrue(DownloadBatch.anyBusy(listOf(row("a", DownloadStatus.COPYING)), emptySet(), emptySet()))
        assertFalse(DownloadBatch.anyBusy(listOf(row("a", DownloadStatus.DOWNLOADING)), setOf("a"), emptySet()))
        assertFalse(DownloadBatch.anyBusy(listOf(row("a", DownloadStatus.PAUSED), row("b", DownloadStatus.FAILED)), emptySet(), emptySet()))
        assertTrue(DownloadBatch.anyBusy(listOf(row("b", DownloadStatus.FAILED)), emptySet(), setOf("b")))
    }
}
