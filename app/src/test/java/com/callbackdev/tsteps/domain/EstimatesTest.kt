package com.callbackdev.tsteps.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EstimatesTest {

    @Test
    fun `stride comes from height when set, labeled average otherwise`() {
        assertEquals(0.72625, Estimates.strideMeters(175), 1e-9)
        assertEquals(Estimates.DEFAULT_STRIDE_METERS, Estimates.strideMeters(null), 1e-9)
    }

    @Test
    fun `distance is steps times stride`() {
        assertEquals(7262.5, Estimates.distanceMeters(10_000, 175), 1e-6)
        assertEquals(7200.0, Estimates.distanceMeters(10_000, null), 1e-6)
    }

    @Test
    fun `active minutes derive from hourly steps at walking cadence`() {
        // 3000 steps in an hour ≈ 30 min at 100 steps/min; stray steps round to 0.
        assertEquals(30, Estimates.activeMinutesInHour(3_000))
        assertEquals(0, Estimates.activeMinutesInHour(20))
        assertEquals(31, Estimates.activeMinutes(listOf(3_000L, 20L, 80L)))
    }

    @Test
    fun `active minutes in one hour cap at 60`() {
        assertEquals(60, Estimates.activeMinutesInHour(9_000))
    }

    @Test
    fun `kcal needs a weight - hidden, not invented`() {
        assertNull(Estimates.activeKcal(weightKg = null, activeMinutes = 60))
        assertEquals(3.3 * 78.0, Estimates.activeKcal(78.0, 60)!!, 1e-6)
        assertEquals(3.3 * 78.0 / 2, Estimates.activeKcal(78.0, 30)!!, 1e-6)
    }
}
