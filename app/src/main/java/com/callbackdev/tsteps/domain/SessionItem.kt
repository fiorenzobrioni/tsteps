package com.callbackdev.tsteps.domain

/**
 * A completed session as the screens consume it — the JSON array entry, the log
 * hunk, the detail expansion. Presentation-agnostic and pure; the data layer maps
 * its Room entity into this.
 */
data class SessionItem(
    val id: Long,
    val startMillis: Long,
    val endMillis: Long,
    val type: String,
    val steps: Long,
    val distanceMeters: Double,
    val activeMillis: Long,
    val avgCadenceSpm: Int?,
    /** Inferred by the detector (Fase 11) rather than tracked by hand. */
    val auto: Boolean = false,
    /**
     * True while the boundary is still the detector's guess — that is what
     * renders the `~`. A user edit makes the time a stated fact and drops it.
     */
    val startApprox: Boolean = false,
    val endApprox: Boolean = false
) {
    val activeMinutes: Int get() = (activeMillis / 60_000L).toInt()
}
