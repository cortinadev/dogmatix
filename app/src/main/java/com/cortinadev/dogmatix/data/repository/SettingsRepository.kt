package com.cortinadev.dogmatix.data.repository

import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val downloadDirectory: Flow<String>
    val separateByConsole: Flow<Boolean>
    val limitSpeed: Flow<Float>
    val autoUnzip: Flow<Boolean>
    val concurrentDownloads: Flow<Int>
    val consoleDownloadDirectories: Flow<Map<String, String>>
    val themeMode: Flow<String>
    val accentColor: Flow<String>
    val favoriteLanguages: Flow<Set<String>>
    val onboardingDone: Flow<Boolean>

    suspend fun updateDownloadDirectory(path: String): Preferences
    suspend fun setSeparateByConsole(enabled: Boolean): Preferences
    suspend fun setLimitSpeed(limit: Float): Preferences
    suspend fun setAutoUnzip(enabled: Boolean): Preferences
    suspend fun setConcurrentDownloads(count: Int): Preferences
    suspend fun updateConsoleDownloadDirectory(consoleId: String, path: String)
    suspend fun setThemeMode(mode: String): Preferences
    suspend fun setAccentColor(hex: String): Preferences
    suspend fun setFavoriteLanguages(tags: Set<String>): Preferences
    suspend fun setOnboardingDone(done: Boolean): Preferences
}
