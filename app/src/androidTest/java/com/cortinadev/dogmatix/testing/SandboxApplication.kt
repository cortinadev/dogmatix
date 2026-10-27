package com.cortinadev.dogmatix.testing

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.cortinadev.dogmatix.DogmatixApplication
import com.cortinadev.dogmatix.R
import java.io.File

/**
 * The application every instrumented test runs in (through [DogmatixTestRunner]), instead of
 * [DogmatixApplication]. Tests run inside the installed app's process, so this keeps them away
 * from the app's own data: files, cache, no-backup files and databases all resolve under
 * `<data dir>/instrumentation-sandbox/`, which is wiped when the run starts and when it ends.
 * DataStore (`user_settings`), Room (`dogmatix_db`) and the caches the app builds from the
 * application context land there, so a test can never read or overwrite the user's library,
 * settings or downloads. None of the app's startup work runs either (update check, ES-DE sync,
 * RomM catalogue…): a test builds what it needs.
 *
 * What it cannot redirect: an Activity's own `filesDir` (UI tests), SAF permissions,
 * JobScheduler jobs and notification channels, which belong to the package (the UI test puts
 * back the background scan job it may cancel).
 */
open class SandboxApplication : Application() {

    private val sandbox: File by lazy { sandboxOf(this) }

    private fun dir(name: String): File = File(sandbox, name).apply { mkdirs() }

    override fun getFilesDir(): File = dir("files")
    override fun getCacheDir(): File = dir("cache")
    override fun getNoBackupFilesDir(): File = dir("no_backup")
    override fun getDir(name: String, mode: Int): File = dir("app_$name")

    override fun getDatabasePath(name: String): File =
        if (name.startsWith(File.separator)) File(name) else File(dir("databases"), name)

    override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))

    override fun onCreate() {
        wipe(this)
        super.onCreate()
        // Starting a download needs its channel; the package keeps it from the real app anyway.
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                DogmatixApplication.DOWNLOAD_CHANNEL_ID,
                getString(R.string.notification_channel_downloads),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        const val SANDBOX_DIR = "instrumentation-sandbox"

        /** The sandbox of [context]'s package (its real data directory, never redirected). */
        fun sandboxOf(context: Context): File {
            val base = if (context is SandboxApplication) context.baseContext else context
            return File(base.dataDir, SANDBOX_DIR)
        }

        fun wipe(context: Context) {
            sandboxOf(context).deleteRecursively()
        }
    }
}
