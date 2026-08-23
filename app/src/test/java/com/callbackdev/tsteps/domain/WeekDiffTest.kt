package com.callbackdev.tsteps.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which two weeks get compared, and what each side is allowed to claim. */
class WeekDiffTest {

    // 2026-08-18 is a Tuesday: ISO week 34 runs mon 17 .. sun 23.
    private val tuesday = LocalDate.parse("2026-08-18")

    private fun day(date: String, steps: Long = 5_000, goalMet: Boolean? = true) = WeekDay(
        date = LocalDate.parse(date),
        steps = steps,
        distanceMeters = steps * 0.72,
        activeMinutes = (steps / 100).toInt(),
        walks = 1,
        goalMet = goalMet
    )

    private fun fullWeek(mondayIso: String, steps: Long = 5_000): List<WeekDay> {
        val monday = LocalDate.parse(mondayIso)
        return (0..6).map { day(monday.plusDays(it.toLong()).toString(), steps) }
    }

    @Test
    fun `the sides are this ISO week and the one immediately before`() {
        val diff = WeekDiff.of(fullWeek("2026-08-10") + fullWeek("2026-08-17"), tuesday)
        assertEquals(33, diff.previous.week)
        assertEquals(34, diff.current.week)
        assertEquals(LocalDate.parse("2026-08-10"), diff.previous.from)
        assertEquals(LocalDate.parse("2026-08-16"), diff.previous.to)
        assertEquals(LocalDate.parse("2026-08-23"), diff.current.to)
    }

    @Test
    fun `each side sums only its own days`() {
        val diff = WeekDiff.of(
            fullWeek("2026-08-10", steps = 6_000) + listOf(day("2026-08-17"), day("2026-08-18")),
            tuesday
        )
        assertEquals(42_000L, diff.previous.steps)
        assertEquals(7, diff.previous.daysWithData)
        assertTrue(diff.previous.isComplete)
        assertEquals(10_000L, diff.current.steps)
        assertEquals(2, diff.current.daysWithData)
        assertFalse(diff.current.isComplete)
        assertEquals(2, diff.current.walks)
    }

    @Test
    fun `a gap week is an empty side, never a week that has data pulled forward`() {
        // Week 33 is missing entirely; week 28 must NOT be promoted to "last week".
        val diff = WeekDiff.of(fullWeek("2026-07-06") + listOf(day("2026-08-18")), tuesday)
        assertEquals(33, diff.previous.week)
        assertFalse(diff.previous.hasData)
        assertEquals(0L, diff.previous.steps)
    }

    @Test
    fun `checks land in date order, and a day without a goal is skipped not failed`() {
        val diff = WeekDiff.of(
            listOf(
                day("2026-08-10", goalMet = true),
                day("2026-08-11", goalMet = false),
                day("2026-08-12", goalMet = null),
                day("2026-08-18")
            ),
            tuesday
        )
        assertEquals(
            listOf(
                GoalCheckResult.PASSED, GoalCheckResult.FAILED, GoalCheckResult.SKIPPED
            ),
            diff.previous.checks
        )
        assertEquals(1, diff.previous.checksPassed)
        assertEquals(2, diff.previous.checksRun) // the skipped day never ran one
    }

    @Test
    fun `a week that straddles the new year keeps its own ISO numbering`() {
        // Thu 2027-01-07 is in ISO week 1; the week before is week 53 of 2026.
        val diff = WeekDiff.of(
            fullWeek("2026-12-28") + listOf(day("2027-01-07")),
            LocalDate.parse("2027-01-07")
        )
        assertEquals(53, diff.previous.week)
        assertEquals(1, diff.current.week)
        assertEquals(7, diff.previous.daysWithData)
    }

    @Test
    fun `an empty history compares two empty weeks rather than blowing up`() {
        val diff = WeekDiff.of(emptyList(), tuesday)
        assertFalse(diff.previous.hasData)
        assertFalse(diff.current.hasData)
    }
}
