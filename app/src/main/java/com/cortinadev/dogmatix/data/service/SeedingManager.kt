package com.cortinadev.dogmatix.data.service

import android.util.Log
import com.cortinadev.dogmatix.data.model.SeedingMode
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps finished torrents uploading ("Seed after download" in Settings) and releases them —
 * deleting their cached data — once the mode's goal (time or ratio) is met. Seeds only live
 * while the download foreground service does: its onDestroy calls [stopAll], and UNLIMITED
 * seeds end there or when the user turns the setting off.
 */
@Singleton
class SeedingManager @Inject constructor(
    private val registry: TorrentHandleRegistry,
    settingsRepository: SettingsRepository
) {
    private data class Seed(val mode: SeedingMode, val startedAt: Long)

    private val seeds = ConcurrentHashMap<String, Seed>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    /** Number of torrents currently seeding; [DownloadService] watches it to stop the service. */
    private val _active = MutableStateFlow(0)
    val active: StateFlow<Int> = _active.asStateFlow()

    val isSeeding: Boolean get() = seeds.isNotEmpty()

    init {
        // Switching the setting off also stops seeds already running (the only way out of an
        // UNLIMITED seed besides closing the app).
        scope.launch {
            settingsRepository.seedingMode.collect { if (it == SeedingMode.OFF) stopAll() }
        }
    }

    fun startSeeding(magnet: String, mode: SeedingMode) {
        if (mode == SeedingMode.OFF) return
        seeds[magnet] = Seed(mode, System.currentTimeMillis())
        _active.value = seeds.size
        synchronized(this) {
            if (monitorJob == null) monitorJob = scope.launch { monitor() }
        }
        Log.i(TAG, "Seeding started (${mode.name}), ${seeds.size} torrent(s) seeding")
    }

    /** Drops a seed without touching the handle: a new download of the same torrent owns it now. */
    fun forget(magnet: String) {
        if (seeds.remove(magnet) != null) {
            _active.value = seeds.size
            Log.i(TAG, "Seed handed back to the download flow")
        }
    }

    /** Releases every seed (cached data included). Called when the download service dies. */
    fun stopAll() = seeds.keys.toList().forEach(::release)

    private fun release(magnet: String) {
        seeds.remove(magnet) ?: return
        registry.releaseHandle(magnet)
        _active.value = seeds.size
        Log.i(TAG, "Seeding finished, ${seeds.size} torrent(s) still seeding")
    }

    private suspend fun monitor() {
        while (true) {
            delay(POLL_INTERVAL_MS)
            seeds.entries.toList().forEach { (magnet, seed) ->
                val handle = registry.getCachedHandle(magnet)
                if (handle == null) {
                    // Released elsewhere (session stopped, or the download flow re-took it and
                    // then released): just drop the stale entry.
                    if (seeds.remove(magnet) != null) _active.value = seeds.size
                    return@forEach
                }
                val ratio = try {
                    val status = handle.status()
                    status.allTimeUpload().toFloat() / status.totalDone().coerceAtLeast(1L)
                } catch (e: Exception) {
                    0f
                }
                if (seed.mode.goalReached(System.currentTimeMillis() - seed.startedAt, ratio)) {
                    release(magnet)
                }
            }
            synchronized(this) {
                if (seeds.isEmpty()) {
                    monitorJob = null
                    return
                }
            }
        }
    }

    companion object {
        private const val TAG = "SeedingManager"
        private const val POLL_INTERVAL_MS = 15_000L
    }
}
