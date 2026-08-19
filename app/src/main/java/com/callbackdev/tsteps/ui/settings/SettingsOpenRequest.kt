package com.callbackdev.tsteps.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Health Connect's rationale/privacy intents must land on an explanation:
 * tsteps has no website (no network), so the explanation IS the
 * `health_connect` section of settings.config. MainActivity sets the request
 * when such an intent arrives; the shell consumes it and switches to the
 * settings tab (same hand-rolled channel as
 * [com.callbackdev.tsteps.ui.track.TrackOpenRequest]).
 */
object SettingsOpenRequest {

    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun request() {
        _pending.value = true
    }

    fun consume() {
        _pending.value = false
    }
}
