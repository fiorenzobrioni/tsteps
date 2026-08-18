package com.callbackdev.tsteps.domain

import java.time.LocalDate

/**
 * Personal records as `tag:` refs — informational, computed on read from the
 * committed days and sessions (never stored, so there is no record state to
 * corrupt).
 */
object Records {

    /** The committed day with the most steps; ties go to the most recent day. */
    fun bestDay(days: List<Pair<LocalDate, Long>>): LocalDate? =
        days.maxWithOrNull(compareBy({ it.second }, { it.first }))?.first

    /** The session with the most active (pause-free) minutes; ties → most recent. */
    fun longestWalk(sessions: List<SessionItem>): SessionItem? =
        sessions.maxWithOrNull(compareBy({ it.activeMillis }, { it.startMillis }))

    /** The ISO week with the most committed steps; ties → most recent week. */
    data class BestWeek(val weekBasedYear: Int, val week: Int, val steps: Long)

    fun bestWeek(days: List<Pair<LocalDate, Long>>): BestWeek? {
        val weekFields = java.time.temporal.WeekFields.ISO
        return days
            .groupBy {
                it.first.get(weekFields.weekBasedYear()) to
                    it.first.get(weekFields.weekOfWeekBasedYear())
            }
            .map { (week, weekDays) ->
                BestWeek(week.first, week.second, weekDays.sumOf { it.second })
            }
            .maxWithOrNull(compareBy({ it.steps }, { it.weekBasedYear }, { it.week }))
    }
}
