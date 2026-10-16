package com.cortinadev.dogmatix.data.repository

import com.cortinadev.dogmatix.data.model.DebridProvider
import com.cortinadev.dogmatix.data.model.SeedingMode
import com.cortinadev.dogmatix.data.model.PreferredRegion
import androidx.datastore.preferences.core.Preferences
import com.cortinadev.dogmatix.util.AutoScanFrequency
import com.cortinadev.dogmatix.util.TagColorKind
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val downloadDirectory: Flow<String>
    val separateByConsole: Flow<Boolean>
    val limitSpeed: Flow<Float>
    val autoUnzip: Flow<Boolean>
    val concurrentDownloads: Flow<Int>
    val metadataTimeoutSeconds: Flow<Int>
    val maxSearchResults: Flow<Int>
    val consoleDownloadDirectories: Flow<Map<String, String>>
    val themeMode: Flow<String>
    val gamepadLayout: Flow<String>
    val swapFaceButtons: Flow<Boolean>
    val accentColor: Flow<String>
    val favoriteLanguages: Flow<Set<String>>
    val onboardingDone: Flow<Boolean>
    val debridProvider: Flow<DebridProvider>
    val seedingMode: Flow<SeedingMode>
    val torboxApiKey: Flow<String>
    val realDebridApiKey: Flow<String>
    val esdeDirectory: Flow<String>
    val iisuDirectory: Flow<String>
    val rommUrl: Flow<String>
    val rommToken: Flow<String>
    val rommAutoUpload: Flow<Boolean>
    val rommPlatformMap: Flow<Map<String, Int>>
    /** Square covers in front of the library rows (on by default). */
    val showListCovers: Flow<Boolean>
    /** Colours the user picked per tag group; a missing group uses [TagColorKind.defaultArgb]. */
    val tagColors: Flow<Map<TagColorKind, Int>>

    suspend fun updateDownloadDirectory(path: String): Preferences
    suspend fun setSeparateByConsole(enabled: Boolean): Preferences
    suspend fun setLimitSpeed(limit: Float): Preferences
    suspend fun setAutoUnzip(enabled: Boolean): Preferences
    suspend fun setConcurrentDownloads(count: Int): Preferences
    suspend fun setMetadataTimeoutSeconds(seconds: Int): Preferences
    suspend fun setMaxSearchResults(max: Int): Preferences
    suspend fun updateConsoleDownloadDirectory(consoleId: String, path: String)
    suspend fun setThemeMode(mode: String): Preferences
    suspend fun setGamepadLayout(layout: String): Preferences
    suspend fun setSwapFaceButtons(enabled: Boolean): Preferences
    suspend fun setAccentColor(hex: String): Preferences
    suspend fun setFavoriteLanguages(tags: Set<String>): Preferences
    suspend fun setOnboardingDone(done: Boolean): Preferences
    suspend fun setDebridProvider(provider: DebridProvider): Preferences
    suspend fun setSeedingMode(mode: SeedingMode): Preferences
    suspend fun setTorboxApiKey(key: String): Preferences
    suspend fun setRealDebridApiKey(key: String): Preferences
    suspend fun setEsdeDirectory(uri: String): Preferences
    suspend fun setIisuDirectory(uri: String): Preferences
    suspend fun setRommUrl(url: String): Preferences
    suspend fun setRommToken(token: String): Preferences
    suspend fun setRommAutoUpload(enabled: Boolean): Preferences
    suspend fun updateRommPlatform(consoleId: String, platformId: Int?)
    suspend fun setShowListCovers(enabled: Boolean): Preferences
    /** [argb] null = back to the default colour of [kind]. */
    suspend fun setTagColor(kind: TagColorKind, argb: Int?): Preferences
    suspend fun resetTagColors(): Preferences

    // Download retries, per-server limit, multi-disc playlists and the preferred region
    val autoRetryAttempts: Flow<Int>
    val perHostDownloads: Flow<Int>
    val multiDiscPlaylists: Flow<Boolean>
    val preferredRegion: Flow<PreferredRegion>
    suspend fun setAutoRetryAttempts(attempts: Int): Preferences
    suspend fun setPerHostDownloads(max: Int): Preferences
    suspend fun setMultiDiscPlaylists(enabled: Boolean): Preferences
    suspend fun setPreferredRegion(region: PreferredRegion): Preferences

    // escaneo
    val autoScanFrequency: Flow<AutoScanFrequency>
    val autoScanWifiOnly: Flow<Boolean>
    val autoScanChargingOnly: Flow<Boolean>
    /** When the last scan of every source ran to the end (0 = never); the background scan counts from it. */
    val lastFullScanAt: Flow<Long>
    suspend fun setAutoScanFrequency(frequency: AutoScanFrequency): Preferences
    suspend fun setAutoScanWifiOnly(enabled: Boolean): Preferences
    suspend fun setAutoScanChargingOnly(enabled: Boolean): Preferences
    suspend fun setLastFullScanAt(millis: Long): Preferences

    // dl-motor
    val verifyDownloads: Flow<Boolean>
    val datAutoDownload: Flow<Boolean>
    suspend fun setVerifyDownloads(enabled: Boolean): Preferences
    suspend fun setDatAutoDownload(enabled: Boolean): Preferences

    // dl-cola
    val batchNotification: Flow<Boolean>
    val notificationPermissionAsked: Flow<Boolean>
    suspend fun setBatchNotification(enabled: Boolean): Preferences
    suspend fun setNotificationPermissionAsked(): Preferences

    // romm-transporte
    /** The RomM certificate trusted by hand ([com.cortinadev.dogmatix.util.CertPin.encode]); "" = none. */
    val rommCertPin: Flow<String>
    suspend fun setRommCertPin(value: String): Preferences

    // frontends
    /** `system|path` of the ES-DE favourites both sides had at the last sync. */
    val esdeFavouritesSynced: Flow<Set<String>>
    suspend fun setEsdeFavouritesSynced(keys: Set<String>): Preferences

    // busqueda
    /** Recent library searches, newest first. */
    val recentSearches: Flow<List<String>>
    suspend fun updateRecentSearches(transform: (List<String>) -> List<String>): Preferences

    // romm-catalogo
    val rommFavouritesSync: Flow<Boolean>
    val rommCatalogRefreshedAt: Flow<Long>
    val rommCatalogSignature: Flow<String>
    suspend fun setRommFavouritesSync(enabled: Boolean): Preferences
    suspend fun setRommCatalogState(signature: String, refreshedAt: Long): Preferences

    // coleccion-analisis
    val collectionTool: Flow<String>
    suspend fun setCollectionTool(tool: String): Preferences

    // coleccion-gestion
    /** Days a deleted game stays in the trash (7 or 30). */
    val trashRetentionDays: Flow<Int>
    /** Second library root of smart storage (SAF tree URI); empty when not set. */
    val secondaryStorageRoot: Flow<String>
    suspend fun setTrashRetentionDays(days: Int): Preferences
    suspend fun setSecondaryStorageRoot(uri: String): Preferences
}
