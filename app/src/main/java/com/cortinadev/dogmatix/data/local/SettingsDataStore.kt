package com.cortinadev.dogmatix.data.local

import com.cortinadev.dogmatix.data.model.DebridProvider
import com.cortinadev.dogmatix.data.model.SeedingMode
import com.cortinadev.dogmatix.data.model.PreferredRegion
import com.cortinadev.dogmatix.util.AutoRetryPolicy
import com.cortinadev.dogmatix.util.AutoScanFrequency
import com.cortinadev.dogmatix.util.TorrentConstants
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cortinadev.dogmatix.util.ColorMath
import com.cortinadev.dogmatix.util.Constants
import com.cortinadev.dogmatix.util.RecentSearches
import com.cortinadev.dogmatix.util.TagColorKind
import com.cortinadev.dogmatix.util.TrashRules
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
    /** Rows per search in the library; 0 = no limit. */
    val MAX_SEARCH_RESULTS = intPreferencesKey("max_search_results")
    val CONSOLE_DOWNLOAD_DIRECTORIES = stringSetPreferencesKey("console_download_directories")
    val THEME_MODE = stringPreferencesKey("theme_mode")
    /** A [com.cortinadev.dogmatix.ui.common.GamepadLayout] name: how the pad's buttons are labelled. */
    val GAMEPAD_LAYOUT = stringPreferencesKey("gamepad_layout")
    /** The pad reports its face buttons the other way round: act on A/B and X/Y swapped. */
    val SWAP_FACE_BUTTONS = booleanPreferencesKey("swap_face_buttons")
    val ACCENT_COLOR = stringPreferencesKey("accent_color")
    val FAVORITE_LANGUAGES = stringSetPreferencesKey("favorite_languages")
    val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    /** Legacy toggle (before [DEBRID_PROVIDER]); still read so an old "on" maps to TorBox. */
    val TORBOX_ENABLED = booleanPreferencesKey("torbox_enabled")
    /** A [DebridProvider] name. */
    val DEBRID_PROVIDER = stringPreferencesKey("debrid_provider")
    /** A [SeedingMode] name: how long a finished torrent keeps uploading. */
    val SEED_AFTER_DOWNLOAD = stringPreferencesKey("seed_after_download")
    val TORBOX_API_KEY = stringPreferencesKey("torbox_api_key")
    val REAL_DEBRID_API_KEY = stringPreferencesKey("realdebrid_api_key")
    /** SAF tree URI of the ES-DE application data directory ("Configure ES-DE" in Settings). */
    val ESDE_DIRECTORY = stringPreferencesKey("esde_directory")
    /** SAF tree URI of iiSU's `iiSULauncher` data directory ("Configure iiSU" in Settings). */
    val IISU_DIRECTORY = stringPreferencesKey("iisu_directory")
    val ROMM_URL = stringPreferencesKey("romm_url")
    val ROMM_TOKEN = stringPreferencesKey("romm_token")
    val ROMM_AUTO_UPLOAD = booleanPreferencesKey("romm_auto_upload")
    /** `consoleId:platformId` entries, like [CONSOLE_DOWNLOAD_DIRECTORIES]. */
    val ROMM_PLATFORM_MAP = stringSetPreferencesKey("romm_platform_map")
    /** Square covers in front of the library rows. */
    val SHOW_LIST_COVERS = booleanPreferencesKey("show_list_covers")
    /** `KIND=#RRGGBB` entries ([TagColorKind] name): colours picked per tag group; absent = default. */
    val TAG_COLORS = stringSetPreferencesKey("tag_colors")

    // Download retries, per-server limit, multi-disc playlists and the preferred region
    /** Automatic retries of a failed download (0 = off); see [com.cortinadev.dogmatix.util.AutoRetryPolicy]. */
    val AUTO_RETRY_ATTEMPTS = intPreferencesKey("auto_retry_attempts")
    /** Simultaneous web downloads per server (0 = no limit beyond the global one). */
    val PER_HOST_DOWNLOADS = intPreferencesKey("per_host_downloads")
    /** Gather a complete multi-disc game into `<game>.m3u/` with its playlist. */
    val MULTI_DISC_PLAYLISTS = booleanPreferencesKey("multi_disc_playlists")
    /** A [com.cortinadev.dogmatix.data.model.PreferredRegion] name: which version of a game the library downloads. */
    val PREFERRED_REGION = stringPreferencesKey("preferred_region")

    // escaneo
    /** A [com.cortinadev.dogmatix.util.AutoScanFrequency] name: background scan of every source (OFF by default). */
    val AUTO_SCAN = stringPreferencesKey("auto_scan")
    val AUTO_SCAN_WIFI_ONLY = booleanPreferencesKey("auto_scan_wifi_only")
    val AUTO_SCAN_CHARGING_ONLY = booleanPreferencesKey("auto_scan_charging_only")
    /** Epoch millis of the last scan of every source that ran to the end (manual or automatic). */
    val LAST_FULL_SCAN_AT = longPreferencesKey("last_full_scan_at")

    // dl-motor
    /** Hash every finished download and compare it with RomM or a DAT. */
    val VERIFY_DOWNLOADS = booleanPreferencesKey("verify_downloads")
    /** Fetch the No-Intro / Redump DAT of a console from libretro-database when a check needs it. */
    val DAT_AUTO_DOWNLOAD = booleanPreferencesKey("dat_auto_download")

    // dl-cola
    /** A notification with how many downloads completed and failed once the queue runs dry. */
    val BATCH_NOTIFICATION = booleanPreferencesKey("batch_notification")
    /** Android 13+ notification permission already asked once (asked at the first download, never again). */
    val NOTIFICATION_PERMISSION_ASKED = booleanPreferencesKey("notification_permission_asked")

    // romm-transporte
    /** The RomM certificate trusted by hand, as `https://host:port sha256hex` ([com.cortinadev.dogmatix.util.CertPin]); "" = none. */
    val ROMM_CERT_PIN = stringPreferencesKey("romm_cert_pin")

    // frontends
    /** `system|path` of the ES-DE favourites both sides had at the last sync, and the ES-DE folder (see EsdeFavouriteSyncService). */
    val ESDE_FAVOURITES_SYNCED = stringSetPreferencesKey("esde_favourites_synced")

    // busqueda
    /** Recent library searches, newest first, one per line (see [com.cortinadev.dogmatix.util.RecentSearches]). */
    val RECENT_SEARCHES = stringPreferencesKey("recent_searches")

    // romm-catalogo
    /** Keep the stars and RomM's favourites collection in step. */
    val ROMM_FAVOURITES_SYNC = booleanPreferencesKey("romm_favourites_sync")
    /** When the server catalogue was last read, and for which server / mapping (a digest). */
    val ROMM_CATALOG_REFRESHED_AT = longPreferencesKey("romm_catalog_refreshed_at")
    val ROMM_CATALOG_SIGNATURE = stringPreferencesKey("romm_catalog_signature")

    // coleccion-analisis
    /** The Collection tool open last (a CollectionTool name). */
    val COLLECTION_TOOL = stringPreferencesKey("collection_tool")

    // coleccion-gestion
    /** Days a deleted game stays in the trash before it is emptied automatically (7 or 30). */
    val TRASH_RETENTION_DAYS = intPreferencesKey("trash_retention_days")
    /** SAF tree URI of the second library root (smart storage, normally on the SD card); empty = off. */
    val SECONDARY_STORAGE_ROOT = stringPreferencesKey("secondary_storage_root")
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
    /** Rows a library search returns before "Load more"; 0 = no limit. */
    val maxSearchResults: Flow<Int> = context.dataStore.data.map {
        it[SettingsKeys.MAX_SEARCH_RESULTS] ?: Constants.DEFAULT_MAX_SEARCH_RESULTS
    }
    val themeMode: Flow<String> = context.dataStore.data.map { it[SettingsKeys.THEME_MODE] ?: "SYSTEM" }
    val gamepadLayout: Flow<String> = context.dataStore.data.map { it[SettingsKeys.GAMEPAD_LAYOUT] ?: "" }
    val swapFaceButtons: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.SWAP_FACE_BUTTONS] ?: false }
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
    /** How long a finished torrent keeps uploading; OFF (the default) releases it right away. */
    val seedingMode: Flow<SeedingMode> = context.dataStore.data.map {
        SeedingMode.fromName(it[SettingsKeys.SEED_AFTER_DOWNLOAD])
    }
    val torboxApiKey: Flow<String> = context.dataStore.data.map { it[SettingsKeys.TORBOX_API_KEY] ?: "" }
    val realDebridApiKey: Flow<String> = context.dataStore.data.map { it[SettingsKeys.REAL_DEBRID_API_KEY] ?: "" }
    val esdeDirectory: Flow<String> = context.dataStore.data.map { it[SettingsKeys.ESDE_DIRECTORY] ?: "" }
    val iisuDirectory: Flow<String> = context.dataStore.data.map { it[SettingsKeys.IISU_DIRECTORY] ?: "" }
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
    val showListCovers: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.SHOW_LIST_COVERS] ?: true }

    val tagColors: Flow<Map<TagColorKind, Int>> = context.dataStore.data.map { preferences ->
        preferences[SettingsKeys.TAG_COLORS].orEmpty().mapNotNull { entry ->
            val kind = TagColorKind.entries.firstOrNull { it.name == entry.substringBefore('=') } ?: return@mapNotNull null
            ColorMath.parseHex(entry.substringAfter('='))?.let { kind to it }
        }.toMap()
    }

    suspend fun setShowListCovers(enabled: Boolean) = context.dataStore.edit { it[SettingsKeys.SHOW_LIST_COVERS] = enabled }

    /** [argb] null puts the group back on its default colour. */
    suspend fun setTagColor(kind: TagColorKind, argb: Int?) = context.dataStore.edit { settings ->
        val others = settings[SettingsKeys.TAG_COLORS].orEmpty().filterNot { it.substringBefore('=') == kind.name }
        settings[SettingsKeys.TAG_COLORS] = if (argb == null) others.toSet() else (others + "${kind.name}=${ColorMath.toHex(argb)}").toSet()
    }

    suspend fun resetTagColors() = context.dataStore.edit { it.remove(SettingsKeys.TAG_COLORS) }

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
    suspend fun setMaxSearchResults(max: Int) = context.dataStore.edit { it[SettingsKeys.MAX_SEARCH_RESULTS] = max }
    suspend fun setThemeMode(mode: String) = context.dataStore.edit { it[SettingsKeys.THEME_MODE] = mode }
    suspend fun setGamepadLayout(layout: String) = context.dataStore.edit { it[SettingsKeys.GAMEPAD_LAYOUT] = layout }
    suspend fun setSwapFaceButtons(enabled: Boolean) = context.dataStore.edit { it[SettingsKeys.SWAP_FACE_BUTTONS] = enabled }
    suspend fun setAccentColor(hex: String) = context.dataStore.edit { it[SettingsKeys.ACCENT_COLOR] = hex }
    suspend fun setFavoriteLanguages(tags: Set<String>) = context.dataStore.edit { it[SettingsKeys.FAVORITE_LANGUAGES] = tags }
    suspend fun setOnboardingDone(done: Boolean) = context.dataStore.edit { it[SettingsKeys.ONBOARDING_DONE] = done }
    suspend fun setDebridProvider(provider: DebridProvider) = context.dataStore.edit { it[SettingsKeys.DEBRID_PROVIDER] = provider.name }
    suspend fun setSeedingMode(mode: SeedingMode) = context.dataStore.edit { it[SettingsKeys.SEED_AFTER_DOWNLOAD] = mode.name }
    suspend fun setTorboxApiKey(key: String) = context.dataStore.edit { it[SettingsKeys.TORBOX_API_KEY] = key.trim() }
    suspend fun setRealDebridApiKey(key: String) = context.dataStore.edit { it[SettingsKeys.REAL_DEBRID_API_KEY] = key.trim() }
    suspend fun setEsdeDirectory(uri: String) = context.dataStore.edit { it[SettingsKeys.ESDE_DIRECTORY] = uri }
    suspend fun setIisuDirectory(uri: String) = context.dataStore.edit { it[SettingsKeys.IISU_DIRECTORY] = uri }
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

    // Download retries, per-server limit, multi-disc playlists and the preferred region
    val autoRetryAttempts: Flow<Int> = context.dataStore.data.map { it[SettingsKeys.AUTO_RETRY_ATTEMPTS] ?: AutoRetryPolicy.DEFAULT_ATTEMPTS }
    val perHostDownloads: Flow<Int> = context.dataStore.data.map { it[SettingsKeys.PER_HOST_DOWNLOADS] ?: 0 }
    val multiDiscPlaylists: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.MULTI_DISC_PLAYLISTS] ?: true }
    /** Defaults to the device's region until the user picks one (onboarding or Settings). */
    val preferredRegion: Flow<PreferredRegion> = context.dataStore.data.map {
        PreferredRegion.fromName(it[SettingsKeys.PREFERRED_REGION]) ?: PreferredRegion.forLocale()
    }
    suspend fun setAutoRetryAttempts(attempts: Int) = context.dataStore.edit { it[SettingsKeys.AUTO_RETRY_ATTEMPTS] = attempts }
    suspend fun setPerHostDownloads(max: Int) = context.dataStore.edit { it[SettingsKeys.PER_HOST_DOWNLOADS] = max }
    suspend fun setMultiDiscPlaylists(enabled: Boolean) = context.dataStore.edit { it[SettingsKeys.MULTI_DISC_PLAYLISTS] = enabled }
    suspend fun setPreferredRegion(region: PreferredRegion) = context.dataStore.edit { it[SettingsKeys.PREFERRED_REGION] = region.name }

    // escaneo
    val autoScanFrequency: Flow<AutoScanFrequency> = context.dataStore.data.map { AutoScanFrequency.fromName(it[SettingsKeys.AUTO_SCAN]) }
    val autoScanWifiOnly: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.AUTO_SCAN_WIFI_ONLY] ?: true }
    val autoScanChargingOnly: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.AUTO_SCAN_CHARGING_ONLY] ?: false }
    val lastFullScanAt: Flow<Long> = context.dataStore.data.map { it[SettingsKeys.LAST_FULL_SCAN_AT] ?: 0L }
    suspend fun setAutoScanFrequency(frequency: AutoScanFrequency) = context.dataStore.edit { it[SettingsKeys.AUTO_SCAN] = frequency.name }
    suspend fun setAutoScanWifiOnly(enabled: Boolean) = context.dataStore.edit { it[SettingsKeys.AUTO_SCAN_WIFI_ONLY] = enabled }
    suspend fun setAutoScanChargingOnly(enabled: Boolean) = context.dataStore.edit { it[SettingsKeys.AUTO_SCAN_CHARGING_ONLY] = enabled }
    suspend fun setLastFullScanAt(millis: Long) = context.dataStore.edit { it[SettingsKeys.LAST_FULL_SCAN_AT] = millis }

    // dl-motor
    val verifyDownloads: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.VERIFY_DOWNLOADS] ?: true }
    val datAutoDownload: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.DAT_AUTO_DOWNLOAD] ?: true }
    suspend fun setVerifyDownloads(enabled: Boolean) = context.dataStore.edit { it[SettingsKeys.VERIFY_DOWNLOADS] = enabled }
    suspend fun setDatAutoDownload(enabled: Boolean) = context.dataStore.edit { it[SettingsKeys.DAT_AUTO_DOWNLOAD] = enabled }

    // dl-cola
    val batchNotification: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.BATCH_NOTIFICATION] ?: true }
    val notificationPermissionAsked: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.NOTIFICATION_PERMISSION_ASKED] ?: false }
    suspend fun setBatchNotification(enabled: Boolean) = context.dataStore.edit { it[SettingsKeys.BATCH_NOTIFICATION] = enabled }
    suspend fun setNotificationPermissionAsked() = context.dataStore.edit { it[SettingsKeys.NOTIFICATION_PERMISSION_ASKED] = true }

    // romm-transporte
    val rommCertPin: Flow<String> = context.dataStore.data.map { it[SettingsKeys.ROMM_CERT_PIN] ?: "" }
    suspend fun setRommCertPin(value: String) = context.dataStore.edit { it[SettingsKeys.ROMM_CERT_PIN] = value }

    // frontends
    val esdeFavouritesSynced: Flow<Set<String>> = context.dataStore.data.map { it[SettingsKeys.ESDE_FAVOURITES_SYNCED] ?: emptySet() }
    suspend fun setEsdeFavouritesSynced(keys: Set<String>) = context.dataStore.edit { it[SettingsKeys.ESDE_FAVOURITES_SYNCED] = keys }

    // busqueda
    val recentSearches: Flow<List<String>> = context.dataStore.data.map { RecentSearches.decode(it[SettingsKeys.RECENT_SEARCHES].orEmpty()) }
    /** Read-modify-write in one edit, so two quick changes never lose one another. */
    suspend fun updateRecentSearches(transform: (List<String>) -> List<String>) = context.dataStore.edit {
        it[SettingsKeys.RECENT_SEARCHES] = RecentSearches.encode(transform(RecentSearches.decode(it[SettingsKeys.RECENT_SEARCHES].orEmpty())))
    }

    // romm-catalogo
    val rommFavouritesSync: Flow<Boolean> = context.dataStore.data.map { it[SettingsKeys.ROMM_FAVOURITES_SYNC] ?: false }
    val rommCatalogRefreshedAt: Flow<Long> = context.dataStore.data.map { it[SettingsKeys.ROMM_CATALOG_REFRESHED_AT] ?: 0L }
    val rommCatalogSignature: Flow<String> = context.dataStore.data.map { it[SettingsKeys.ROMM_CATALOG_SIGNATURE] ?: "" }
    suspend fun setRommFavouritesSync(enabled: Boolean) = context.dataStore.edit { it[SettingsKeys.ROMM_FAVOURITES_SYNC] = enabled }
    suspend fun setRommCatalogState(signature: String, refreshedAt: Long) = context.dataStore.edit {
        it[SettingsKeys.ROMM_CATALOG_SIGNATURE] = signature
        it[SettingsKeys.ROMM_CATALOG_REFRESHED_AT] = refreshedAt
    }

    // coleccion-analisis
    val collectionTool: Flow<String> = context.dataStore.data.map { it[SettingsKeys.COLLECTION_TOOL] ?: "" }
    suspend fun setCollectionTool(tool: String) = context.dataStore.edit { it[SettingsKeys.COLLECTION_TOOL] = tool }

    // coleccion-gestion
    val trashRetentionDays: Flow<Int> = context.dataStore.data.map { TrashRules.retentionOrDefault(it[SettingsKeys.TRASH_RETENTION_DAYS]) }
    val secondaryStorageRoot: Flow<String> = context.dataStore.data.map { it[SettingsKeys.SECONDARY_STORAGE_ROOT] ?: "" }
    suspend fun setTrashRetentionDays(days: Int) = context.dataStore.edit { it[SettingsKeys.TRASH_RETENTION_DAYS] = TrashRules.retentionOrDefault(days) }
    suspend fun setSecondaryStorageRoot(uri: String) = context.dataStore.edit { it[SettingsKeys.SECONDARY_STORAGE_ROOT] = uri }
}
