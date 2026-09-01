package com.cortinadev.dogmatix.data.repository

import com.cortinadev.dogmatix.data.model.DebridProvider
import androidx.datastore.preferences.core.Preferences
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
    val torboxApiKey: Flow<String>
    val realDebridApiKey: Flow<String>
    val esdeDirectory: Flow<String>
    val iisuDirectory: Flow<String>
    val rommUrl: Flow<String>
    val rommToken: Flow<String>
    val rommAutoUpload: Flow<Boolean>
    val rommPlatformMap: Flow<Map<String, Int>>

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
    suspend fun setTorboxApiKey(key: String): Preferences
    suspend fun setRealDebridApiKey(key: String): Preferences
    suspend fun setEsdeDirectory(uri: String): Preferences
    suspend fun setIisuDirectory(uri: String): Preferences
    suspend fun setRommUrl(url: String): Preferences
    suspend fun setRommToken(token: String): Preferences
    suspend fun setRommAutoUpload(enabled: Boolean): Preferences
    suspend fun updateRommPlatform(consoleId: String, platformId: Int?)
}
