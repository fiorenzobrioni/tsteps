package com.callbackdev.tsteps.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class StepTrackerTest {

    private fun reading(cumulative: Long, boot: Int = 7, ts: Long = 1_000_000L) =
        StepReading(cumulativeSteps = cumulative, bootCount = boot, timestampMillis = ts)

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

    @Test
    fun `clock moving backwards collapses the interval to the reading instant`() {
        val state = TrackerState(bootCount = 7, lastCumulative = 1_000L, lastTimestampMillis = 900L)
        val advance = StepTracker.advance(state, reading(1_100L, ts = 500L))
        assertEquals(100L, advance.deltaSteps)
        assertEquals(500L, advance.fromMillis)
        assertEquals(500L, advance.toMillis)
    }
}
