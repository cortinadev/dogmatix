package com.cortinadev.dogmatix.data.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.cortinadev.dogmatix.DogmatixApplication
import com.cortinadev.dogmatix.MainActivity
import com.cortinadev.dogmatix.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process and the CPU up while ROMs are converted ([RomConversionService]): an XISO of
 * 7 GB takes minutes, longer than Android lets a background app run. Its own service (not the
 * downloads' one, whose lifetime follows the download queue), of the data-sync type that covers
 * local file processing, on the downloads channel. Stops itself once nothing is queued; stopping it
 * from the system cancels the conversions (their originals stay untouched).
 */
@AndroidEntryPoint
class ConversionForegroundService : Service() {

    @Inject lateinit var conversions: RomConversionService

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false
    /** The latest start: stopping with an older one fails when a conversion was queued meanwhile. */
    @Volatile private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dogmatix:ConversionWakeLock").apply { acquire(MAX_WAKE_MS) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (!started) {
            started = true
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            scope.launch {
                conversions.jobs.collect {
                    if (!conversions.busy) {
                        stopSelfResult(lastStartId)
                        return@collect
                    }
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
                    delay(REFRESH_MS)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val jobs = conversions.jobs.value.values
        val current = jobs.firstOrNull { it.state == ConversionState.CONVERTING || it.state == ConversionState.VERIFYING || it.state == ConversionState.FINISHING }
        val waiting = jobs.count { it.state == ConversionState.QUEUED }
        val title = current?.let {
            getString(if (it.state == ConversionState.CONVERTING) R.string.conversion_notification_converting else R.string.conversion_notification_verifying, it.format.label)
        } ?: getString(R.string.conversion_notification_waiting)
        val text = listOfNotNull(current?.title, waiting.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.conversion_notification_queued, it, it) })
            .joinToString("  ·  ")
        val open = PendingIntent.getActivity(
            this, NOTIFICATION_ID, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, DogmatixApplication.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_convert)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setProgress(100, ((current?.progress ?: 0f) * 100).toInt(), current == null)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        // Killed by the system or the user while converting: stop cleanly (parts deleted, originals kept).
        if (conversions.busy) conversions.cancelAll()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private companion object {
        const val NOTIFICATION_ID = 7310
        const val REFRESH_MS = 1_500L
        /** A safety cap: the lock is released with the service anyway. */
        const val MAX_WAKE_MS = 6 * 60 * 60 * 1000L
    }
}
