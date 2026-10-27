package com.cortinadev.dogmatix.data.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cortinadev.dogmatix.data.local.DogmatixDatabase
import com.cortinadev.dogmatix.data.local.entity.DownloadHistoryEntity
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.testing.TestData
import com.cortinadev.dogmatix.testing.TestData.eventually
import com.cortinadev.dogmatix.testing.fakeSettings
import com.cortinadev.dogmatix.util.DownloadFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * What a restart brings back of the Downloads list and its queue, rebuilt from `download_history`
 * the way DownloadService does at startup (restore, then the in-flight rows put back in line in
 * one batch with their places): the order, the holds and the paused rows survive, nothing is
 * listed twice, and one simultaneous download really means one. A real process kill is in the
 * tester's manual guide (force-stop with three downloads, one held and one paused).
 */
@RunWith(AndroidJUnit4::class)
class QueueRecoveryTest {

    private lateinit var db: DogmatixDatabase
    private val history get() = db.downloadHistoryDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    @Before
    fun setUp() = runBlocking {
        db = TestData.inMemoryDatabase()
        ROWS.forEach { history.upsert(it) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
    }

    /** A queue as the app builds it at startup, with one download at a time. */
    private fun newQueue(): DownloadQueueService = runBlocking {
        val slotsApplied = CompletableDeferred<Unit>()
        val queue = DownloadQueueService(
            fakeSettings(
                "concurrentDownloads" to flow { emit(1); slotsApplied.complete(Unit); awaitCancellation() },
                "perHostDownloads" to flowOf(0)
            ),
            DownloadProgressTracker(history)
        )
        withTimeout(5_000) { slotsApplied.await() }
        queue
    }

    /** DownloadService.restoreHistory + resumeInterrupted: places first, then the in-flight rows in one batch. */
    private fun restart(queue: DownloadQueueService): List<String> = runBlocking {
        val rows = history.getAll()
        queue.restore(rows)
        val inFlight = rows.filter { it.wasInFlight }
        val tickets = queue.batch { inFlight.map { queue.enqueue(it.toEntity(), keepPlace = true) } }
        tickets.forEach { ticket -> startJob(queue, ticket) }
        inFlight.map { it.fileName }
    }

    /** The job of a download: takes its slot when its turn comes and keeps it until [finish]. */
    private fun startJob(queue: DownloadQueueService, ticket: QueueTicket) {
        val gate = CompletableDeferred<Unit>().also { gates[ticket.name] = it }
        scope.launch { queue.withSlot(ticket) { started += ticket.name; gate.await() } }
    }

    private fun finish(name: String) = gates.getValue(name).complete(Unit)

    private fun placesInDatabase(): Map<String, Pair<Long, Boolean>> = runBlocking {
        history.getAll().associate { it.fileName to (it.queueOrder to it.queueHeld) }
    }

    @Test
    fun aRestartKeepsTheOrderTheHoldsAndThePausedRows() {
        val before = placesInDatabase()
        val queue = newQueue()
        val relaunched = restart(queue)
        assertEquals(setOf("first.zip", "second.zip", "third.zip"), relaunched.toSet())

        // By saved order, not by list order: second (1) is held, so first (3) starts and third (7) waits.
        eventually("first starts") { started.toList() == listOf("first.zip") }
        Thread.sleep(300)
        assertEquals(listOf("first.zip"), started.toList())
        assertEquals(listOf("second.zip", "third.zip"), queue.state.value.waiting)
        assertEquals(setOf("second.zip"), queue.state.value.held)
        assertEquals(mapOf("third.zip" to 1), queue.state.value.turns)
        // Coming back changed no place in the database.
        Thread.sleep(300)
        assertEquals(before, placesInDatabase())

        // The held one is skipped when the slot frees up.
        finish("first.zip")
        eventually("third starts after first") { started.toList() == listOf("first.zip", "third.zip") }

        // The paused one, resumed by the user, goes back to its place (4): ahead of nothing running, behind the held one.
        val resumed = queue.enqueue(ROWS.single { it.fileName == "paused.zip" }.toEntity(), keepPlace = true)
        startJob(queue, resumed)
        assertEquals(4L, resumed.order)
        assertEquals(listOf("second.zip", "paused.zip"), queue.state.value.waiting)
        // Resumed twice (a double tap): still listed once.
        val again = queue.enqueue(ROWS.single { it.fileName == "paused.zip" }.toEntity(), keepPlace = true)
        startJob(queue, again)
        assertEquals(listOf("second.zip", "paused.zip"), queue.state.value.waiting)
    }

    @Test
    fun aSecondRestartComesBackTheSameWay() {
        // First run: as above, then the process dies with first finished and third running.
        val firstRun = newQueue()
        restart(firstRun)
        eventually("first starts") { started.toList() == listOf("first.zip") }
        finish("first.zip")
        eventually("third starts") { started.contains("third.zip") }
        runBlocking {
            history.updateStatus("first.zip", DownloadStatus.COMPLETED.name, 5_000)
            // The paused one was resumed: running again when the process died.
            history.markRestarted("paused.zip", DownloadStatus.DOWNLOADING.name, 6_000, retryCount = 0)
        }

        // Second run: second (1, held), paused (4), third (7).
        started.clear()
        val secondRun = newQueue()
        val relaunched = restart(secondRun)
        assertEquals(setOf("second.zip", "third.zip", "paused.zip"), relaunched.toSet())
        eventually("paused starts first") { started.toList() == listOf("paused.zip") }
        assertEquals(listOf("second.zip", "third.zip"), secondRun.state.value.waiting)
        assertEquals(setOf("second.zip"), secondRun.state.value.held)

        // Letting go of the hold is saved for the next restart too.
        secondRun.setHeld("second.zip", false)
        assertTrue(secondRun.state.value.held.isEmpty())
        eventually("hold saved") { placesInDatabase().getValue("second.zip") == (1L to false) }
        finish("paused.zip")
        eventually("second goes next") { started.toList() == listOf("paused.zip", "second.zip") }
    }

    // ---- The list itself (DownloadProgressTracker) ----------------------------------------------

    @Test
    fun theRestoredListShowsWhatCanBeResumed() = runBlocking {
        val tracker = DownloadProgressTracker(history)
        // A download started before the history was read wins over its old row.
        tracker.addDownload(ROWS.single { it.fileName == "failed.zip" }.toItem().copy(status = DownloadStatus.DOWNLOADING))
        val rows = history.getAll()
        tracker.restore(rows.map { it.toItem() })
        tracker.restore(rows.map { it.toItem() }) // twice: nothing doubles
        val byName = tracker.getDownloads().associate { it.fileName to it.status }
        assertEquals(ROWS.size, tracker.getDownloads().size)
        assertEquals(DownloadStatus.STOPPED, byName["first.zip"])
        assertEquals(DownloadStatus.STOPPED, byName["second.zip"])
        assertEquals(DownloadStatus.STOPPED, byName["third.zip"])
        assertEquals(DownloadStatus.PAUSED, byName["paused.zip"])
        assertEquals(DownloadStatus.COMPLETED, byName["done.zip"])
        assertEquals(DownloadStatus.DOWNLOADING, byName["failed.zip"])
        listOf("first.zip", "paused.zip", "done.zip").forEach { assertTrue(it, tracker.canRetryDownload(it)) }
        assertFalse(tracker.hasActiveDownloads(ignoring = setOf("failed.zip")))

        // Failure reasons from the history: what this run already knows wins.
        tracker.recordFailure("third.zip", DownloadFailure.NOT_FOUND)
        tracker.restoreFailures(mapOf("third.zip" to DownloadFailure.NETWORK, "first.zip" to DownloadFailure.SERVER))
        assertEquals(DownloadFailure.NOT_FOUND, tracker.failures.value["third.zip"])
        assertEquals(DownloadFailure.SERVER, tracker.failures.value["first.zip"])

        // Status changes reach the database.
        tracker.updateDownloadStatus("third.zip", DownloadStatus.FAILED)
        eventually("status saved") { runBlocking { history.getByFileName("third.zip")!!.status } == DownloadStatus.FAILED.name }
        eventually("reason saved") { runBlocking { history.getByFileName("third.zip")!!.failureReason } == DownloadFailure.NOT_FOUND.name }
    }

    companion object {
        /** The Downloads list when the process died. startedAt gives a list order unlike the queue's. */
        private val ROWS: List<DownloadHistoryEntity> = listOf(
            TestData.history("third.zip", DownloadStatus.DOWNLOADING, order = 7, startedAt = 50),
            TestData.history("first.zip", DownloadStatus.DOWNLOADING, order = 3, startedAt = 100),
            TestData.history("second.zip", DownloadStatus.DOWNLOADING, order = 1, held = true, startedAt = 200),
            TestData.history("paused.zip", DownloadStatus.PAUSED, order = 4, startedAt = 300),
            TestData.history("done.zip", DownloadStatus.COMPLETED, order = 2, startedAt = 400),
            TestData.history("failed.zip", DownloadStatus.FAILED, order = 5, startedAt = 500)
        )
    }
}
