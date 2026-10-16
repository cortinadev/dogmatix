package com.cortinadev.dogmatix.data.service

import android.util.Log
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.FileSizeUtils
import com.cortinadev.dogmatix.util.TokenBucket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies the Settings speed limit (KB/s) to all downloads together: HTTP downloads (debrid and
 * RomM included) share one [TokenBucket], and the torrent session gets the same limit as its own.
 * A change in Settings reaches downloads already running.
 */
@Singleton
class DownloadSpeedController @Inject constructor(
    settingsRepository: SettingsRepository,
    torrentHandleRegistry: TorrentHandleRegistry
) {
    private val bucket = TokenBucket()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            settingsRepository.limitSpeed
                .map(TokenBucket::bytesPerSecond)
                .distinctUntilChanged()
                .collect { limit ->
                    bucket.setRate(limit)
                    runCatching { torrentHandleRegistry.setDownloadRateLimit(limit) }
                        .onFailure { Log.w("DownloadSpeedController", "Torrent rate limit not applied: ${it.message}") }
                }
        }
    }

    /** Waits as long as reading [bytes] more takes under the shared limit; returns at once without one. */
    suspend fun throttle(bytes: Int) {
        val wait = bucket.take(bytes, System.nanoTime())
        if (wait > 0) delay(wait)
    }

    fun calculateSpeed(bytesDownloaded: Long, timeElapsed: Float): Float {
        return if (timeElapsed > 0) {
            FileSizeUtils.bytesToMB(bytesDownloaded) / timeElapsed
        } else {
            0f
        }
    }
}
