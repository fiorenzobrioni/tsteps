package com.callbackdev.tsteps.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSessionTrackerTest {

    @Test
    fun `first reading anchors, later readings add their deltas`() {
        var s = LiveSessionTracker.start("walk", nowMillis = 1_000L)
        s = LiveSessionTracker.onReading(s, 50_000L)
        assertEquals(0L, s.steps)
        s = LiveSessionTracker.onReading(s, 50_250L)
        s = LiveSessionTracker.onReading(s, 50_600L)
        assertEquals(600L, s.steps)
    }

    @Test
    fun `steps during a pause belong to the day, not the session`() {
        var s = LiveSessionTracker.start("walk", 0L)
        s = LiveSessionTracker.onReading(s, 1_000L)
        s = LiveSessionTracker.onReading(s, 1_100L) // +100
        s = LiveSessionTracker.pause(s, 60_000L)
        s = LiveSessionTracker.onReading(s, 1_400L) // +300 while paused: discarded
        s = LiveSessionTracker.resume(s, 120_000L)
        s = LiveSessionTracker.onReading(s, 1_500L) // +100
        assertEquals(200L, s.steps)
    }

    @Test
    fun `active duration excludes pauses, including a still-running one`() {
        var s = LiveSessionTracker.start("walk", 0L)
        s = LiveSessionTracker.pause(s, 60_000L)
        s = LiveSessionTracker.resume(s, 120_000L)
        assertEquals(120_000L, s.activeMillis(180_000L)) // 3 min wall - 1 min paused
        s = LiveSessionTracker.pause(s, 180_000L)
        assertEquals(120_000L, s.activeMillis(240_000L)) // frozen while paused
    }

    @Test
    fun `a reboot mid-session keeps counting from the fresh counter`() {
        var s = LiveSessionTracker.start("walk", 0L)
        s = LiveSessionTracker.onReading(s, 90_000L)
        s = LiveSessionTracker.onReading(s, 90_400L) // +400
        s = LiveSessionTracker.onReading(s, 50L)     // reboot: 50 steps since boot
        assertEquals(450L, s.steps)
    }

    @Test
    fun `pause and resume are idempotent`() {
        var s = LiveSessionTracker.start("walk", 0L)
        s = LiveSessionTracker.resume(s, 10L) // not paused: no-op
        assertFalse(s.paused)
        s = LiveSessionTracker.pause(s, 20L)
        s = LiveSessionTracker.pause(s, 30L) // already paused: keeps first mark
        assertEquals(20L, s.pausedSinceMillis)
        assertTrue(s.paused)
    }

    @Test
    fun `type cycles between walk and other`() {
        var s = LiveSessionTracker.start("walk", 0L)
        s = LiveSessionTracker.cycleType(s)
        assertEquals("other", s.type)
        s = LiveSessionTracker.cycleType(s)
        assertEquals("walk", s.type)
    }
}
