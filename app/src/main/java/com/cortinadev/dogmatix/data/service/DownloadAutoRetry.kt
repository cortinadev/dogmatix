package com.cortinadev.dogmatix.data.service

import android.util.Log
import com.cortinadev.dogmatix.data.model.DownloadStatus
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.AutoRetryPolicy
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
 * (first try or a manual retry) begins a new chain. Stop, pause, delete or success end it, and
 * any manual action cancels the pending retry. Pending retries live in memory only.
 */
@Singleton
class DownloadAutoRetry @Inject constructor(
    private val downloadService: DownloadService,
    private val progressTracker: DownloadProgressTracker,
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Automatic retries already spent per file in the current chain. */
    private val attempts = ConcurrentHashMap<String, Int>()
    private val pending = ConcurrentHashMap<String, Job>()
    /** Files whose current job this service started: their failure continues the chain. */
    private val autoStarted = ConcurrentHashMap.newKeySet<String>()

    private val _nextRetryAt = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Epoch millis of the next automatic retry per file, for the Downloads list. */
    val nextRetryAt: StateFlow<Map<String, Long>> = _nextRetryAt.asStateFlow()

    init {
        scope.launch { downloadService.failed.collect { schedule(it) } }
        scope.launch {
            downloadService.downloads.collect { list ->
                val statuses = list.associate { it.fileName to it.status }
                // Retried by hand, stopped or deleted meanwhile: the pending retry is moot.
                pending.keys.filter { statuses[it] != DownloadStatus.FAILED }.forEach(::cancel)
                for ((fileName, status) in statuses) {
                    if (status == DownloadStatus.COMPLETED || status == DownloadStatus.STOPPED || status == DownloadStatus.PAUSED) endChain(fileName)
                }
                (attempts.keys + autoStarted).filter { it !in statuses }.forEach(::endChain)
            }
        }
    }

    private suspend fun schedule(fileName: String) {
        val max = settingsRepository.autoRetryAttempts.first()
        val done = if (autoStarted.remove(fileName)) attempts[fileName] ?: 0 else 0
        val failure = progressTracker.failures.value[fileName]
        if (!AutoRetryPolicy.shouldRetry(failure, done, max)) return
        val wait = AutoRetryPolicy.delayMs(done)
        cancel(fileName)
        _nextRetryAt.update { it + (fileName to System.currentTimeMillis() + wait) }
        pending[fileName] = scope.launch {
            delay(wait)
            pending.remove(fileName)
            _nextRetryAt.update { it - fileName }
            // Turned off meanwhile, or the row moved on (retried by hand, deleted).
            if (settingsRepository.autoRetryAttempts.first() <= 0) return@launch
            if (progressTracker.statusOf(fileName) != DownloadStatus.FAILED) return@launch
            Log.i(TAG, "Automatic retry ${done + 1}/$max of $fileName ($failure)")
            attempts[fileName] = done + 1
            autoStarted.add(fileName)
            downloadService.retryDownload(fileName)
        }
    }

    private fun endChain(fileName: String) {
        attempts.remove(fileName)
        autoStarted.remove(fileName)
    }

    private fun cancel(fileName: String) {
        pending.remove(fileName)?.cancel()
        _nextRetryAt.update { it - fileName }
    }
}
