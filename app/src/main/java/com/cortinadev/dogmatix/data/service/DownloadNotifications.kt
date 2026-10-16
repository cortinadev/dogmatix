package com.cortinadev.dogmatix.data.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cortinadev.dogmatix.DogmatixApplication
import com.cortinadev.dogmatix.MainActivity
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.state.PendingDownloadsView
import com.cortinadev.dogmatix.ui.components.formatBytes
import com.cortinadev.dogmatix.util.DownloadBatch
import com.cortinadev.dogmatix.util.DownloadBulk
import com.cortinadev.dogmatix.util.DownloadFilter
import com.cortinadev.dogmatix.util.QueueProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** A web download sits on FAILED this long at most between two attempts; an idle queue must outlast it. */
private const val QUIET_MS = 8_000L

/**
 * The download notifications: the foreground service's progress (built here, posted by
 * [DownloadForegroundService]) with Pause all / Resume / Cancel all, the "paused" notice left
 * behind by Pause all, and the notice when a batch ends ("3 downloads completed, 1 failed",
 * "Notice when downloads finish" in Settings). Buttons go to [DownloadActionReceiver]; a tap
 * opens Downloads.
 */
@Singleton
class DownloadNotifications @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloadService: DownloadService,
    private val downloadQueue: DownloadQueueService,
    private val autoRetry: DownloadAutoRetry,
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val batch = DownloadBatch()
    @Volatile private var pausedShown = false
    // Each paused job reaches PAUSED a moment after Pause all, as it winds down: until a paused row
    // shows up, an empty paused list does not mean the downloads were resumed.
    @Volatile private var pausedLanded = false

    init {
        val channel = NotificationChannel(
            RESULTS_CHANNEL_ID,
            context.getString(R.string.notification_channel_results),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = context.getString(R.string.notification_channel_results_desc) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        scope.launch { watchBatches() }
    }

    /** Progress of the whole queue, on every change of the list or the line (worked out off the main thread). */
    fun progress(): Flow<QueueProgress> = combine(downloadService.downloads, downloadQueue.state) { rows, queue ->
        QueueProgress.of(rows, queue.waitingSet, queue.held)
    }.flowOn(Dispatchers.Default)

    fun currentProgress(): QueueProgress = downloadQueue.state.value.let { queue ->
        QueueProgress.of(downloadService.getDownloads(), queue.waitingSet, queue.held)
    }

    /** The foreground service's notification. */
    fun ongoing(progress: QueueProgress): Notification {
        val title = when {
            progress.running + progress.waiting == 0 -> context.getString(R.string.notification_downloading)
            progress.waiting > 0 -> context.getString(R.string.notif_progress_title_queued, progress.running, progress.waiting)
            else -> context.getString(R.string.notif_progress_title, progress.running)
        }
        val text = if (progress.totalBytes > 0L) {
            val bytes = context.getString(R.string.notif_progress_bytes, formatBytes(progress.doneBytes), formatBytes(progress.totalBytes))
            if (progress.speed > 0f) context.getString(R.string.notif_progress_bytes_speed, bytes, context.getString(R.string.download_speed, progress.speed))
            else bytes
        } else context.getString(R.string.notification_downloading_text)
        val percent = progress.percent
        return NotificationCompat.Builder(context, DogmatixApplication.DOWNLOAD_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_arrow_down)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, percent ?: 0, percent == null)
            .setContentIntent(openDownloads(DownloadFilter.ALL))
            .apply {
                if (progress.canPause) addAction(R.drawable.ic_pause, context.getString(R.string.downloads_pause_all), action(ACTION_PAUSE_ALL))
                if (progress.canResume) addAction(R.drawable.ic_play, context.getString(R.string.notif_resume), action(ACTION_RESUME_ALL))
                if (progress.canCancel) addAction(R.drawable.ic_stop, context.getString(R.string.notif_cancel_all), action(ACTION_CANCEL_ALL))
            }
            .build()
    }

    /** Re-posts the foreground notification with new figures (the service started it). */
    fun updateOngoing(id: Int, progress: QueueProgress) = post(id, ongoing(progress))

    /**
     * Pause all from the notification stops the foreground service, and its notification with it:
     * this one stays behind with Resume, so the downloads can be picked up from the same place.
     */
    fun showPaused(count: Int) {
        if (count <= 0) return
        val notification = NotificationCompat.Builder(context, DogmatixApplication.DOWNLOAD_CHANNEL_ID)
            .setContentTitle(context.resources.getQuantityString(R.plurals.notif_paused_title, count, count))
            .setContentText(context.getString(R.string.notif_paused_text))
            .setSmallIcon(R.drawable.ic_pause)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openDownloads(DownloadFilter.ALL))
            .addAction(R.drawable.ic_play, context.getString(R.string.notif_resume), action(ACTION_RESUME_ALL))
            .build()
        pausedLanded = false
        pausedShown = post(PAUSED_NOTIFICATION_ID, notification)
    }

    fun clearPaused() {
        if (!pausedShown) return
        pausedShown = false
        NotificationManagerCompat.from(context).cancel(PAUSED_NOTIFICATION_ID)
    }

    private fun showBatchDone(summary: DownloadBatch.Summary) {
        val res = context.resources
        val completed = res.getQuantityString(R.plurals.notif_batch_completed, summary.completed, summary.completed)
        val text = when {
            summary.failed == 0 -> completed
            summary.completed == 0 -> res.getQuantityString(R.plurals.notif_batch_failed_only, summary.failed, summary.failed)
            else -> context.getString(
                R.string.notif_batch_both, completed,
                res.getQuantityString(R.plurals.notif_batch_failed, summary.failed, summary.failed)
            )
        }
        val notification = NotificationCompat.Builder(context, RESULTS_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_batch_title))
            .setContentText(text)
            .setSmallIcon(if (summary.failed > 0) R.drawable.ic_error else R.drawable.ic_check)
            .setAutoCancel(true)
            // With failures the list opens on them.
            .setContentIntent(openDownloads(if (summary.failed > 0) DownloadFilter.FAILED else DownloadFilter.ALL))
            .build()
        post(BATCH_NOTIFICATION_ID, notification)
    }

    /**
     * Feeds every change to [DownloadBatch]. An idle queue is only taken as the end of the batch
     * after [QUIET_MS] without changes: between two attempts a web download shows FAILED.
     */
    private suspend fun watchBatches() {
        combine(downloadService.downloads, downloadQueue.state, autoRetry.nextRetryAt) { rows, queue, retries ->
            Triple(rows, queue.held, retries.keys)
        }.collectLatest { (rows, held, retrying) ->
            if (pausedShown) {
                if (DownloadBulk.resumable(rows).isNotEmpty()) pausedLanded = true else if (pausedLanded) clearPaused()
            }
            if (!DownloadBatch.anyBusy(rows, held, retrying)) delay(QUIET_MS)
            val summary = batch.update(rows, held, retrying) ?: return@collectLatest
            if (settingsRepository.batchNotification.first()) showBatchDone(summary)
        }
    }

    private fun openDownloads(filter: DownloadFilter): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN_BASE + filter.ordinal,
        Intent(context, MainActivity::class.java)
            .putExtra(PendingDownloadsView.EXTRA_DOWNLOADS_FILTER, filter.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun action(name: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_ACTION_BASE + ACTIONS.indexOf(name),
        Intent(context, DownloadActionReceiver::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Posts only when the user allows notifications (Android 13+ asks; a refusal is respected). */
    @SuppressLint("MissingPermission")
    private fun post(id: Int, notification: Notification): Boolean {
        val manager = NotificationManagerCompat.from(context)
        val allowed = manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        if (allowed) manager.notify(id, notification)
        return allowed
    }

    companion object {
        const val ACTION_PAUSE_ALL = "com.cortinadev.dogmatix.action.PAUSE_ALL_DOWNLOADS"
        const val ACTION_RESUME_ALL = "com.cortinadev.dogmatix.action.RESUME_ALL_DOWNLOADS"
        const val ACTION_CANCEL_ALL = "com.cortinadev.dogmatix.action.CANCEL_ALL_DOWNLOADS"
        private val ACTIONS = listOf(ACTION_PAUSE_ALL, ACTION_RESUME_ALL, ACTION_CANCEL_ALL)

        /** Refresh interval of the progress notification. */
        const val REFRESH_MS = 1_500L

        private const val RESULTS_CHANNEL_ID = "download_results_channel"
        // The foreground notification is 1 (DownloadForegroundService).
        private const val PAUSED_NOTIFICATION_ID = 2
        private const val BATCH_NOTIFICATION_ID = 3
        private const val REQUEST_OPEN_BASE = 100
        private const val REQUEST_ACTION_BASE = 200
    }
}
