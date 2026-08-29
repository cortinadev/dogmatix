package com.cortinadev.dogmatix.data.local

import com.cortinadev.dogmatix.data.model.DebridProvider
import com.cortinadev.dogmatix.util.TorrentConstants
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
    val METADATA_TIMEOUT_S = intPreferencesKey("metadata_timeout_s")
    val CONSOLE_DOWNLOAD_DIRECTORIES = stringSetPreferencesKey("console_download_directories")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val ACCENT_COLOR = stringPreferencesKey("accent_color")
    val FAVORITE_LANGUAGES = stringSetPreferencesKey("favorite_languages")
    val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    /** Legacy toggle (before [DEBRID_PROVIDER]); still read so an old "on" maps to TorBox. */
    val TORBOX_ENABLED = booleanPreferencesKey("torbox_enabled")
    /** A [DebridProvider] name. */
    val DEBRID_PROVIDER = stringPreferencesKey("debrid_provider")
    val TORBOX_API_KEY = stringPreferencesKey("torbox_api_key")
    val REAL_DEBRID_API_KEY = stringPreferencesKey("realdebrid_api_key")
    val ROMM_URL = stringPreferencesKey("romm_url")
    val ROMM_TOKEN = stringPreferencesKey("romm_token")
    val ROMM_AUTO_UPLOAD = booleanPreferencesKey("romm_auto_upload")
    /** `consoleId:platformId` entries, like [CONSOLE_DOWNLOAD_DIRECTORIES]. */
    val ROMM_PLATFORM_MAP = stringSetPreferencesKey("romm_platform_map")
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
    /** Seconds to wait for a magnet's metadata (rescan and direct torrent download) before giving up. */
    val metadataTimeoutSeconds: Flow<Int> = context.dataStore.data.map {
        it[SettingsKeys.METADATA_TIMEOUT_S] ?: TorrentConstants.DEFAULT_METADATA_TIMEOUT_S
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
    /** Torrent downloads go through this debrid service instead of libtorrent (NONE = direct). */
    val debridProvider: Flow<DebridProvider> = context.dataStore.data.map {
        it[SettingsKeys.DEBRID_PROVIDER]?.let(DebridProvider::fromName)
            ?: if (it[SettingsKeys.TORBOX_ENABLED] == true) DebridProvider.TORBOX else DebridProvider.NONE
    }
    val torboxApiKey: Flow<String> = context.dataStore.data.map { it[SettingsKeys.TORBOX_API_KEY] ?: "" }
    val realDebridApiKey: Flow<String> = context.dataStore.data.map { it[SettingsKeys.REAL_DEBRID_API_KEY] ?: "" }
    val rommUrl: Flow<String> = context.dataStore.data.map { it[SettingsKeys.ROMM_URL] ?: "" }
    val rommToken: Flow<String> = context.dataStore.data.map { it[SettingsKeys.ROMM_TOKEN] ?: "" }
    val rommAutoUpload: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.ROMM_AUTO_UPLOAD] ?: false }
    /** consoleId → RomM platform id. */
    val rommPlatformMap: Flow<Map<String, Int>> = context.dataStore.data.map { preferences ->
        (preferences[SettingsKeys.ROMM_PLATFORM_MAP] ?: emptySet()).mapNotNull {
            val (key, value) = it.split(":", limit = 2)
            value.toIntOrNull()?.let { id -> key to id }
        }.toMap()
    }
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
    suspend fun setMetadataTimeoutSeconds(seconds: Int) = context.dataStore.edit { it[SettingsKeys.METADATA_TIMEOUT_S] = seconds }
    suspend fun setConcurrentDownloads(count: Int) = context.dataStore.edit { it[SettingsKeys.CONCURRENT_DOWNLOADS] = count }
    suspend fun setThemeMode(mode: String) = context.dataStore.edit { it[SettingsKeys.THEME_MODE] = mode }
    suspend fun setAccentColor(hex: String) = context.dataStore.edit { it[SettingsKeys.ACCENT_COLOR] = hex }
    suspend fun setFavoriteLanguages(tags: Set<String>) = context.dataStore.edit { it[SettingsKeys.FAVORITE_LANGUAGES] = tags }
    suspend fun setOnboardingDone(done: Boolean) = context.dataStore.edit { it[SettingsKeys.ONBOARDING_DONE] = done }
    suspend fun setDebridProvider(provider: DebridProvider) = context.dataStore.edit { it[SettingsKeys.DEBRID_PROVIDER] = provider.name }
    suspend fun setTorboxApiKey(key: String) = context.dataStore.edit { it[SettingsKeys.TORBOX_API_KEY] = key.trim() }
    suspend fun setRealDebridApiKey(key: String) = context.dataStore.edit { it[SettingsKeys.REAL_DEBRID_API_KEY] = key.trim() }
    suspend fun setRommUrl(url: String) = context.dataStore.edit { it[SettingsKeys.ROMM_URL] = url.trim().trimEnd('/') }
    suspend fun setRommToken(token: String) = context.dataStore.edit { it[SettingsKeys.ROMM_TOKEN] = token.trim() }
    suspend fun setRommAutoUpload(enabled: Boolean) = context.dataStore.edit { it[SettingsKeys.ROMM_AUTO_UPLOAD] = enabled }
    /** [platformId] null removes the mapping. */
    suspend fun updateRommPlatform(consoleId: String, platformId: Int?) {
        context.dataStore.edit { settings ->
            val current = settings[SettingsKeys.ROMM_PLATFORM_MAP] ?: emptySet()
            val next = current.filterNot { it.startsWith("$consoleId:") }.toMutableSet()
            if (platformId != null) next.add("$consoleId:$platformId")
            settings[SettingsKeys.ROMM_PLATFORM_MAP] = next
        }
    }
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
