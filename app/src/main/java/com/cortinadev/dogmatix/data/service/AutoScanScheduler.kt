package com.cortinadev.dogmatix.data.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.cortinadev.dogmatix.MainActivity
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.AutoScanFrequency
import com.cortinadev.dogmatix.util.AutoScanSchedule
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AutoScan"
private const val JOB_ID = 7301
private const val NOTIFICATION_ID = 7302
private const val SCAN_CHANNEL_ID = "scan_channel"

/**
 * Keeps the background scan in step with Settings → Automatic scan. While it is on, a periodic
 * JobScheduler job wakes up every [AutoScanSchedule.CHECK_EVERY_MS] that its conditions hold
 * (Wi-Fi, charging) and scans when [AutoScanSchedule.isDue]. The job survives a reboot.
 *
 * JobScheduler rather than WorkManager: minSdk 29 already has everything this needs (periodic,
 * unmetered network, charging, persisted), and it adds no library, no second database and no
 * initializer to the app.
 */
@Singleton
class AutoScanScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    private data class Plan(val frequency: AutoScanFrequency, val wifiOnly: Boolean, val chargingOnly: Boolean)

    /** Starts following the settings (idempotent); called once the app starts. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(
                settingsRepository.autoScanFrequency,
                settingsRepository.autoScanWifiOnly,
                settingsRepository.autoScanChargingOnly
            ) { frequency, wifi, charging -> Plan(frequency, wifi, charging) }
                .distinctUntilChanged()
                .collect { runCatching { apply(it) }.onFailure { e -> Log.w(TAG, "Could not schedule: ${e.message}") } }
        }
    }

    private fun apply(plan: Plan) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (plan.frequency == AutoScanFrequency.OFF) {
            scheduler.cancel(JOB_ID)
            return
        }
        val network = if (plan.wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
        // Rescheduling restarts the period: leave an identical job alone, or opening the app
        // every hour would keep pushing the check back.
        val pending = scheduler.getPendingJob(JOB_ID)
        @Suppress("DEPRECATION")
        if (pending != null && pending.networkType == network && pending.isRequireCharging == plan.chargingOnly &&
            pending.intervalMillis == AutoScanSchedule.CHECK_EVERY_MS && pending.isPersisted) return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, AutoScanJobService::class.java))
            .setPeriodic(AutoScanSchedule.CHECK_EVERY_MS)
            .setRequiredNetworkType(network)
            .setRequiresCharging(plan.chargingOnly)
            .setPersisted(true)
            .build()
        val result = scheduler.schedule(job)
        Log.i(TAG, "Background scan check ${if (result == JobScheduler.RESULT_SUCCESS) "scheduled" else "refused"}: $plan")
    }
}

/** A discreet notice after a background scan that found new games, if notifications are allowed. */
@Singleton
class ScanNotifier @Inject constructor(@param:ApplicationContext private val context: Context) {

    fun newGames(summary: ScanSummary) {
        if (summary.newGames <= 0 || !allowed()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(SCAN_CHANNEL_ID, context.getString(R.string.scan_notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.scan_notification_channel_desc)
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, SCAN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_retry)
            .setContentTitle(context.resources.getQuantityString(R.plurals.scan_notification_new_games, summary.newGames, summary.newGames))
            .setContentText(context.getString(R.string.scan_notification_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification not allowed: ${e.message}")
        }
    }

    /** Android 13+ asks for the permission (requested elsewhere); before that, the app-wide switch decides. */
    private fun allowed(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
    }
}

/** One wake-up of the background scan: scans when due, then tells about new games. */
@AndroidEntryPoint
class AutoScanJobService : JobService() {
    @Inject lateinit var scanService: SourceScanService
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var notifier: ScanNotifier

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            var stopped = false
            try {
                val frequency = settingsRepository.autoScanFrequency.first()
                val last = settingsRepository.lastFullScanAt.first()
                if (AutoScanSchedule.isDue(System.currentTimeMillis(), last, frequency)) {
                    scanService.runAutoScan()?.let(notifier::newGames)
                }
            } catch (e: CancellationException) {
                stopped = true
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Background scan failed: ${e.message}")
            } finally {
                if (!stopped) jobFinished(params, false)
            }
        }
        return true
    }

    /** Conditions lost (Wi-Fi, charger): stop; the next check picks it up again. */
    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
