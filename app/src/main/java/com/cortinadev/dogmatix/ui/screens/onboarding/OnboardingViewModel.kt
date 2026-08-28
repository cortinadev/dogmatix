package com.cortinadev.dogmatix.ui.screens.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository
) : ViewModel() {

    val downloadDirectory: StateFlow<String> = settings.downloadDirectory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), "")

    fun updateDownloadDirectory(uri: String) {
        viewModelScope.launch { settings.updateDownloadDirectory(uri) }
    }

    /** Marks the tour as done; the shell replaces it on the next recomposition. */
    fun finish() {
        viewModelScope.launch { settings.setOnboardingDone(true) }
    }
}
