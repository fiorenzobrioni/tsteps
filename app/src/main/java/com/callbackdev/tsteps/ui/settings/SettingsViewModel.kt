package com.callbackdev.tsteps.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.callbackdev.tsteps.data.AppSettings
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.data.SessionMetric
import com.callbackdev.tsteps.data.SettingsStore
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.data.WidgetOpacities
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsStore: SettingsStore
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setLineNumbers(enabled: Boolean) = save { setLineNumbers(enabled) }
    fun setWordWrap(enabled: Boolean) = save { setWordWrap(enabled) }
    fun setNotifDailyCommit(enabled: Boolean) = save { setNotifDailyCommit(enabled) }
    fun setNotifGoalCheck(enabled: Boolean) = save { setNotifGoalCheck(enabled) }
    fun setHealthConnectSync(enabled: Boolean) = save { setHealthConnectSync(enabled) }
    fun setDailyGoalSteps(steps: Int) = save { setDailyGoalSteps(steps) }
    fun setWeightKg(weightKg: Double?) = save { setWeightKg(weightKg) }
    fun setHeightCm(heightCm: Int?) = save { setHeightCm(heightCm) }
    fun setThemeProfile(name: String) = save { setThemeProfileName(name) }

    /** `sessions.auto_detect` — Fase 11's opt-in switch, default off. */
    fun setAutoDetectSessions(enabled: Boolean) = save { setAutoDetectSessions(enabled) }

    fun toggleUnits() = save {
        setUnits(
            if (this@SettingsViewModel.settings.value.units == UnitsSystem.METRIC) {
                UnitsSystem.IMPERIAL
            } else {
                UnitsSystem.METRIC
            }
        )
    }

    fun toggleSessionMetric() = save {
        setSessionMetric(
            if (this@SettingsViewModel.settings.value.sessionMetric == SessionMetric.SPEED) {
                SessionMetric.PACE
            } else {
                SessionMetric.SPEED
            }
        )
    }

    /** Cycles 100 → 85 → 70 → 50 → 100 percent (tweather's ladder). */
    fun cycleWidgetOpacity() = save {
        val current = this@SettingsViewModel.settings.value.widgetOpacityPct
        val index = WidgetOpacities.indexOf(current)
        setWidgetOpacity(WidgetOpacities[(index + 1) % WidgetOpacities.size])
    }

    /** `$ git restore settings.config`. */
    fun resetToDefaults() = save { resetToDefaults() }

    private fun save(block: suspend SettingsStore.() -> Unit) {
        viewModelScope.launch { settingsStore.block() }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[AndroidViewModelFactory.APPLICATION_KEY])
                SettingsViewModel(settingsStore = ServiceLocator.settingsStore(app))
            }
        }
    }
}
