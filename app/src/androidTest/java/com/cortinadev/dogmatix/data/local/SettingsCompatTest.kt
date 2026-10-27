package com.cortinadev.dogmatix.data.local

import androidx.compose.ui.graphics.Color
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cortinadev.dogmatix.data.model.DebridProvider
import com.cortinadev.dogmatix.data.model.PreferredRegion
import com.cortinadev.dogmatix.data.model.SeedingMode
import com.cortinadev.dogmatix.testing.SandboxApplication
import com.cortinadev.dogmatix.testing.TestData
import com.cortinadev.dogmatix.ui.common.GamepadLayout
import com.cortinadev.dogmatix.ui.screens.collection.CollectionTool
import com.cortinadev.dogmatix.ui.theme.AccentPresets
import com.cortinadev.dogmatix.ui.theme.ThemeMode
import com.cortinadev.dogmatix.util.AutoRetryPolicy
import com.cortinadev.dogmatix.util.AutoScanFrequency
import com.cortinadev.dogmatix.util.Constants
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Settings saved by older versions, or missing, read the way the app reads them. The DataStore
 * file is the sandbox's (see [SandboxApplication]), never the installed app's `user_settings`;
 * every test starts from an empty store and leaves it empty.
 */
@RunWith(AndroidJUnit4::class)
class SettingsCompatTest {

    private val context = TestData.context
    private val settings = SettingsDataStore(context)

    @Before
    fun clear() = runBlocking { context.dataStore.edit { it.clear() }; Unit }

    @After
    fun tearDown() = clear()

    private fun store(block: (MutablePreferences) -> Unit) = runBlocking {
        context.dataStore.edit { block(it) }
        Unit
    }

    @Test
    fun theStoreIsTheSandboxs() {
        store { it[booleanPreferencesKey("onboarding_done")] = true }
        val file = File(SandboxApplication.sandboxOf(context), "files/datastore/${Constants.SETTINGS_DATASTORE_NAME}.preferences_pb")
        assertTrue("$file", file.isFile)
    }

