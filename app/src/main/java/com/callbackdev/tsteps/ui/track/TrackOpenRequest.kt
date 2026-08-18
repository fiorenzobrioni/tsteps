package com.callbackdev.tsteps.ui.track

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The tracking notification's deep link: tapping the running process's
 * notification must open the process, not just the app. MainActivity sets the
 * request when the launch intent carries the extra; the shell consumes it and
 * navigates to the track buffer — but only if a session is still running (same
 * hand-rolled-channel reasoning as [com.callbackdev.tsteps.ui.log.LogFocus]).
 */
object TrackOpenRequest {

    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun request() {
        _pending.value = true
    }

    fun consume() {
        _pending.value = false
    }
}
