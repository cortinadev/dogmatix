package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.DownloadRuns.End
import com.cortinadev.dogmatix.util.DownloadRuns.Start
import com.cortinadev.dogmatix.util.DownloadRuns.Stop
import com.cortinadev.dogmatix.util.DownloadRuns.StopResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRunsTest {

    private val runs = DownloadRuns<String>()
    private fun go(name: String): Long = (runs.start(name, "start") as Start.Go).run

    @Test
    fun oneRunPerFile() {
        val run = go("a")
        // A second tap, an automatic retry and "Resume all" at once: still one job.
        assertEquals(Start.Busy, runs.start("a", "again"))
        assertEquals(Start.Busy, runs.start("a", "again"))
        // Other files are not held up.
        assertNotEquals(run, go("b"))
        assertTrue(runs.isLive("a"))
    }

    @Test
    fun aRunEndsExactlyOnce() {
        val run = go("a")
        assertEquals(End.Done<String>(null, null), runs.end("a", run, null))
        assertEquals(End.Stale, runs.end("a", run, null))
        assertFalse(runs.isRunning("a"))
        // The next run is another one: the old job's late end cannot close it.
        val next = go("a")
        assertEquals(End.Stale, runs.end("a", run, null))
        assertTrue(runs.isCurrent("a", next))
        assertFalse(runs.isCurrent("a", run))
    }

    @Test
    fun stoppingCancelsTheJobOnceAndEndsWithTheStop() {
        val run = go("a")
        assertEquals(StopResult.Cancel(run), runs.stop("a", Stop.PAUSE, cleanUpWhenIdle = false))
        // A second pause (or "Pause all" right after) does not cancel again.
        assertEquals(StopResult.Merged, runs.stop("a", Stop.PAUSE, cleanUpWhenIdle = false))
        assertEquals(Stop.PAUSE, runs.stopOf("a", run))
        assertFalse(runs.isLive("a"))
        assertEquals(End.Done<String>(Stop.PAUSE, null), runs.end("a", run, Stop.PAUSE))
    }

    @Test
    fun aStartWhileWindingDownWaitsForTheEnd() {
        val run = go("a")
        runs.stop("a", Stop.PAUSE, cleanUpWhenIdle = false)
        // Paused and resumed at once: the resume waits for the pause to land, it is not lost.
        assertEquals(Start.AfterCurrent, runs.start("a", "resume"))
        assertEquals(End.Done(Stop.PAUSE, "resume"), runs.end("a", run, Stop.PAUSE))
        assertTrue(runs.start("a", "x") is Start.Go)
    }

    @Test
    fun theLastWordWins() {
        val run = go("a")
        runs.stop("a", Stop.PAUSE, cleanUpWhenIdle = false)
        runs.start("a", "resume")
        // Paused, resumed, then stopped: the waiting resume is dropped.
        assertEquals(StopResult.Merged, runs.stop("a", Stop.STOP, cleanUpWhenIdle = false))
        assertEquals(End.Done<String>(Stop.STOP, null), runs.end("a", run, Stop.STOP))
    }

    @Test
    fun aStrongerStopDuringTheWindDownIsWoundDownToo() {
        val run = go("a")
        runs.stop("a", Stop.PAUSE, cleanUpWhenIdle = false)
        // Deleted while the pause was being dealt with: the pause alone does not end the run.
        runs.stop("a", Stop.DELETE, cleanUpWhenIdle = true)
        assertEquals(End.Again(Stop.DELETE), runs.end("a", run, Stop.PAUSE))
        assertEquals(End.Done<String>(Stop.DELETE, null), runs.end("a", run, Stop.DELETE))
        // A weaker request never replaces a stronger one.
        val next = go("b")
        runs.stop("b", Stop.DELETE, cleanUpWhenIdle = true)
        runs.stop("b", Stop.PAUSE, cleanUpWhenIdle = false)
        assertEquals(Stop.DELETE, runs.stopOf("b", next))
    }

    @Test
    fun aStopThatCameTooLateIsWoundDownBeforeTheEnd() {
        val run = go("a")
        runs.stop("a", Stop.STOP, cleanUpWhenIdle = false)
        // The job ran to its end anyway (the cancellation was swallowed, or came after the last byte).
        assertEquals(End.Again(Stop.STOP), runs.end("a", run, null))
        assertEquals(End.Done<String>(Stop.STOP, null), runs.end("a", run, Stop.STOP))
    }

    @Test
    fun withNothingRunningPauseDoesNothingAndStopOrDeleteCleanUp() {
        // A pause that arrives just after the job ended leaves nothing behind.
        assertEquals(StopResult.Idle, runs.stop("a", Stop.PAUSE, cleanUpWhenIdle = false))
        assertFalse(runs.isRunning("a"))
        // A paused row stopped, or any row deleted: a run that only cleans up, already stopping.
        val cleanUp = runs.stop("a", Stop.DELETE, cleanUpWhenIdle = true) as StopResult.CleanUp
        assertEquals(Stop.DELETE, runs.stopOf("a", cleanUp.run))
        // Started again from the library while the clean-up runs: it goes once the partial is gone.
        assertEquals(Start.AfterCurrent, runs.start("a", "fresh"))
        assertEquals(End.Again(Stop.DELETE), runs.end("a", cleanUp.run, null))
        assertEquals(End.Done(Stop.DELETE, "fresh"), runs.end("a", cleanUp.run, Stop.DELETE))
    }

    @Test
    fun staleRunsSeeNothing() {
        val old = go("a")
        runs.end("a", old, null)
        val current = go("a")
        runs.stop("a", Stop.STOP, cleanUpWhenIdle = false)
        assertNull(runs.stopOf("a", old))
        assertEquals(Stop.STOP, runs.stopOf("a", current))
        assertTrue(current > old)
    }
}
