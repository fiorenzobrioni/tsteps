package com.callbackdev.tsteps.domain

import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** The per-day numbers a committed day contributes to the averages. */
data class DayStats(
    val date: LocalDate,
    val steps: Long,
    val distanceMeters: Double,
    val activeMinutes: Int
)

/** One `## averages` table row: means over the committed days of a window. */
data class WindowAverages(
    val windowDays: Int,
    val daysWithData: Int,
    val avgSteps: Long,
    val avgDistanceMeters: Double,
    val avgActiveMinutes: Int
)

/**
 * Averages over the last N days, computed on the days that actually have a
 * commit — an untracked day is missing data, not a zero to water the mean down
 * with (honest uncertainty over false precision, VISION-era rule). Null when
 * the window is empty: no line beats an invented one.
 */
object Averages {

    fun over(days: List<DayStats>, today: LocalDate, windowDays: Int): WindowAverages? {
        val from = today.minusDays(windowDays.toLong())
        val window = days.filter { it.date.isAfter(from) && !it.date.isAfter(today) }
        if (window.isEmpty()) return null
        return WindowAverages(
            windowDays = windowDays,
            daysWithData = window.size,
            avgSteps = (window.sumOf { it.steps }.toDouble() / window.size).roundToLong(),
            avgDistanceMeters = window.sumOf { it.distanceMeters } / window.size,
            avgActiveMinutes = (window.sumOf { it.activeMinutes }.toDouble() / window.size).roundToInt()
        )
    }
}
