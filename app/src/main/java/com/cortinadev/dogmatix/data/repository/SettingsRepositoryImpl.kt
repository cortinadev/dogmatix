package com.cortinadev.dogmatix.data.repository

import com.cortinadev.dogmatix.data.model.DebridProvider
import com.cortinadev.dogmatix.data.model.SeedingMode
import com.cortinadev.dogmatix.data.model.PreferredRegion
import androidx.datastore.preferences.core.Preferences
import com.cortinadev.dogmatix.data.local.SettingsDataStore
import com.cortinadev.dogmatix.util.AutoScanFrequency
import com.cortinadev.dogmatix.util.TagColorKind
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) : SettingsRepository {

    override val downloadDirectory: Flow<String> = settingsDataStore.downloadDirectory
    override val separateByConsole: Flow<Boolean> = settingsDataStore.separateByConsole
    override val limitSpeed: Flow<Float> = settingsDataStore.limitSpeed
    override val autoUnzip: Flow<Boolean> = settingsDataStore.autoUnzip
    override val concurrentDownloads: Flow<Int> = settingsDataStore.concurrentDownloads
    override val metadataTimeoutSeconds: Flow<Int> = settingsDataStore.metadataTimeoutSeconds
    override suspend fun setMetadataTimeoutSeconds(seconds: Int): Preferences = settingsDataStore.setMetadataTimeoutSeconds(seconds)
    override val maxSearchResults: Flow<Int> = settingsDataStore.maxSearchResults
    override suspend fun setMaxSearchResults(max: Int): Preferences = settingsDataStore.setMaxSearchResults(max)
    override val consoleDownloadDirectories: Flow<Map<String, String>> = settingsDataStore.consoleDownloadDirectories
    override val themeMode: Flow<String> = settingsDataStore.themeMode
    override val gamepadLayout: Flow<String> = settingsDataStore.gamepadLayout
    override val swapFaceButtons: Flow<Boolean> = settingsDataStore.swapFaceButtons
    override val accentColor: Flow<String> = settingsDataStore.accentColor
    override val favoriteLanguages: Flow<Set<String>> = settingsDataStore.favoriteLanguages
    override val onboardingDone: Flow<Boolean> = settingsDataStore.onboardingDone
    override val debridProvider: Flow<DebridProvider> = settingsDataStore.debridProvider
    override val seedingMode: Flow<SeedingMode> = settingsDataStore.seedingMode
    override suspend fun setSeedingMode(mode: SeedingMode): Preferences = settingsDataStore.setSeedingMode(mode)
    override val torboxApiKey: Flow<String> = settingsDataStore.torboxApiKey
    override val realDebridApiKey: Flow<String> = settingsDataStore.realDebridApiKey

    override suspend fun setDebridProvider(provider: DebridProvider): Preferences = settingsDataStore.setDebridProvider(provider)
    override suspend fun setTorboxApiKey(key: String): Preferences = settingsDataStore.setTorboxApiKey(key)
    override suspend fun setRealDebridApiKey(key: String): Preferences = settingsDataStore.setRealDebridApiKey(key)

    override val esdeDirectory: Flow<String> = settingsDataStore.esdeDirectory
    override suspend fun setEsdeDirectory(uri: String): Preferences = settingsDataStore.setEsdeDirectory(uri)
    override val iisuDirectory: Flow<String> = settingsDataStore.iisuDirectory
    override suspend fun setIisuDirectory(uri: String): Preferences = settingsDataStore.setIisuDirectory(uri)
    override val rommUrl: Flow<String> = settingsDataStore.rommUrl
    override val rommToken: Flow<String> = settingsDataStore.rommToken
    override val rommAutoUpload: Flow<Boolean> = settingsDataStore.rommAutoUpload
    override val rommPlatformMap: Flow<Map<String, Int>> = settingsDataStore.rommPlatformMap
    override suspend fun setRommUrl(url: String): Preferences = settingsDataStore.setRommUrl(url)
    override suspend fun setRommToken(token: String): Preferences = settingsDataStore.setRommToken(token)
    override suspend fun setRommAutoUpload(enabled: Boolean): Preferences = settingsDataStore.setRommAutoUpload(enabled)
    override suspend fun updateRommPlatform(consoleId: String, platformId: Int?) = settingsDataStore.updateRommPlatform(consoleId, platformId)

    override val showListCovers: Flow<Boolean> = settingsDataStore.showListCovers
    override val tagColors: Flow<Map<TagColorKind, Int>> = settingsDataStore.tagColors
    override suspend fun setShowListCovers(enabled: Boolean): Preferences = settingsDataStore.setShowListCovers(enabled)
    override suspend fun setTagColor(kind: TagColorKind, argb: Int?): Preferences = settingsDataStore.setTagColor(kind, argb)
    override suspend fun resetTagColors(): Preferences = settingsDataStore.resetTagColors()

    override suspend fun setOnboardingDone(done: Boolean): Preferences = settingsDataStore.setOnboardingDone(done)

    override suspend fun updateDownloadDirectory(path: String): Preferences {
        return settingsDataStore.updateDownloadDirectory(path)
    }

    override suspend fun setSeparateByConsole(enabled: Boolean): Preferences {
        return settingsDataStore.setSeparateByConsole(enabled)
    }

    override suspend fun setLimitSpeed(limit: Float): Preferences {
        return settingsDataStore.setLimitSpeed(limit)
    }

    override suspend fun setAutoUnzip(enabled: Boolean): Preferences {
        return settingsDataStore.setAutoUnzip(enabled)
    }

    override suspend fun setConcurrentDownloads(count: Int): Preferences {
        return settingsDataStore.setConcurrentDownloads(count)
    }

    override suspend fun updateConsoleDownloadDirectory(consoleId: String, path: String) {
        settingsDataStore.updateConsoleDownloadDirectory(consoleId, path)
    }

    override suspend fun setThemeMode(mode: String): Preferences = settingsDataStore.setThemeMode(mode)

    override suspend fun setGamepadLayout(layout: String): Preferences = settingsDataStore.setGamepadLayout(layout)

    override suspend fun setSwapFaceButtons(enabled: Boolean): Preferences = settingsDataStore.setSwapFaceButtons(enabled)

    override suspend fun setAccentColor(hex: String): Preferences = settingsDataStore.setAccentColor(hex)

    override suspend fun setFavoriteLanguages(tags: Set<String>): Preferences = settingsDataStore.setFavoriteLanguages(tags)

    // Download retries, per-server limit, multi-disc playlists and the preferred region
    override val autoRetryAttempts: Flow<Int> = settingsDataStore.autoRetryAttempts
    override val perHostDownloads: Flow<Int> = settingsDataStore.perHostDownloads
    override val multiDiscPlaylists: Flow<Boolean> = settingsDataStore.multiDiscPlaylists
    override val preferredRegion: Flow<PreferredRegion> = settingsDataStore.preferredRegion
    override suspend fun setAutoRetryAttempts(attempts: Int): Preferences = settingsDataStore.setAutoRetryAttempts(attempts)
    override suspend fun setPerHostDownloads(max: Int): Preferences = settingsDataStore.setPerHostDownloads(max)
    override suspend fun setMultiDiscPlaylists(enabled: Boolean): Preferences = settingsDataStore.setMultiDiscPlaylists(enabled)
    override suspend fun setPreferredRegion(region: PreferredRegion): Preferences = settingsDataStore.setPreferredRegion(region)

    // escaneo
    override val autoScanFrequency: Flow<AutoScanFrequency> = settingsDataStore.autoScanFrequency
    override val autoScanWifiOnly: Flow<Boolean> = settingsDataStore.autoScanWifiOnly
    override val autoScanChargingOnly: Flow<Boolean> = settingsDataStore.autoScanChargingOnly
    override val lastFullScanAt: Flow<Long> = settingsDataStore.lastFullScanAt
    override suspend fun setAutoScanFrequency(frequency: AutoScanFrequency): Preferences = settingsDataStore.setAutoScanFrequency(frequency)
    override suspend fun setAutoScanWifiOnly(enabled: Boolean): Preferences = settingsDataStore.setAutoScanWifiOnly(enabled)
    override suspend fun setAutoScanChargingOnly(enabled: Boolean): Preferences = settingsDataStore.setAutoScanChargingOnly(enabled)
    override suspend fun setLastFullScanAt(millis: Long): Preferences = settingsDataStore.setLastFullScanAt(millis)

    // dl-motor
    override val verifyDownloads: Flow<Boolean> = settingsDataStore.verifyDownloads
    override val datAutoDownload: Flow<Boolean> = settingsDataStore.datAutoDownload
    override suspend fun setVerifyDownloads(enabled: Boolean): Preferences = settingsDataStore.setVerifyDownloads(enabled)
    override suspend fun setDatAutoDownload(enabled: Boolean): Preferences = settingsDataStore.setDatAutoDownload(enabled)

    // dl-cola
    override val batchNotification: Flow<Boolean> = settingsDataStore.batchNotification
    override val notificationPermissionAsked: Flow<Boolean> = settingsDataStore.notificationPermissionAsked
    override suspend fun setBatchNotification(enabled: Boolean): Preferences = settingsDataStore.setBatchNotification(enabled)
    override suspend fun setNotificationPermissionAsked(): Preferences = settingsDataStore.setNotificationPermissionAsked()

    // romm-transporte
    override val rommCertPin: Flow<String> = settingsDataStore.rommCertPin
    override suspend fun setRommCertPin(value: String): Preferences = settingsDataStore.setRommCertPin(value)

    // frontends
    override val esdeFavouritesSynced: Flow<Set<String>> = settingsDataStore.esdeFavouritesSynced
    override suspend fun setEsdeFavouritesSynced(keys: Set<String>): Preferences = settingsDataStore.setEsdeFavouritesSynced(keys)

    // busqueda
    override val recentSearches: Flow<List<String>> = settingsDataStore.recentSearches
    override suspend fun updateRecentSearches(transform: (List<String>) -> List<String>): Preferences =
        settingsDataStore.updateRecentSearches(transform)

    // romm-catalogo
    override val rommFavouritesSync: Flow<Boolean> = settingsDataStore.rommFavouritesSync
    override val rommCatalogRefreshedAt: Flow<Long> = settingsDataStore.rommCatalogRefreshedAt
    override val rommCatalogSignature: Flow<String> = settingsDataStore.rommCatalogSignature
    override suspend fun setRommFavouritesSync(enabled: Boolean): Preferences = settingsDataStore.setRommFavouritesSync(enabled)
    override suspend fun setRommCatalogState(signature: String, refreshedAt: Long): Preferences =
        settingsDataStore.setRommCatalogState(signature, refreshedAt)

    // coleccion-analisis
    override val collectionTool: Flow<String> = settingsDataStore.collectionTool
    override suspend fun setCollectionTool(tool: String): Preferences = settingsDataStore.setCollectionTool(tool)

    // coleccion-gestion
    override val trashRetentionDays: Flow<Int> = settingsDataStore.trashRetentionDays
    override val secondaryStorageRoot: Flow<String> = settingsDataStore.secondaryStorageRoot
    override suspend fun setTrashRetentionDays(days: Int): Preferences = settingsDataStore.setTrashRetentionDays(days)
    override suspend fun setSecondaryStorageRoot(uri: String): Preferences = settingsDataStore.setSecondaryStorageRoot(uri)
}
