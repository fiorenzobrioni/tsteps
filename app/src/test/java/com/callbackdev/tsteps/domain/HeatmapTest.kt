package com.callbackdev.tsteps.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatmapTest {

    // A Tuesday, so the current week has trailing future cells.
    private val today = LocalDate.parse("2026-08-18")

    @Test
    fun `the grid is 12 Monday-first weeks ending with today's week`() {
        val grid = Heatmap.build(emptyMap(), today)
        assertEquals(12, grid.weeks.size)
        grid.weeks.forEach { week ->
            assertEquals(7, week.cells.size)
            assertEquals(DayOfWeek.MONDAY, week.cells.first().date.dayOfWeek)
        }
        assertEquals(LocalDate.parse("2026-08-17"), grid.weeks.last().cells.first().date)
        assertEquals(LocalDate.parse("2026-06-01"), grid.weeks.first().cells.first().date)
    }

    @Test
    fun `days that haven't happened yet are blank, not zeros`() {
        val grid = Heatmap.build(emptyMap(), today)
        val currentWeek = grid.weeks.last().cells
        assertEquals(0L, currentWeek[0].steps) // Monday: happened, zero
        assertEquals(0L, currentWeek[1].steps) // today
        assertNull(currentWeek[2].steps)       // tomorrow: blank
        assertNull(currentWeek[6].steps)
    }

    @Test
    fun `levels are quartiles of the user's own non-zero days`() {
        val steps = mapOf(
            LocalDate.parse("2026-08-10") to 1_000L,
            LocalDate.parse("2026-08-11") to 2_000L,
            LocalDate.parse("2026-08-12") to 3_000L,
            LocalDate.parse("2026-08-13") to 4_000L,
            LocalDate.parse("2026-08-14") to 0L
        )
        val grid = Heatmap.build(steps, today)
        val week = grid.weeks[grid.weeks.size - 2].cells // week of Aug 10
        assertEquals(1, week[0].level) // 1000: below q1
        assertEquals(2, week[1].level) // 2000
        assertEquals(3, week[2].level) // 3000
        assertEquals(4, week[3].level) // 4000: the user's own max
        assertEquals(0, week[4].level) // zero day
    }

    @Test
    fun `a single active day is the user's max, not a dim dot`() {
        val grid = Heatmap.build(mapOf(LocalDate.parse("2026-08-17") to 5_000L), today)
        assertEquals(Heatmap.LEVELS, grid.weeks.last().cells[0].level)
    }

    @Test
    fun `data outside the window does not distort the buckets`() {
        val steps = mapOf(
            LocalDate.parse("2025-01-01") to 50_000L, // ancient monster day
            LocalDate.parse("2026-08-17") to 3_000L
        )
        val grid = Heatmap.build(steps, today)
        assertEquals(Heatmap.LEVELS, grid.weeks.last().cells[0].level)
    }

    @Test
    fun `month labels mark the columns whose Monday enters a new month`() {
        val grid = Heatmap.build(emptyMap(), today, locale = Locale.ENGLISH)
        assertTrue(grid.weeks.first().monthStart) // the grid's first column
        val starts = grid.weeks.filter { it.monthStart }.map { it.monthLabel }
        // Jun 1 .. Aug 17 mondays: jun, jul, aug
        assertEquals(listOf("jun", "jul", "aug"), starts)
    }
}
