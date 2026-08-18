package com.callbackdev.tsteps.ui.track

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.callbackdev.tsteps.data.AppSettings
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.data.SettingsStore
import com.callbackdev.tsteps.data.TrackingManager
import com.callbackdev.tsteps.data.TrackingState
import java.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * Thin state adapter for the track screen: the session lives in [TrackingManager]
 * (written by the service); this only re-exposes it plus a 1s clock for the
 * elapsed display. Actions go to the service via its static intents — the screen
 * dispatches them, because starting/stopping a service needs a Context.
 */
class TrackViewModel(
    trackingManager: TrackingManager,
    settingsStore: SettingsStore,
    private val clock: Clock = Clock.systemDefaultZone()
) : ViewModel() {

    val state: StateFlow<TrackingState?> = trackingManager.state

    val settings: StateFlow<AppSettings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** Ticks every second while the screen is on the glass — the elapsed clock. */
    val nowMillis: StateFlow<Long> = flow {
        while (true) {
            emit(clock.millis())
            delay(1_000L)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), clock.millis())

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[AndroidViewModelFactory.APPLICATION_KEY])
                TrackViewModel(
                    trackingManager = ServiceLocator.trackingManager(app),
                    settingsStore = ServiceLocator.settingsStore(app)
                )
            }
        }
    }
}
