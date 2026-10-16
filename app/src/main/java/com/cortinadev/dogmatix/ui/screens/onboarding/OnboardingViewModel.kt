package com.cortinadev.dogmatix.ui.screens.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.local.defaultFavoriteLanguages
import com.cortinadev.dogmatix.data.model.PreferredRegion
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.data.service.EsdeConfigService
import com.cortinadev.dogmatix.data.service.FrontendSetupException
import com.cortinadev.dogmatix.util.ToastUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val esdeConfig: EsdeConfigService
) : ViewModel() {

    val downloadDirectory: StateFlow<String> = settings.downloadDirectory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")

    /** Stable for the whole tour: decides whether the ES-DE step exists at all. */
    val esdeInstalled: Boolean = esdeConfig.isEsdeInstalled()

    private val _esdeBusy = MutableStateFlow(false)
    val esdeBusy: StateFlow<Boolean> = _esdeBusy.asStateFlow()

    /** Which version of a game the library picks; editable later in Settings. */
    val preferredRegion: StateFlow<PreferredRegion> = settings.preferredRegion
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), PreferredRegion.forLocale())
    val favoriteLanguages: StateFlow<Set<String>> = settings.favoriteLanguages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), defaultFavoriteLanguages())

    fun setPreferredRegion(region: PreferredRegion) {
        viewModelScope.launch { settings.setPreferredRegion(region) }
    }

    fun toggleLanguage(tag: String) {
        val current = favoriteLanguages.value
        viewModelScope.launch { settings.setFavoriteLanguages(if (tag in current) current - tag else current + tag) }
    }

    fun updateDownloadDirectory(uri: String) {
        viewModelScope.launch { settings.updateDownloadDirectory(uri) }
    }

    /** Saves the picked ES-DE folder, runs the one-button setup and ends the tour. */
    fun configureEsde(context: Context, uri: String) {
        if (_esdeBusy.value) return
        viewModelScope.launch {
            _esdeBusy.value = true
            runCatching {
                settings.setEsdeDirectory(uri)
                esdeConfig.configure(uri)
            }
                .onSuccess { result ->
                    ToastUtil.showSuccess(
                        context,
                        context.getString(R.string.esde_configured, result.systems, result.shortcutsWritten)
                    )
                }
                .onFailure { error ->
                    val message = when (error) {
                        is EsdeConfigService.EsdeNotInstalledException -> context.getString(R.string.esde_not_installed)
                        is FrontendSetupException -> error.userMessage(context)
                        else -> error.message ?: context.getString(R.string.msg_operation_failed)
                    }
                    ToastUtil.showError(context, message)
                }
            _esdeBusy.value = false
            finish()
        }
    }

    /** Marks the tour as done; the shell replaces it on the next recomposition. */
    fun finish() {
        viewModelScope.launch { settings.setOnboardingDone(true) }
    }
}
