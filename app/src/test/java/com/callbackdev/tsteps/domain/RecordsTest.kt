package com.callbackdev.tsteps.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordsTest {

    private fun day(date: String, steps: Long) = LocalDate.parse(date) to steps

    @Test
    fun `best day is the committed day with the most steps`() {
        val best = Records.bestDay(
            listOf(day("2026-08-15", 9_000), day("2026-08-16", 14_823), day("2026-08-17", 11_204))
        )
        assertEquals(LocalDate.parse("2026-08-16"), best)
    }

    @Test
    fun `ties go to the most recent day`() {
        val best = Records.bestDay(listOf(day("2026-08-15", 9_000), day("2026-08-17", 9_000)))
        assertEquals(LocalDate.parse("2026-08-17"), best)
    }

    @Test
    fun `no committed days, no record`() {
        assertNull(Records.bestDay(emptyList()))
        assertNull(Records.longestWalk(emptyList()))
        assertNull(Records.bestWeek(emptyList()))
    }

    private fun walk(id: Long, activeMin: Int, start: Long = id * 1_000L) = SessionItem(
        id = id,
        startMillis = start,
        endMillis = start + activeMin * 60_000L,
        type = "walk",
        steps = activeMin * 100L,
        distanceMeters = activeMin * 72.0,
        activeMillis = activeMin * 60_000L,
        avgCadenceSpm = 100
    )

    @Test
    fun `longest walk is the session with the most active minutes`() {
        val longest = Records.longestWalk(listOf(walk(1, 46), walk(2, 92), walk(3, 30)))
        assertEquals(2L, longest?.id)
    }

    @Test
    fun `best week sums the committed days per ISO week`() {
        // Aug 17 2026 is a Monday (week 34); Aug 16 a Sunday (week 33).
        val best = Records.bestWeek(
            listOf(
                day("2026-08-17", 11_000),
                day("2026-08-16", 9_000),
                day("2026-08-14", 4_000)
            )
        )
        assertEquals(33, best?.week)
        assertEquals(13_000L, best?.steps)
    }
}
