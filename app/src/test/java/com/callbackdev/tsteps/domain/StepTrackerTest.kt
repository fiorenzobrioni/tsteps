package com.callbackdev.tsteps.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class StepTrackerTest {

    private fun reading(
        cumulative: Long,
        boot: Int = 7,
        ts: Long = 1_000_000L,
        bootAt: Long = 0L
    ) = StepReading(
        cumulativeSteps = cumulative,
        bootCount = boot,
        timestampMillis = ts,
        bootMillis = bootAt
    )

    @Test
    fun `first reading ever anchors without producing steps`() {
        val advance = StepTracker.advance(null, reading(123_456L, ts = 42L))
        assertEquals(0L, advance.deltaSteps)
        assertEquals(42L, advance.fromMillis)
        assertEquals(42L, advance.toMillis)
        assertEquals(TrackerState(7, 123_456L, 42L), advance.newState)
    }

    @Test
    fun `same boot increments produce the difference over the sample interval`() {
        val state = TrackerState(bootCount = 7, lastCumulative = 1_000L, lastTimestampMillis = 100L)
        val advance = StepTracker.advance(state, reading(1_250L, ts = 400L))
        assertEquals(250L, advance.deltaSteps)
        assertEquals(100L, advance.fromMillis)
        assertEquals(400L, advance.toMillis)
    }

    @Test
    fun `reboot resets the counter so the cumulative value is the delta`() {
        val state = TrackerState(bootCount = 7, lastCumulative = 50_000L, lastTimestampMillis = 100L)
        val advance = StepTracker.advance(state, reading(300L, boot = 8, ts = 400L))
        assertEquals(300L, advance.deltaSteps)
        assertEquals(TrackerState(8, 300L, 400L), advance.newState)
    }

    @Test
    fun `double reboot in a row keeps counting from each fresh counter`() {
        var state: TrackerState? = TrackerState(7, 10_000L, 100L)
        val first = StepTracker.advance(state, reading(200L, boot = 8, ts = 200L))
        state = first.newState
        val second = StepTracker.advance(state, reading(80L, boot = 9, ts = 300L))
        assertEquals(200L, first.deltaSteps)
        assertEquals(80L, second.deltaSteps)
        assertEquals(TrackerState(9, 80L, 300L), second.newState)
    }

    @Test
    fun `counter decreasing without a reboot is treated as a reset, never negative`() {
        val state = TrackerState(bootCount = 7, lastCumulative = 5_000L, lastTimestampMillis = 100L)
        val advance = StepTracker.advance(state, reading(120L, boot = 7, ts = 400L))
        assertEquals(120L, advance.deltaSteps)
    }

    /**
     * `STEP_COUNTER` is on-change: a still device hands back the event from the
     * last step taken, hours old, with its original timestamp. That age belongs to
     * the steps (so the delta lands in the hour it was walked) and NOT to the
     * reading — the widget's freshness is measured against the second number, and
     * the two used to be the same field.
     */
    @Test
    fun `the read instant is anchored separately from the step instant`() {
        val state = TrackerState(
            bootCount = 7,
            lastCumulative = 1_000L,
            lastTimestampMillis = 100L,
            lastReadMillis = 5_000L
        )
        val advance = StepTracker.advance(
            state,
            StepReading(
                cumulativeSteps = 1_000L,
                bootCount = 7,
                // Not a step since; the sensor re-delivers the old event...
                timestampMillis = 100L,
                // ...but it was read just now, and that is what freshness means.
                readAtMillis = 9_000L
            )
        )
        assertEquals(0L, advance.deltaSteps)
        assertEquals(100L, advance.newState.lastTimestampMillis)
        assertEquals(9_000L, advance.newState.lastReadMillis)
    }

    /** One number for a synthetic reading; the default must not invent an age. */
    @Test
    fun `a reading without a read instant reads as taken when it was counted`() {
        val advance = StepTracker.advance(null, reading(500L, ts = 7_000L))
        assertEquals(7_000L, advance.newState.lastReadMillis)
    }

    @Test
    fun `clock moving backwards collapses the interval to the reading instant`() {
        val state = TrackerState(bootCount = 7, lastCumulative = 1_000L, lastTimestampMillis = 900L)
        val advance = StepTracker.advance(state, reading(1_100L, ts = 500L))
        assertEquals(100L, advance.deltaSteps)
        assertEquals(500L, advance.fromMillis)
        assertEquals(500L, advance.toMillis)
    }

    /**
     * The bug this clamp closes: the delta of a restarted counter was spread from
     * the *pre-reboot* anchor, so a phone switched off every night credited the
     * days it was off with steps walked after the last boot. The counter cannot
     * have counted before it was zeroed, and now the span says so.
     */
    @Test
    fun `a restarted counter cannot have counted before the boot that zeroed it`() {
        val state = TrackerState(bootCount = 7, lastCumulative = 50_000L, lastTimestampMillis = 100L)
        val advance = StepTracker.advance(state, reading(300L, boot = 8, ts = 400L, bootAt = 250L))
        assertEquals(300L, advance.deltaSteps)
        assertEquals(250L, advance.fromMillis)
        assertEquals(400L, advance.toMillis)
    }

    /** Same floor for a HAL restart: it happened inside this boot, so boot holds. */
    @Test
    fun `a counter that went backwards is clamped to the boot instant too`() {
        val state = TrackerState(bootCount = 7, lastCumulative = 5_000L, lastTimestampMillis = 100L)
        val advance = StepTracker.advance(state, reading(120L, boot = 7, ts = 400L, bootAt = 250L))
        assertEquals(120L, advance.deltaSteps)
        assertEquals(250L, advance.fromMillis)
    }

    /** A synthetic reading knows no boot instant; the clamp must then do nothing. */
    @Test
    fun `an unknown boot instant leaves the span where it was`() {
        val state = TrackerState(bootCount = 7, lastCumulative = 50_000L, lastTimestampMillis = 100L)
        val advance = StepTracker.advance(state, reading(300L, boot = 8, ts = 400L))
        assertEquals(100L, advance.fromMillis)
    }

    /** Clock skew must never produce a span that ends before it starts. */
    @Test
    fun `a boot instant after the reading collapses the span`() {
        val state = TrackerState(bootCount = 7, lastCumulative = 50_000L, lastTimestampMillis = 100L)
        val advance = StepTracker.advance(state, reading(300L, boot = 8, ts = 400L, bootAt = 900L))
        assertEquals(400L, advance.fromMillis)
        assertEquals(400L, advance.toMillis)
    }

    /** The ordinary case is untouched: no restart, no clamp, whatever boot says. */
    @Test
    fun `an increment within one boot keeps the sample interval`() {
        val state = TrackerState(bootCount = 7, lastCumulative = 1_000L, lastTimestampMillis = 100L)
        val advance = StepTracker.advance(state, reading(1_250L, ts = 400L, bootAt = 250L))
        assertEquals(250L, advance.deltaSteps)
        assertEquals(100L, advance.fromMillis)
    }
}
