package com.callbackdev.tsteps.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionMetricsTest {

    @Test
    fun `average speed is distance over active time`() {
        // 3.4 km in 46 active minutes = 4.43 km/h
        assertEquals(4.43, SessionMetrics.avgSpeedKmh(3_400.0, 46 * 60_000L)!!, 0.01)
    }

    @Test
    fun `pace formats as minutes and seconds per unit`() {
        assertEquals("13:32", SessionMetrics.pacePerUnit(3_400.0, 46 * 60_000L))
        // Per mile: 46 min over 3400 m = 21:46 min/mi
        assertEquals("21:46", SessionMetrics.pacePerUnit(3_400.0, 46 * 60_000L, 1_609.344))
    }

    @Test
    fun `cadence is steps per active minute`() {
        assertEquals(105, SessionMetrics.avgCadenceSpm(4_820, 46 * 60_000L))
    }

    @Test
    fun `sessions too short to divide by have no metrics - null over invented`() {
        assertNull(SessionMetrics.avgSpeedKmh(100.0, 10_000L))   // 10 s
        assertNull(SessionMetrics.avgSpeedKmh(20.0, 600_000L))   // 20 m
        assertNull(SessionMetrics.pacePerUnit(20.0, 600_000L))
        assertNull(SessionMetrics.avgCadenceSpm(50, 10_000L))
    }
}
