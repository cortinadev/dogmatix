package com.cortinadev.dogmatix.data.service

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.AutoRetryPolicy
import com.cortinadev.dogmatix.util.DownloadRestore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DownloadAutoRetry"

/**
 * "Automatic retries" in Settings: a download whose job ends FAILED for a transient reason
 * (network, server, torrent without metadata…) is retried after 1, 5, 15… minutes, up to the
 * configured count. The count runs along a chain of automatic retries: a job the user started
 * (first try or a manual retry) begins a new chain; stop, pause, delete or success end it (the
 * download service keeps the count, see [DownloadService.retriesSpent]), and any manual action
 * cancels the pending retry.
 *
 * Pending retries are saved with their row and come back after a restart, once the app has
 * resumed the queue ([DownloadService.awaitResume]): overdue ones at once, the rest with what was
 * left of their wait. One that comes due while the app is in the background, where Android
 * refuses the foreground service a transfer needs, waits until the app is open again (its time
 * in [nextRetryAt] is then [WAITING_FOR_APP]).
 */
@Singleton
class DownloadAutoRetry @Inject constructor(
    @ApplicationContext context: Context,
    private val downloadService: DownloadService,
    private val progressTracker: DownloadProgressTracker,
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val pending = ConcurrentHashMap<String, Job>()
    /**
     * Came due and being started, or waiting for the app because the start was refused: the app
     * coming to the foreground runs them. Under [dueLock], like the watcher's look at it.
     */
    private val due = HashSet<String>()
    private val dueLock = Any()

    private val _nextRetryAt = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Epoch millis of the next automatic retry per file, for the Downloads list. */
    val nextRetryAt: StateFlow<Map<String, Long>> = _nextRetryAt.asStateFlow()

    init {
        scope.launch { downloadService.failed.collect { schedule(it) } }
        scope.launch {
            downloadService.downloads.collect { list ->
                val statuses = list.associate { it.fileName to it.status }
                // Retried by hand, stopped or deleted meanwhile: the pending retry is moot.
                (pending.keys + dueNames() + _nextRetryAt.value.keys).filter { statuses[it] != DownloadStatus.FAILED }.forEach { cancel(it) }
            }
        }
        scope.launch {
            // Turned off: nothing stays pending.
            settingsRepository.autoRetryAttempts.collect { max -> if (max <= 0) (pending.keys + dueNames()).forEach { cancel(it) } }
        }
        scope.launch { restore(downloadService.awaitResume()) }
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(ForegroundWatcher())
    }

    private suspend fun schedule(fileName: String) {
        if (progressTracker.statusOf(fileName) != DownloadStatus.FAILED) return
        val max = settingsRepository.autoRetryAttempts.first()
        val done = downloadService.retriesSpent(fileName)
        val failure = progressTracker.failures.value[fileName]
        if (!AutoRetryPolicy.shouldRetry(failure, done, max)) return
        plan(fileName, System.currentTimeMillis() + AutoRetryPolicy.delayMs(done))
    }

    /** The retries saved by the previous run, programmed once (see [DownloadRestore.pendingRetries]). */
    private suspend fun restore(saved: List<DownloadRestore.SavedRetry>) {
        if (saved.isEmpty()) return
        val plans = DownloadRestore.pendingRetries(saved, settingsRepository.autoRetryAttempts.first(), System.currentTimeMillis())
        for (retry in saved) {
            val at = plans[retry.fileName]
            if (at != null && progressTracker.statusOf(retry.fileName) == DownloadStatus.FAILED) plan(retry.fileName, at)
            // Turned off or lowered meanwhile, or the row moved on: forgotten.
            else downloadService.saveRetryAt(retry.fileName, null)
        }
    }

    /** One pending retry per file: a new plan replaces the old one. Saved, so a restart keeps it. */
    private fun plan(fileName: String, at: Long) {
        cancel(fileName, forget = false)
        _nextRetryAt.update { it + (fileName to at) }
        downloadService.saveRetryAt(fileName, at)
        pending[fileName] = scope.launch {
            delay((at - System.currentTimeMillis()).coerceAtLeast(0L))
            pending.remove(fileName)
            fire(fileName)
        }
    }

    private suspend fun fire(fileName: String) {
        // Turned off meanwhile, or the row moved on (retried by hand, deleted).
        if (settingsRepository.autoRetryAttempts.first() <= 0 || progressTracker.statusOf(fileName) != DownloadStatus.FAILED) {
            cancel(fileName)
            return
        }
        val attempt = downloadService.retriesSpent(fileName) + 1
        Log.i(TAG, "Automatic retry $attempt of $fileName (${progressTracker.failures.value[fileName]})")
        // Marked due *before* trying: the app coming to the foreground while the start is refused
        // then finds it (marked after, the watcher could look in between and miss it until the
        // next time the app is opened).
        synchronized(dueLock) { due += fileName }
        val started = downloadService.retryAutomatically(fileName, attempt)
        synchronized(dueLock) {
            if (started) {
                due -= fileName
                _nextRetryAt.update { it - fileName }
            } else if (fileName in due) {
                // No foreground service from the background: it waits (saved) until the app opens.
                // Still due: the foreground watcher has not started it meanwhile.
                Log.i(TAG, "$fileName waits for the app to be open")
                _nextRetryAt.update { it + (fileName to WAITING_FOR_APP) }
            }
        }
    }

    private fun dueNames(): Set<String> = synchronized(dueLock) { due.toSet() }

    /** Drops the pending retry of [fileName]; [forget]: also from the saved row. */
    private fun cancel(fileName: String, forget: Boolean = true) {
        pending.remove(fileName)?.cancel()
        val had = synchronized(dueLock) { due.remove(fileName) } or (fileName in _nextRetryAt.value)
        _nextRetryAt.update { it - fileName }
        if (had && forget) downloadService.saveRetryAt(fileName, null)
    }

    /** The app coming to the foreground (an activity resumed) runs what came due while it was not. */
    private inner class ForegroundWatcher : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            val waiting = dueNames()
            if (waiting.isNotEmpty()) scope.launch { waiting.forEach { fire(it) } }
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    companion object {
        /** The [nextRetryAt] of a retry that came due with the app in the background: it runs when the app is open. */
        const val WAITING_FOR_APP = Long.MAX_VALUE
    }
}
