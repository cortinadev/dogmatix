package com.cortinadev.dogmatix.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.repository.DownloadableFileRepository
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.ui.common.executeWithToast
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import androidx.compose.ui.graphics.Color
import com.cortinadev.dogmatix.ui.theme.AccentPresets
import com.cortinadev.dogmatix.ui.theme.ThemeMode
import kotlinx.coroutines.flow.map
import javax.inject.Inject

data class SettingsUiState(
    val downloadDirectory: String = "",
    val separateByConsole: Boolean = true,
    val limitSpeed: Float = Float.POSITIVE_INFINITY,
    val autoUnzip: Boolean = true,
    val concurrentDownloads: Int = 3,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: Color = AccentPresets.default,
    val favoriteLanguages: Set<String> = emptySet(),
    val isLoading: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val fileRepository: DownloadableFileRepository
) : ViewModel() {

    /** Every language tag present in the library, so favorites can be picked from real data. */
    private val _availableLanguages = MutableStateFlow<List<String>>(emptyList())
    val availableLanguages: StateFlow<List<String>> = _availableLanguages.asStateFlow()

    fun loadAvailableLanguages() {
        viewModelScope.launch {
            _availableLanguages.value = runCatching { fileRepository.getCategorizedTags("*").languages.tags }
                .getOrDefault(emptyList())
        }
    }

    /** Null until DataStore answers; MainActivity shows nothing meanwhile to avoid a flash. */
    val onboardingDone: StateFlow<Boolean?> = repository.onboardingDone
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val uiState: StateFlow<SettingsUiState> = combine(
        repository.downloadDirectory,
        repository.separateByConsole,
        repository.limitSpeed,
        repository.autoUnzip,
        repository.concurrentDownloads,
        repository.themeMode,
        repository.accentColor,
        repository.favoriteLanguages
    ) { values: Array<Any> ->
        SettingsUiState(
            downloadDirectory = values[0] as String,
            separateByConsole = values[1] as Boolean,
            limitSpeed = values[2] as Float,
            autoUnzip = values[3] as Boolean,
            concurrentDownloads = values[4] as Int,
            themeMode = ThemeMode.fromName(values[5] as String),
            accent = AccentPresets.fromHex(values[6] as String),
            favoriteLanguages = (values[7] as Set<*>).filterIsInstance<String>().toSet()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    fun onDownloadDirChanged(context: Context, path: String) {
        executeWithToast(context, "SettingsViewModel") {
            repository.updateDownloadDirectory(path)
        }
    }

    fun onSeparateByConsoleChanged(context: Context, enabled: Boolean) {
        executeWithToast(context, "SettingsViewModel") {
            repository.setSeparateByConsole(enabled)
        }
    }

    fun onLimitSpeedChanged(context: Context, limit: Float) {
        executeWithToast(context, "SettingsViewModel") {
            repository.setLimitSpeed(limit)
        }
    }

    fun onAutoUnzipChanged(context: Context, enabled: Boolean) {
        executeWithToast(context, "SettingsViewModel") {
            repository.setAutoUnzip(enabled)
        }
    }

    fun onConcurrentDownloadsChanged(context: Context, count: Int) {
        executeWithToast(context, "SettingsViewModel") {
            repository.setConcurrentDownloads(count)
        }
    }

    fun onThemeModeChanged(context: Context, mode: ThemeMode) {
        executeWithToast(context, "SettingsViewModel") {
            repository.setThemeMode(mode.name)
        }
    }

    fun onFavoriteLanguagesChanged(context: Context, tags: Set<String>) {
        executeWithToast(context, "SettingsViewModel") {
            repository.setFavoriteLanguages(tags)
        }
    }

    fun onAccentChanged(context: Context, accent: Color) {
        executeWithToast(context, "SettingsViewModel") {
            repository.setAccentColor(AccentPresets.toHex(accent))
        }
    }
}
