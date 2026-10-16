package com.cortinadev.dogmatix.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cortinadev.dogmatix.data.repository.SettingsRepository
import com.cortinadev.dogmatix.util.AutoScanFrequency
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Settings → Automatic scan; the background job follows these values (see AutoScanScheduler). */
@HiltViewModel
class AutoScanSettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    data class State(
        val frequency: AutoScanFrequency = AutoScanFrequency.OFF,
        val wifiOnly: Boolean = true,
        val chargingOnly: Boolean = false
    )

    val state: StateFlow<State> = combine(
        settingsRepository.autoScanFrequency,
        settingsRepository.autoScanWifiOnly,
        settingsRepository.autoScanChargingOnly
    ) { frequency, wifi, charging -> State(frequency, wifi, charging) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    /** Steps through Off → Daily → Weekly (wrapping), as the other steppers of Settings. */
    fun cycleFrequency(delta: Int) {
        val all = AutoScanFrequency.entries
        val next = all[((state.value.frequency.ordinal + delta) % all.size + all.size) % all.size]
        viewModelScope.launch { settingsRepository.setAutoScanFrequency(next) }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoScanWifiOnly(enabled) }
    }

    fun setChargingOnly(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoScanChargingOnly(enabled) }
    }
}
