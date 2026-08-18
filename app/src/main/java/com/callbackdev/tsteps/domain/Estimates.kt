package com.callbackdev.tsteps.domain

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Every derived number in tsteps is an estimate, and this is the one place that
 * says how. The UI's honesty rules hang off these functions: distance carries an
 * `// estimated from stride length` comment, and kcal is **hidden, not invented**
 * when the profile lacks a weight (a null here is a missing line in the JSON).
 */
object Estimates {

    /** Anthropometric rule of thumb: walking stride ≈ 41.5% of height. */
    const val STRIDE_HEIGHT_FACTOR = 0.415

    /** Fallback stride when no height is set — a labeled average, not a secret. */
    const val DEFAULT_STRIDE_METERS = 0.72

    /** MET for ordinary walking (Compendium of Physical Activities, ~4.8 km/h). */
    const val WALKING_MET = 3.3

    /**
     * Average walking cadence used to turn sampled step deltas into active
     * minutes. Passive counting reads the counter in batches, so per-minute
     * cadence windows are not observable without a permanent foreground service —
     * which the battery philosophy forbids. Minutes therefore come from steps:
     * an hour with 3,000 steps was ~30 active minutes at 100 steps/min. Tracked
     * sessions (Fase 6) measure their minutes for real.
     */
    const val WALKING_CADENCE_SPM = 100.0

    fun strideMeters(heightCm: Int?): Double =
        heightCm?.let { it * STRIDE_HEIGHT_FACTOR / 100.0 } ?: DEFAULT_STRIDE_METERS

    fun distanceMeters(steps: Long, heightCm: Int?): Double =
        steps * strideMeters(heightCm)

    /** Active minutes for one hour bucket, capped at the hour's own 60. */
    fun activeMinutesInHour(hourSteps: Long): Int =
        min(60, (hourSteps / WALKING_CADENCE_SPM).roundToInt())

    /** Active minutes for a day, from its hourly buckets. */
    fun activeMinutes(hourlySteps: Collection<Long>): Int =
        hourlySteps.sumOf { activeMinutesInHour(it) }

    /** Null without a weight: the metric disappears instead of guessing a body. */
    fun activeKcal(weightKg: Double?, activeMinutes: Int): Double? =
        weightKg?.let { WALKING_MET * it * (activeMinutes / 60.0) }
}
