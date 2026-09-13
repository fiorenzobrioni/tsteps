package com.callbackdev.tsteps.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * A run of consecutive days the app has no reading for (Fase 24d). Not "days
 * with zero steps": the app cannot know whether nobody walked or nobody was
 * listening, and saying the first when it only knows the second is the kind of
 * lie the file is not allowed to tell.
 */
data class Gap(val from: LocalDate, val to: LocalDate) {
    val days: Int get() = (ChronoUnit.DAYS.between(from, to) + 1).toInt()
}

/**
 * Where the history has holes. Pure date arithmetic: no clock, no zone — the
 * caller has already decided what "today" means.
 */
object Gaps {

    /**
     * The days strictly between two commits, or null when they are adjacent (or
     * out of order, which a caller iterating a stream should never produce but
     * an empty answer handles better than an exception).
     */
    fun between(newer: LocalDate, older: LocalDate): Gap? {
        if (!older.isBefore(newer)) return null
        val from = older.plusDays(1)
        val to = newer.minusDays(1)
        return if (to.isBefore(from)) null else Gap(from, to)
    }

    /**
     * Every gap inside `[from, to]`, given the dates that do have data. Both ends
     * are inclusive, so a caller asking about the last week passes the week's
     * first and last day and gets the holes in it.
     */
    fun inWindow(covered: Set<LocalDate>, from: LocalDate, to: LocalDate): List<Gap> {
        if (to.isBefore(from)) return emptyList()
        val gaps = mutableListOf<Gap>()
        var runStart: LocalDate? = null
        var date = from
        while (!date.isAfter(to)) {
            if (date in covered) {
                runStart?.let { gaps += Gap(it, date.minusDays(1)) }
                runStart = null
            } else if (runStart == null) {
                runStart = date
            }
            date = date.plusDays(1)
        }
        runStart?.let { gaps += Gap(it, to) }
        return gaps
    }

    /** How many days in `[from, to]` have no data — the README's one number. */
    fun daysMissing(covered: Set<LocalDate>, from: LocalDate, to: LocalDate): Int =
        inWindow(covered, from, to).sumOf { it.days }
}
