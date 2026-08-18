package com.callbackdev.tsteps.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class GoalStreaksTest {

    @Test
    fun `goal check is a factual pass, fail, or skip`() {
        assertEquals(GoalCheckResult.PASSED, GoalCheck.run(10_000, 8_000))
        assertEquals(GoalCheckResult.PASSED, GoalCheck.run(8_000, 8_000))
        assertEquals(GoalCheckResult.FAILED, GoalCheck.run(7_999, 8_000))
        assertEquals(GoalCheckResult.SKIPPED, GoalCheck.run(7_999, 0))
    }

    private fun day(date: String, result: GoalCheckResult) =
        LocalDate.parse(date) to result

    @Test
    fun `current streak counts consecutive passed days ending yesterday`() {
        val today = LocalDate.parse("2026-08-18")
        val days = listOf(
            day("2026-08-17", GoalCheckResult.PASSED),
            day("2026-08-16", GoalCheckResult.PASSED),
            day("2026-08-15", GoalCheckResult.PASSED),
            day("2026-08-14", GoalCheckResult.FAILED),
            day("2026-08-13", GoalCheckResult.PASSED)
        )
        assertEquals(3, Streaks.current(days, today))
    }

    @Test
    fun `a missing date breaks the current streak`() {
        val today = LocalDate.parse("2026-08-18")
        val days = listOf(
            day("2026-08-17", GoalCheckResult.PASSED),
            // 16th absent (no data that day)
            day("2026-08-15", GoalCheckResult.PASSED)
        )
        assertEquals(1, Streaks.current(days, today))
    }

    @Test
    fun `a skipped check breaks the streak like a missing day`() {
        val today = LocalDate.parse("2026-08-18")
        val days = listOf(
            day("2026-08-17", GoalCheckResult.PASSED),
            day("2026-08-16", GoalCheckResult.SKIPPED),
            day("2026-08-15", GoalCheckResult.PASSED)
        )
        assertEquals(1, Streaks.current(days, today))
    }

    @Test
    fun `no passed yesterday means streak zero`() {
        val today = LocalDate.parse("2026-08-18")
        assertEquals(0, Streaks.current(listOf(day("2026-08-15", GoalCheckResult.PASSED)), today))
        assertEquals(0, Streaks.current(emptyList(), today))
    }

    @Test
    fun `longest streak scans the whole history`() {
        val days = listOf(
            day("2026-08-01", GoalCheckResult.PASSED),
            day("2026-08-02", GoalCheckResult.PASSED),
            day("2026-08-03", GoalCheckResult.PASSED),
            day("2026-08-04", GoalCheckResult.PASSED),
            day("2026-08-06", GoalCheckResult.PASSED),
            day("2026-08-07", GoalCheckResult.PASSED)
        )
        assertEquals(4, Streaks.longest(days))
        assertEquals(0, Streaks.longest(emptyList()))
    }
}
