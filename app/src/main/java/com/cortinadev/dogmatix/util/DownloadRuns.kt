package com.cortinadev.dogmatix.util

/**
 * Who may touch a download right now. Each file has at most one *run*: its download job, or the
 * clean-up a stop, pause or delete leaves behind (the torrent session, the partial file, the
 * history row). A run ends exactly once, whatever ended it, and only then may the next one
 * start: two jobs never write the same file, and a late clean-up never lands on a newer download.
 *
 * Plain state without coroutines or locking: the download service calls it under one lock.
 * [T] is what a start that has to wait for the current run to wind down carries.
 */
class DownloadRuns<T> {

    /** Why a run is wound down. A stronger request replaces a weaker one: delete > stop > pause. */
    enum class Stop { PAUSE, STOP, DELETE }

    sealed interface Start {
        /** Nothing was running: [run] is the new run. */
        data class Go(val run: Long) : Start
        /** A run is going on undisturbed: a second one would fetch the same file twice. */
        data object Busy : Start
        /** The current run is winding down: the start is kept and handed back by [end]. */
        data object AfterCurrent : Start
    }

    sealed interface StopResult {
        /** The live run [run] is stopping now: cancel its job. */
        data class Cancel(val run: Long) : StopResult
        /** Nothing was running: [run] is a run opened only to do the clean-up (already stopping). */
        data class CleanUp(val run: Long) : StopResult
        /** Already winding down; the request was merged in (the stronger one wins). */
        data object Merged : StopResult
        /** Nothing running and nothing to clean up. */
        data object Idle : StopResult
    }

    sealed interface End<out P> {
        /** Not the current run (it ended already, or never was one): nothing to do. */
        data object Stale : End<Nothing>
        /** A stop the run has not wound down for yet (it came late, or got stronger): do it, then end again. */
        data class Again(val stop: Stop) : End<Nothing>
        /** Over. [stop]: why (null = it ran to its end). [next]: the start that waited for it. */
        data class Done<P>(val stop: Stop?, val next: P?) : End<P>
    }

    private class Run<T>(val id: Long, var stop: Stop?, var next: T? = null)

    private val runs = HashMap<String, Run<T>>()
    private var lastId = 0L

    /** Opens a run for [name]; while the current one winds down, [next] waits for it instead. */
    fun start(name: String, next: T): Start {
        val current = runs[name] ?: return Start.Go(open(name, null))
        if (current.stop == null) return Start.Busy
        current.next = next
        return Start.AfterCurrent
    }

    /**
     * Asks the run of [name] to wind down for [stop]. With nothing running, [cleanUpWhenIdle]
     * opens a run that only does the clean-up (a paused torrent session let go, a deleted row).
     */
    fun stop(name: String, stop: Stop, cleanUpWhenIdle: Boolean): StopResult {
        val current = runs[name]
            ?: return if (cleanUpWhenIdle) StopResult.CleanUp(open(name, stop)) else StopResult.Idle
        // The last word: a start that was waiting for this run is dropped.
        current.next = null
        val was = current.stop
        if (was == null || stop > was) current.stop = stop
        return if (was == null) StopResult.Cancel(current.id) else StopResult.Merged
    }

    /** What [run] is being wound down for; null while it runs undisturbed, or once it is over. */
    fun stopOf(name: String, run: Long): Stop? = runs[name]?.takeIf { it.id == run }?.stop

    fun isCurrent(name: String, run: Long): Boolean = runs[name]?.id == run

    /** A run of [name] is going on and nothing is winding it down. */
    fun isLive(name: String): Boolean = runs[name]?.let { it.stop == null } == true

    fun isRunning(name: String): Boolean = name in runs

    /**
     * [run] is over. [handled]: the stop its wind-down took care of (null = none). A stop it has
     * not wound down for comes back as [End.Again]; every call after the first [End.Done] is
     * [End.Stale].
     */
    fun end(name: String, run: Long, handled: Stop?): End<T> {
        val current = runs[name]?.takeIf { it.id == run } ?: return End.Stale
        val stop = current.stop
        if (stop != null && (handled == null || stop > handled)) return End.Again(stop)
        runs.remove(name)
        return End.Done(stop, current.next)
    }

    private fun open(name: String, stop: Stop?): Long {
        val id = ++lastId
        runs[name] = Run(id, stop)
        return id
    }
}
