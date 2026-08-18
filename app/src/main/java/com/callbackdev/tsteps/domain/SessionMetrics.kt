package com.callbackdev.tsteps.domain

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Session-level derived numbers. Null over invented: a session too short to
 * divide by simply has no speed/pace/cadence line (the file must not lie).
 * Speed and pace are the same fact in two shapes; the settings' `session_metric`
 * picks which one renders — never both (VISION §5).
 */
object SessionMetrics {

    private const val MIN_ACTIVE_MILLIS = 30_000L
    private const val MIN_DISTANCE_METERS = 50.0

    fun avgSpeedKmh(distanceMeters: Double, activeMillis: Long): Double? {
        if (activeMillis < MIN_ACTIVE_MILLIS || distanceMeters < MIN_DISTANCE_METERS) return null
        return (distanceMeters / 1_000.0) / (activeMillis / 3_600_000.0)
    }

    /** `13:38` — minutes:seconds per kilometer (or per mile for imperial callers). */
    fun pacePerUnit(distanceMeters: Double, activeMillis: Long, unitMeters: Double = 1_000.0): String? {
        if (activeMillis < MIN_ACTIVE_MILLIS || distanceMeters < MIN_DISTANCE_METERS) return null
        val millisPerUnit = activeMillis / (distanceMeters / unitMeters)
        val totalSeconds = (millisPerUnit / 1_000.0).roundToInt()
        return "%d:%02d".format(Locale.ROOT, totalSeconds / 60, totalSeconds % 60)
    }

    fun avgCadenceSpm(steps: Long, activeMillis: Long): Int? {
        if (activeMillis < MIN_ACTIVE_MILLIS) return null
        return (steps / (activeMillis / 60_000.0)).roundToInt()
    }
}
