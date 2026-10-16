package com.cortinadev.dogmatix.data.state

import com.cortinadev.dogmatix.util.ScanEta
import com.cortinadev.dogmatix.util.ScanProgressMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RescanStateHolder @Inject constructor() {
    private val _isRescanning = MutableStateFlow(false)
    val isRescanning: StateFlow<Boolean> = _isRescanning.asStateFlow()

    private val _lastRescanTime = MutableStateFlow(0L)
    val lastRescanTime: StateFlow<Long> = _lastRescanTime.asStateFlow()

    private val _progressMessage = MutableStateFlow("")
    val progressMessage: StateFlow<String> = _progressMessage.asStateFlow()

    private val _torrentFetchProgress = MutableStateFlow("")
    val torrentFetchProgress: StateFlow<String> = _torrentFetchProgress.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun setRescanning(value: Boolean) { 
        _isRescanning.value = value 
        if (!value) {
            _lastRescanTime.value = System.currentTimeMillis()
        }
    }
    fun setProgressMessage(message: String) { _progressMessage.value = message }
    fun clearProgressMessage() { _progressMessage.value = "" }
    fun setTorrentFetchProgress(message: String) { _torrentFetchProgress.value = message }
    fun clearTorrentFetchProgress() { _torrentFetchProgress.value = "" }
    fun setErrorMessage(message: String?) { _errorMessage.value = message }

    // escaneo: progress of the running scan, per source (see ScanProgressMath), and the time left.
    private val _progress = MutableStateFlow<ScanProgress?>(null)
    /** Null while no scan runs. */
    val progress: StateFlow<ScanProgress?> = _progress.asStateFlow()

    private val lock = Any()
    private var math = ScanProgressMath()
    private val eta = ScanEta()

    /** A new scan: [weights] per source key (see [sourceKey]); sources that weigh nothing are left out. */
    fun beginProgress(weights: Map<String, Double>, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        math = ScanProgressMath().apply { weights.forEach { (key, w) -> add(key, w) } }
        eta.reset(now)
        publish(now)
    }

    /** Source [key] reached phase [fraction] (0..1). Safe from any thread. */
    fun sourceProgress(key: String, fraction: Double) = synchronized(lock) {
        if (_progress.value == null) return@synchronized
        math.set(key, fraction)
        publish(System.currentTimeMillis())
    }

    fun sourceDone(key: String) = sourceProgress(key, 1.0)

    /** Called every couple of seconds while scanning, so the estimate keeps counting down between sources. */
    fun tickProgress(now: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (_progress.value != null) publish(now)
    }

    fun endProgress() = synchronized(lock) { _progress.value = null }

    private fun publish(now: Long) {
        val fraction = math.fraction
        // Rounded so the indicator does not recompose for changes nobody can see.
        val left = eta.update(now, fraction)?.let { (it / 5_000) * 5_000 }
        _progress.value = ScanProgress((fraction * 100).toInt() / 100f, left)
    }

    companion object {
        fun sourceKey(consoleId: String, url: String) = "$consoleId|$url"
    }
}

/** How far a running scan is: [fraction] 0..1, and the milliseconds left once the estimate has settled. */
data class ScanProgress(val fraction: Float, val etaMs: Long?)
