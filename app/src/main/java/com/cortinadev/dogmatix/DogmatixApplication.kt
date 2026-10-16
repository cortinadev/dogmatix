package com.cortinadev.dogmatix

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.sf.sevenzipjbinding.SevenZip
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class DogmatixApplication : Application(), ImageLoaderFactory {
    
    @Inject
    lateinit var versionCheckerService: com.cortinadev.dogmatix.data.service.VersionCheckerService

    @Inject
    lateinit var downloadableFileRepository: com.cortinadev.dogmatix.data.repository.DownloadableFileRepository

    @Inject
    lateinit var sourcesRepository: com.cortinadev.dogmatix.data.repository.SourcesRepository

    /** Injected so it starts watching finished downloads from the first one. */
    @Inject
    lateinit var rommUploadService: com.cortinadev.dogmatix.data.service.RommUploadService

    // Both watch the downloads from the first one, like the RomM upload.
    @Inject
    lateinit var downloadAutoRetry: com.cortinadev.dogmatix.data.service.DownloadAutoRetry

    @Inject
    lateinit var multiDiscService: com.cortinadev.dogmatix.data.service.MultiDiscService

    // dl-motor: checks every finished download against RomM / the console's DAT.
    @Inject
    lateinit var downloadVerifier: com.cortinadev.dogmatix.data.service.DownloadVerifier

    // dl-cola: watches the queue from the first download to tell when a batch is over.
    @Inject
    lateinit var downloadNotifications: com.cortinadev.dogmatix.data.service.DownloadNotifications

    // romm-transporte: images from a RomM server with a self-signed certificate (see RommTls).
    @Inject
    lateinit var rommTls: com.cortinadev.dogmatix.data.service.RommTls

    // frontends: carries ★ changes over to ES-DE and syncs its favourites once after launch
    @Inject
    lateinit var esdeFavouriteSync: com.cortinadev.dogmatix.data.service.EsdeFavouriteSyncService

    // romm-catalogo: the RomM catalogue reads itself on start / foreground, the favourites sync
    // watches the stars from the first one.
    @Inject
    lateinit var rommCatalogService: com.cortinadev.dogmatix.data.service.RommCatalogService

    @Inject
    lateinit var rommFavouritesSync: com.cortinadev.dogmatix.data.service.RommFavouritesSyncService

    // coleccion-analisis: refreshes the Collection analysis after downloads.
    @Inject
    lateinit var libraryScanService: com.cortinadev.dogmatix.data.service.LibraryScanService

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        applicationScope.launch {
            clearStaleCache()

            // Load the native 7-zip library (blocking — must run on IO thread)
            try {
                SevenZip.initSevenZipFromPlatformJAR()
                Log.d("DogmatixApplication", "7-Zip native library loaded")
            } catch (e: Exception) {
                Log.e("DogmatixApplication", "Failed to load 7-Zip native library: ${e.message}")
            }
        }

        // Check for updates on app startup
        applicationScope.launch {
            versionCheckerService.checkForUpdates(this@DogmatixApplication)
        }

        // Short names / folder aliases configured per console, kept in sync for the static helpers
        applicationScope.launch {
            sourcesRepository.aliasOverrides.collect { com.cortinadev.dogmatix.util.ConsoleAliasRegistry.overrides = it }
        }

        // Files indexed before the search key column existed need it computed once
        applicationScope.launch {
            try {
                downloadableFileRepository.backfillSearchKeys()
            } catch (e: Exception) {
                Log.e("DogmatixApplication", "Search key backfill failed: ${e.message}")
            }
        }
    }

    /**
     * Coil's app-wide loader (covers, details artwork). libretro-thumbnails is served with
     * five-minute cache headers, but a cover never changes: keep the disk copy regardless.
     * Its OkHttp client also trusts the RomM server's certificate, on that server only.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .respectCacheHeaders(false)
        .okHttpClient { rommTls.imageClient() }
        .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            DOWNLOAD_CHANNEL_ID,
            getString(R.string.notification_channel_downloads),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_downloads_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Deletes cache directories left over from downloads that were interrupted before
     * they could finish copying to the user's storage and clean up after themselves.
     * Safe to run at startup because no downloads are active yet.
     */
    private fun clearStaleCache() {
        listOf("extraction_temp", "torrent_data").forEach { dir ->
            val cacheDir = File(cacheDir, dir)
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
                Log.d("DogmatixApplication", "Cleared stale cache: $dir")
            }
        }
    }

    companion object {
        const val DOWNLOAD_CHANNEL_ID = "download_channel"
    }
}