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
    }
}
