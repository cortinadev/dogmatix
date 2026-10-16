package com.cortinadev.dogmatix.data.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DownloadForegroundService : Service() {

    @Inject lateinit var downloadService: DownloadService
    @Inject lateinit var torrentHandleRegistry: TorrentHandleRegistry
    @Inject lateinit var torrentProgressBridge: TorrentProgressBridge
    @Inject lateinit var seedingManager: SeedingManager
    @Inject lateinit var notifications: DownloadNotifications

    /**
     * Keeps the notification's figures current while the service runs. Posts on the main thread,
     * like onDestroy: no update can land after the service took its notification away.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val ACTION_START_SERVICE = "start_service"
        const val ACTION_STOP_SERVICE = "stop_service"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        torrentHandleRegistry.start()
        torrentHandleRegistry.session().addListener(torrentProgressBridge)

        // Keep the CPU awake so downloads continue when the screen is off.
        // Released in onDestroy when all downloads have finished.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dogmatix:DownloadWakeLock").apply {
            acquire()
        }
        scope.launch {
            // Conflated + a pause per update: at most one post per REFRESH_MS, always the latest figures.
            notifications.progress().distinctUntilChanged().conflate().collect {
                notifications.updateOngoing(NOTIFICATION_ID, it)
                delay(DownloadNotifications.REFRESH_MS)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        downloadService.cancelAllDownloads()
        // Release live seeds first: stop() only pauses handles, so their cached data would pile up.
        seedingManager.stopAll()
        torrentHandleRegistry.session().removeListener(torrentProgressBridge)
        torrentHandleRegistry.stop()
        wakeLock?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> promoteToForeground()
            ACTION_STOP_SERVICE -> stopSelf()
        }
        return START_STICKY
    }

    private fun promoteToForeground() {
        // Downloads run again: the "paused" notice left by Pause all has done its job.
        notifications.clearPaused()
        val notification = notifications.ongoing(notifications.currentProgress())

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override fun onBind(intent: Intent?) = null
}
