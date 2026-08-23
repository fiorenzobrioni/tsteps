package com.callbackdev.tsteps.domain

import java.time.LocalDate
import java.time.temporal.WeekFields

/** One day as the week diff sees it: what it did, and how its check went. */
data class WeekDay(
    val date: LocalDate,
    val steps: Long,
    val distanceMeters: Double,
    val activeMinutes: Int,
    val walks: Int,
    /** Null = no goal was set that day: the check was skipped, not failed. */
    val goalMet: Boolean?
)

/**
 * One side of the diff: an ISO week, summed over the days that have data.
 *
 * [daysWithData] is the honesty of the whole file. Comparing a Tuesday-deep week
 * against a finished one is the comparison people actually want ("am I moving
 * like last week?"), but the totals are not commensurable, so both sides carry
 * their day count and the document says so out loud rather than quietly
 * rescaling anything. A silently pro-rated total would be a number the rest of
 * the app never shows.
 */
data class WeekSide(
    val week: Int,
    /** The ISO week's own Monday and Sunday, whether or not they have data. */
    val from: LocalDate,
    val to: LocalDate,
    val daysWithData: Int,
    val steps: Long,
    val distanceMeters: Double,
    val activeMinutes: Int,
    val walks: Int,
    /** One entry per day with data, in date order. */
    val checks: List<GoalCheckResult>
) {
    val hasData: Boolean get() = daysWithData > 0
    val isComplete: Boolean get() = daysWithData >= WeekDiff.DAYS_IN_WEEK
    val checksPassed: Int get() = checks.count { it == GoalCheckResult.PASSED }
    val checksRun: Int get() = checks.count { it != GoalCheckResult.SKIPPED }
}

/** The two weeks the diff puts side by side, newest as the `+` side. */
data class WeekComparison(val previous: WeekSide, val current: WeekSide)

/**
 * `git diff @{last.week}` — the week-over-week comparison VISION §2 maps onto a
 * real diff. The log's week separators already carry the steps delta in passing;
 * this is the whole picture in one file, all five metrics with their old and new
 * values.
 *
 * The previous side is the ISO week **immediately** before the current one, never
 * "the most recent week that happens to have data": labelling week 28 as last
 * week because 33 is empty would be the file lying about which weeks it compared.
 * An empty previous week is a state the document renders, not a lookup to retry.
 */
object WeekDiff {

    const val DAYS_IN_WEEK = 7

    private val Iso = WeekFields.ISO

    fun of(days: List<WeekDay>, today: LocalDate): WeekComparison {
        val monday = today.with(Iso.dayOfWeek(), 1L)
        return WeekComparison(
            previous = side(days, monday.minusWeeks(1)),
            current = side(days, monday)
        )
    }

    private fun side(days: List<WeekDay>, monday: LocalDate): WeekSide {
        val sunday = monday.plusDays((DAYS_IN_WEEK - 1).toLong())
        val inWeek = days
            .filter { !it.date.isBefore(monday) && !it.date.isAfter(sunday) }
            .sortedBy { it.date }
        return WeekSide(
            week = monday.get(Iso.weekOfWeekBasedYear()),
            from = monday,
            to = sunday,
            daysWithData = inWeek.size,
            steps = inWeek.sumOf { it.steps },
            distanceMeters = inWeek.sumOf { it.distanceMeters },
            activeMinutes = inWeek.sumOf { it.activeMinutes },
            walks = inWeek.sumOf { it.walks },
            checks = inWeek.map { day ->
                when (day.goalMet) {
                    null -> GoalCheckResult.SKIPPED
                    true -> GoalCheckResult.PASSED
                    false -> GoalCheckResult.FAILED
                }
            }
        )
    }
}
