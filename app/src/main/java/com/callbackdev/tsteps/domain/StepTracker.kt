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
    /**
     * When the steps in [cumulativeSteps] were *counted*. `STEP_COUNTER` is an
     * on-change sensor: registering re-delivers the last event with its ORIGINAL
     * timestamp, so on a still device this is however long ago the user last
     * moved. That is exactly right for [StepAttribution] — the delta belongs in
     * the hour it was walked — and exactly wrong as a measure of freshness.
     */
    val timestampMillis: Long,
    /**
     * When the counter was *read*, wall clock at sampling. Defaults to
     * [timestampMillis] so a synthetic reading needs one number, but on real
     * hardware the two differ by however long the user has been sitting still.
     * This is the one the widget's `# last_sync` and `# stale` are made of.
     */
    val readAtMillis: Long = timestampMillis
)

/**
 * The persisted continuity anchor — the classic pedometer bug lives here. Losing
 * or mishandling this state either doubles a day's steps or silently drops them
 * across a reboot, so every transition is pure and unit-tested.
 */
data class TrackerState(
    val bootCount: Int,
    val lastCumulative: Long,
    /** The instant the anchored steps were walked (see [StepReading.timestampMillis]). */
    val lastTimestampMillis: Long,
    /**
     * The instant the counter was last read (see [StepReading.readAtMillis]).
     * Defaults to [lastTimestampMillis]: that is what an anchor written before
     * this field existed can honestly claim, and one sample replaces it.
     */
    val lastReadMillis: Long = lastTimestampMillis
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
            lastTimestampMillis = reading.timestampMillis,
            lastReadMillis = reading.readAtMillis
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
