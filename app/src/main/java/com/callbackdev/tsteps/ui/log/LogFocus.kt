package com.callbackdev.tsteps.ui.log

import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cross-tab jump into the log: `stats.md`'s tag rows link to their commit. The
 * requester sets a date, navigates to the Log tab; the log expands that day and
 * scrolls its commit into view, then consumes the request. A tiny hand-rolled
 * channel instead of nav arguments — the Log tab's back-stack entry is restored,
 * not recreated, so an argument would not reach it (same reasoning as tweather's
 * status-bar ⎇ jump).
 */
object LogFocus {

    private val _request = MutableStateFlow<LocalDate?>(null)
    val request: StateFlow<LocalDate?> = _request.asStateFlow()

    fun request(date: LocalDate) {
        _request.value = date
    }

    fun consume() {
        _request.value = null
    }
}
