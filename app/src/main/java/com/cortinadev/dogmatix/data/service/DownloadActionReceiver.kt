package com.cortinadev.dogmatix.data.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cortinadev.dogmatix.di.DownloadActionEntryPoint
import com.cortinadev.dogmatix.util.DownloadBulk
import dagger.hilt.android.EntryPointAccessors

/**
 * The buttons of the download notifications: Pause all, Resume, Cancel all, the same whole-list
 * actions as the Downloads header. Not exported: only the app's own immutable PendingIntents
 * reach it. A tap on a notification button lets the app start the foreground service again.
 */
class DownloadActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, DownloadActionEntryPoint::class.java)
        val repository = entry.downloadRepository()
        val notifications = entry.downloadNotifications()
        when (intent.action) {
            DownloadNotifications.ACTION_PAUSE_ALL -> {
                val rows = repository.downloads.value
                val count = DownloadBulk.pausable(rows, entry.downloadQueue().heldNames()).size + DownloadBulk.resumable(rows).size
                repository.pauseAll()
                notifications.showPaused(count)
            }
            DownloadNotifications.ACTION_RESUME_ALL -> {
                notifications.clearPaused()
                repository.resumeAll()
            }
            DownloadNotifications.ACTION_CANCEL_ALL -> repository.stopAll()
        }
    }
}
