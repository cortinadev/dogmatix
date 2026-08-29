package com.cortinadev.dogmatix.data.repository

import com.cortinadev.dogmatix.data.model.DebridProvider
import androidx.datastore.preferences.core.Preferences
import com.cortinadev.dogmatix.data.local.SettingsDataStore
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
    override val consoleDownloadDirectories: Flow<Map<String, String>> = settingsDataStore.consoleDownloadDirectories
    override val themeMode: Flow<String> = settingsDataStore.themeMode
    override val accentColor: Flow<String> = settingsDataStore.accentColor
    override val favoriteLanguages: Flow<Set<String>> = settingsDataStore.favoriteLanguages
    override val onboardingDone: Flow<Boolean> = settingsDataStore.onboardingDone
    override val debridProvider: Flow<DebridProvider> = settingsDataStore.debridProvider
    override val torboxApiKey: Flow<String> = settingsDataStore.torboxApiKey
    override val realDebridApiKey: Flow<String> = settingsDataStore.realDebridApiKey

    override suspend fun setDebridProvider(provider: DebridProvider): Preferences = settingsDataStore.setDebridProvider(provider)
    override suspend fun setTorboxApiKey(key: String): Preferences = settingsDataStore.setTorboxApiKey(key)
    override suspend fun setRealDebridApiKey(key: String): Preferences = settingsDataStore.setRealDebridApiKey(key)

    override val rommUrl: Flow<String> = settingsDataStore.rommUrl
    override val rommToken: Flow<String> = settingsDataStore.rommToken
    override val rommAutoUpload: Flow<Boolean> = settingsDataStore.rommAutoUpload
    override val rommPlatformMap: Flow<Map<String, Int>> = settingsDataStore.rommPlatformMap
    override suspend fun setRommUrl(url: String): Preferences = settingsDataStore.setRommUrl(url)
    override suspend fun setRommToken(token: String): Preferences = settingsDataStore.setRommToken(token)
    override suspend fun setRommAutoUpload(enabled: Boolean): Preferences = settingsDataStore.setRommAutoUpload(enabled)
    override suspend fun updateRommPlatform(consoleId: String, platformId: Int?) = settingsDataStore.updateRommPlatform(consoleId, platformId)

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

    override suspend fun setAccentColor(hex: String): Preferences = settingsDataStore.setAccentColor(hex)

    override suspend fun setFavoriteLanguages(tags: Set<String>): Preferences = settingsDataStore.setFavoriteLanguages(tags)
}
