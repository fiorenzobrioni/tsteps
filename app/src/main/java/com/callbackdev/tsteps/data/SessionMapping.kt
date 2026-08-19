package com.callbackdev.tsteps.data

import com.callbackdev.tsteps.data.local.SessionEntity
import com.callbackdev.tsteps.domain.SessionItem

/** Only completed rows become items; a running session has no end and no hunk. */
fun SessionEntity.toItem(): SessionItem? {
    val end = endMillis ?: return null
    return SessionItem(
        id = id,
        startMillis = startMillis,
        endMillis = end,
        type = type,
        steps = steps,
        distanceMeters = distanceMeters ?: 0.0,
        activeMillis = activeMillis,
        avgCadenceSpm = avgCadenceSpm,
        auto = auto,
        startApprox = auto && startMillis == detectedStartMillis,
        endApprox = auto && end == detectedEndMillis
    )
}
