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
import com.callbackdev.tsteps.export.DataExporter
import com.callbackdev.tsteps.export.ExportFormat
import com.callbackdev.tsteps.export.ExportResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the `$ tsteps export` line prints right now (Fase 13). */
sealed interface ExportState {

    /** No command run yet in this visit: only the commands are on screen. */
    data object Idle : ExportState

    data object Running : ExportState

    /** The outcome, printed as terminal output under the command. */
    data class Done(val result: ExportResult) : ExportState
}

class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val exporter: DataExporter
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    /** `$ tsteps export --json|--csv`. Taps during a run are ignored, not queued. */
    fun export(format: ExportFormat) {
        if (_exportState.value == ExportState.Running) return
        _exportState.value = ExportState.Running
        viewModelScope.launch {
            _exportState.value = ExportState.Done(exporter.export(format))
        }
    }

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
                SettingsViewModel(
                    settingsStore = ServiceLocator.settingsStore(app),
                    exporter = ServiceLocator.dataExporter(app)
                )
            }
        }
    }
}
