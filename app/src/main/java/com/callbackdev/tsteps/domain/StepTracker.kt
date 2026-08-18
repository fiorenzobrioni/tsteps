package com.callbackdev.tsteps.domain

/**
 * One sample of the hardware step counter.
 *
 * `TYPE_STEP_COUNTER` reports steps **cumulative since boot**: the value is only
 * meaningful relative to the previous sample, and it resets to zero on reboot.
 * [bootCount] (from `Settings.Global.BOOT_COUNT`) is captured with every sample so
 * a reset can be told apart from a sensor glitch.
 */
data class StepReading(
    val cumulativeSteps: Long,
    val bootCount: Int,
    val timestampMillis: Long
)

/**
 * The persisted continuity anchor — the classic pedometer bug lives here. Losing
 * or mishandling this state either doubles a day's steps or silently drops them
 * across a reboot, so every transition is pure and unit-tested.
 */
data class TrackerState(
    val bootCount: Int,
    val lastCumulative: Long,
    val lastTimestampMillis: Long
)

/**
 * Pure step-delta arithmetic between the anchor and a new reading. No clocks, no
 * Android: callers feed readings in, persist [Advance.newState], and attribute
 * [Advance.deltaSteps] over the [Advance.fromMillis]..[Advance.toMillis] interval
 * (see [StepAttribution]).
 */
object StepTracker {

    data class Advance(
        val newState: TrackerState,
        val deltaSteps: Long,
        /** Interval the delta belongs to; from == to when there is no usable span. */
        val fromMillis: Long,
        val toMillis: Long
    )

    fun advance(state: TrackerState?, reading: StepReading): Advance {
        val newState = TrackerState(
            bootCount = reading.bootCount,
            lastCumulative = reading.cumulativeSteps,
            lastTimestampMillis = reading.timestampMillis
        )
        // First reading ever: the cumulative value covers days we know nothing
        // about (steps since boot, before the app existed). Attributing them to
        // "now" would invent a giant fake day, so counting starts at zero here.
        if (state == null) {
            return Advance(newState, 0L, reading.timestampMillis, reading.timestampMillis)
        }
        // Clock moved backwards (manual change, sync): the span is meaningless,
        // collapse it so the delta lands at the reading's own instant.
        val from = minOf(state.lastTimestampMillis, reading.timestampMillis)
        val delta = when {
            // Reboot: the counter restarted from zero, so the cumulative value IS
            // the delta. Steps between the last sample and the shutdown are lost;
            // that loss is accepted and documented (they were never sampled).
            reading.bootCount != state.bootCount -> reading.cumulativeSteps
            // Counter went backwards without a reboot (sensor HAL restart):
            // treat it as a reset for the same reason.
            reading.cumulativeSteps < state.lastCumulative -> reading.cumulativeSteps
            else -> reading.cumulativeSteps - state.lastCumulative
        }
        return Advance(newState, delta, from, reading.timestampMillis)
    }
}
