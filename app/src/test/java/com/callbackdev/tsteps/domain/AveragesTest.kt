package com.callbackdev.tsteps.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AveragesTest {

    private val today = LocalDate.parse("2026-08-18")

    private fun day(date: String, steps: Long, meters: Double = 700.0 * steps / 1000, min: Int = 60) =
        DayStats(LocalDate.parse(date), steps, meters, min)

    @Test
    fun `averages mean over the days that have a commit, not the calendar`() {
        val avg = Averages.over(
            listOf(
                day("2026-08-17", 10_000, meters = 7_000.0, min = 90),
                day("2026-08-15", 6_000, meters = 4_200.0, min = 50)
                // 16th untracked: missing data, not a zero
            ),
            today,
            windowDays = 7
        )!!
        assertEquals(2, avg.daysWithData)
        assertEquals(8_000L, avg.avgSteps)
        assertEquals(5_600.0, avg.avgDistanceMeters, 1e-6)
        assertEquals(70, avg.avgActiveMinutes)
    }

    @Test
    fun `the window boundary is exclusive at the far end`() {
        val avg = Averages.over(
            listOf(
                day("2026-08-11", 4_000), // exactly 7 days back: out
                day("2026-08-12", 8_000)
            ),
            today,
            windowDays = 7
        )!!
        assertEquals(1, avg.daysWithData)
        assertEquals(8_000L, avg.avgSteps)
    }

    @Test
    fun `an empty window is null, not a zero row`() {
        assertNull(Averages.over(emptyList(), today, 7))
        assertNull(Averages.over(listOf(day("2026-07-01", 9_000)), today, 7))
    }
}
