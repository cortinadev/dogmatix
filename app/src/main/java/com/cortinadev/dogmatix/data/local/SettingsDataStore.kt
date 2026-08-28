package com.cortinadev.dogmatix.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cortinadev.dogmatix.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore by preferencesDataStore(name = Constants.SETTINGS_DATASTORE_NAME)

object SettingsKeys {
    val DOWNLOAD_DIRECTORY = stringPreferencesKey("download_directory")
    val SEPARATE_BY_CONSOLE = booleanPreferencesKey("separate_by_console")
    val LIMIT_SPEED = floatPreferencesKey("limit_speed")
    val AUTO_UNZIP = booleanPreferencesKey("auto_unzip")
    val CONCURRENT_DOWNLOADS = intPreferencesKey("concurrent_downloads")
    val CONSOLE_DOWNLOAD_DIRECTORIES = stringSetPreferencesKey("console_download_directories")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val ACCENT_COLOR = stringPreferencesKey("accent_color")
    val FAVORITE_LANGUAGES = stringSetPreferencesKey("favorite_languages")
    val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
}

/** Language tags are upper-cased ISO codes ("EN", "ES"), matching how files are tagged. */
fun defaultFavoriteLanguages(): Set<String> =
    setOf(Locale.getDefault().language.uppercase(Locale.ROOT), "EN").filter { it.length in 2..3 }.toSet()

@Singleton
class SettingsDataStore @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    val downloadDirectory: Flow<String> = context.dataStore.data.map { 
        it[SettingsKeys.DOWNLOAD_DIRECTORY] ?: "" 
    }
    val separateByConsole: Flow<Boolean> = context.dataStore.data.map { 
        it[SettingsKeys.SEPARATE_BY_CONSOLE] ?: true 
    }
    val limitSpeed: Flow<Float> = context.dataStore.data.map { 
        it[SettingsKeys.LIMIT_SPEED] ?: Float.POSITIVE_INFINITY 
    }
    val autoUnzip: Flow<Boolean> = context.dataStore.data.map { 
        it[SettingsKeys.AUTO_UNZIP] ?: true 
    }
    val concurrentDownloads: Flow<Int> = context.dataStore.data.map { 
        it[SettingsKeys.CONCURRENT_DOWNLOADS] ?: Constants.DEFAULT_CONCURRENT_DOWNLOADS 
    }
    val themeMode: Flow<String> = context.dataStore.data.map { it[SettingsKeys.THEME_MODE] ?: "SYSTEM" }
    val accentColor: Flow<String> = context.dataStore.data.map { it[SettingsKeys.ACCENT_COLOR] ?: "" }
    /** Language tags shown first in the filter; defaults to the device language plus English. */
    val favoriteLanguages: Flow<Set<String>> = context.dataStore.data.map {
        it[SettingsKeys.FAVORITE_LANGUAGES] ?: defaultFavoriteLanguages()
    }
    /** Null until DataStore has been read, so the shell is not flashed before the onboarding decision. */
    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.ONBOARDING_DONE] ?: false }
    val consoleDownloadDirectories: Flow<Map<String, String>> = context.dataStore.data.map { preferences ->
        (preferences[SettingsKeys.CONSOLE_DOWNLOAD_DIRECTORIES] ?: emptySet()).associate {
            val (key, value) = it.split(":", limit = 2)
            key to value
        }
    }

    suspend fun updateDownloadDirectory(path: String) = context.dataStore.edit { it[SettingsKeys.DOWNLOAD_DIRECTORY] = path }
    suspend fun setSeparateByConsole(enabled: Boolean) = context.dataStore.edit { it[SettingsKeys.SEPARATE_BY_CONSOLE] = enabled }
    suspend fun setLimitSpeed(limit: Float) = context.dataStore.edit { it[SettingsKeys.LIMIT_SPEED] = limit}
    suspend fun setAutoUnzip(enabled: Boolean) = context.dataStore.edit { it[SettingsKeys.AUTO_UNZIP] = enabled }
    suspend fun setConcurrentDownloads(count: Int) = context.dataStore.edit { it[SettingsKeys.CONCURRENT_DOWNLOADS] = count }
    suspend fun setThemeMode(mode: String) = context.dataStore.edit { it[SettingsKeys.THEME_MODE] = mode }
    suspend fun setAccentColor(hex: String) = context.dataStore.edit { it[SettingsKeys.ACCENT_COLOR] = hex }
    suspend fun setFavoriteLanguages(tags: Set<String>) = context.dataStore.edit { it[SettingsKeys.FAVORITE_LANGUAGES] = tags }
    suspend fun setOnboardingDone(done: Boolean) = context.dataStore.edit { it[SettingsKeys.ONBOARDING_DONE] = done }
    suspend fun updateConsoleDownloadDirectory(consoleId: String, path: String) {
        context.dataStore.edit { settings ->
            val currentDirs = settings[SettingsKeys.CONSOLE_DOWNLOAD_DIRECTORIES] ?: emptySet()
            val newDirs = currentDirs.filterNot { it.startsWith("$consoleId:") }.toMutableSet()
            if (path.isNotEmpty()) {
                newDirs.add("$consoleId:$path")
            }
            settings[SettingsKeys.CONSOLE_DOWNLOAD_DIRECTORIES] = newDirs
        }
    }
}