    @Test
    fun nothingSavedGivesTheDocumentedDefaults() = runBlocking {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName(settings.themeMode.first()))
        assertEquals(AccentPresets.default, AccentPresets.fromHex(settings.accentColor.first()))
        assertEquals(Float.POSITIVE_INFINITY, settings.limitSpeed.first())
        assertEquals(AutoScanFrequency.OFF, settings.autoScanFrequency.first())
        assertTrue(settings.autoScanWifiOnly.first())
        assertFalse(settings.autoScanChargingOnly.first())
        assertEquals(PreferredRegion.forLocale(), settings.preferredRegion.first())
        assertEquals(DebridProvider.NONE, settings.debridProvider.first())
        assertEquals(SeedingMode.OFF, settings.seedingMode.first())
        assertEquals(Constants.DEFAULT_CONCURRENT_DOWNLOADS, settings.concurrentDownloads.first())
        assertEquals(0, settings.perHostDownloads.first())
        assertEquals(Constants.DEFAULT_MAX_SEARCH_RESULTS, settings.maxSearchResults.first())
        assertEquals(AutoRetryPolicy.DEFAULT_ATTEMPTS, settings.autoRetryAttempts.first())
        assertEquals(30, settings.trashRetentionDays.first())
        assertEquals(CollectionTool.SUMMARY, CollectionTool.fromSaved(settings.collectionTool.first()))
        assertEquals(GamepadLayout.XBOX, GamepadLayout.fromName(settings.gamepadLayout.first()))
        assertEquals(defaultFavoriteLanguages(), settings.favoriteLanguages.first())
        assertTrue(settings.separateByConsole.first())
        assertTrue(settings.autoUnzip.first())
        assertTrue(settings.multiDiscPlaylists.first())
        assertTrue(settings.verifyDownloads.first())
        assertTrue(settings.datAutoDownload.first())
        assertTrue(settings.batchNotification.first())
        assertTrue(settings.showListCovers.first())
        assertTrue(settings.updateAutoCheck.first())
        assertFalse(settings.onboardingDone.first())
        assertFalse(settings.swapFaceButtons.first())
        assertEquals(emptyMap<String, String>(), settings.consoleDownloadDirectories.first())
        assertEquals(emptyList<String>(), settings.recentSearches.first())
    }

    @Test
    fun theOldTorBoxSwitchReadsAsTorBox() = runBlocking {
        store { it[booleanPreferencesKey("torbox_enabled")] = true }
        assertEquals(DebridProvider.TORBOX, settings.debridProvider.first())
        // Once a service is chosen, the old switch no longer counts.
        store { it[stringPreferencesKey("debrid_provider")] = "REAL_DEBRID" }
        assertEquals(DebridProvider.REAL_DEBRID, settings.debridProvider.first())
        store { it[stringPreferencesKey("debrid_provider")] = "NONE" }
        assertEquals(DebridProvider.NONE, settings.debridProvider.first())
        // A service this version does not know is off.
        store { it[stringPreferencesKey("debrid_provider")] = "ALL_DEBRID" }
        assertEquals(DebridProvider.NONE, settings.debridProvider.first())
        store {
            it.remove(stringPreferencesKey("debrid_provider"))
            it[booleanPreferencesKey("torbox_enabled")] = false
        }
        assertEquals(DebridProvider.NONE, settings.debridProvider.first())
    }

    @Test
    fun theOldStatisticsToolOpensSummary() = runBlocking {
        // «Estadísticas y almacenamiento» went into Summary in 1.3; whatever name it had saved opens Summary.
        listOf("STATISTICS", "STATS", "STORAGE", "STATISTICS_AND_STORAGE", "").forEach { old ->
            store { it[stringPreferencesKey("collection_tool")] = old }
            assertEquals(old, settings.collectionTool.first())
            assertEquals(old, CollectionTool.SUMMARY, CollectionTool.fromSaved(settings.collectionTool.first()))
        }
        store { it[stringPreferencesKey("collection_tool")] = "HISTORY" }
        assertEquals(CollectionTool.HISTORY, CollectionTool.fromSaved(settings.collectionTool.first()))
    }

    @Test
    fun unknownOrOldValuesFallBackToTheDefaults() = runBlocking {
        store {
            it[stringPreferencesKey("theme_mode")] = "AMOLED"
            it[stringPreferencesKey("accent_color")] = "#GG00ZZ"
            it[stringPreferencesKey("auto_scan")] = "HOURLY"
            it[stringPreferencesKey("preferred_region")] = "ATLANTIS"
            it[stringPreferencesKey("seed_after_download")] = "FOREVER"
            it[intPreferencesKey("trash_retention_days")] = 90
            it[stringPreferencesKey("gamepad_layout")] = "SEGA"
        }
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName(settings.themeMode.first()))
        assertEquals(AccentPresets.default, AccentPresets.fromHex(settings.accentColor.first()))
        assertEquals(AutoScanFrequency.OFF, settings.autoScanFrequency.first())
        assertEquals(PreferredRegion.forLocale(), settings.preferredRegion.first())
        assertEquals(SeedingMode.OFF, settings.seedingMode.first())
        assertEquals(30, settings.trashRetentionDays.first())
        assertEquals(GamepadLayout.XBOX, GamepadLayout.fromName(settings.gamepadLayout.first()))
    }

    @Test
    fun valuesSavedByOlderVersionsReadBack() = runBlocking {
        store {
            it[stringPreferencesKey("theme_mode")] = "TRUE_BLACK"
            it[stringPreferencesKey("accent_color")] = "#4E99FF"
            it[floatPreferencesKey("limit_speed")] = 2048f
            it[stringPreferencesKey("auto_scan")] = "WEEKLY"
            it[stringPreferencesKey("preferred_region")] = "SPAIN"
            it[intPreferencesKey("trash_retention_days")] = 7
            it[stringSetPreferencesKey("console_download_directories")] = setOf(
                "nintendo_snes:content://com.android.externalstorage.documents/tree/primary%3AROMS/document/primary%3AROMS%2Fsnes",
                "sony_playstation:content://com.android.externalstorage.documents/tree/6A3B-BB88%3AROMS/document/6A3B-BB88%3AROMS%2Fpsx"
            )
            it[stringSetPreferencesKey("romm_platform_map")] = setOf("nintendo_snes:19", "sony_playstation:x")
            it[stringPreferencesKey("recent_searches")] = "mario\nzelda\n\nmario"
        }
        assertEquals(ThemeMode.TRUE_BLACK, ThemeMode.fromName(settings.themeMode.first()))
        assertEquals(Color(0xFF4E99FF), AccentPresets.fromHex(settings.accentColor.first()))
        assertEquals(2048f, settings.limitSpeed.first())
        assertEquals(AutoScanFrequency.WEEKLY, settings.autoScanFrequency.first())
        assertEquals(PreferredRegion.SPAIN, settings.preferredRegion.first())
        assertEquals(7, settings.trashRetentionDays.first())
        // Per-console folders: split at the first ':' only (the URI has its own).
        val folders = settings.consoleDownloadDirectories.first()
        assertEquals(setOf("nintendo_snes", "sony_playstation"), folders.keys)
        assertTrue(folders.getValue("sony_playstation").startsWith("content://com.android.externalstorage.documents/tree/6A3B-BB88%3AROMS"))
        // A platform id that is not a number is skipped, not fatal.
        assertEquals(mapOf("nintendo_snes" to 19), settings.rommPlatformMap.first())
        assertEquals(listOf("mario", "zelda"), settings.recentSearches.first().take(2))
    }
}
