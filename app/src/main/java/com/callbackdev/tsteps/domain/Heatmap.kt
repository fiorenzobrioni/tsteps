package com.callbackdev.tsteps.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * One day of the contribution graph. [steps] is null for days that haven't
 * happened yet (the grid's trailing blanks); [level] is 0 for a zero/absent day
 * and 1..4 for the intensity bucket.
 */
data class HeatmapCell(
    val date: LocalDate,
    val steps: Long?,
    val level: Int
)

/** One ISO week column, Monday-first, always 7 cells. */
data class HeatmapWeek(
    val cells: List<HeatmapCell>,
    /** True when this column's Monday enters a new month — where its label goes. */
    val monthStart: Boolean,
    /** Lowercase short month name of the column's Monday, for the label row. */
    val monthLabel: String
)

/** The grid: [weeks] oldest → newest, ending with the week containing today. */
data class HeatmapGrid(val weeks: List<HeatmapWeek>)

/**
 * The movement contribution graph — the screen the git metaphor unlocks.
 * Intensity buckets are **relative to the user's own distribution** in the
 * window (quartiles of the non-zero days), never to an absolute 10,000: the
 * graph answers "how consistent am I, by my own standards" (VISION §4.3).
 */
object Heatmap {

    const val LEVELS = 4

    fun build(
        stepsByDate: Map<LocalDate, Long>,
        today: LocalDate,
        weeks: Int = 12,
        locale: Locale = Locale.ENGLISH
    ): HeatmapGrid {
        val firstMonday = today.with(DayOfWeek.MONDAY).minusWeeks(weeks - 1L)
        val window = stepsByDate.filterKeys { !it.isBefore(firstMonday) && !it.isAfter(today) }
        val nonZero = window.values.filter { it > 0 }.sorted()
        val q1 = percentile(nonZero, 0.25)
        val q2 = percentile(nonZero, 0.50)
        val q3 = percentile(nonZero, 0.75)

        var previousMonth = -1
        val columns = (0 until weeks).map { w ->
            val monday = firstMonday.plusWeeks(w.toLong())
            val monthStart = monday.monthValue != previousMonth
            previousMonth = monday.monthValue
            HeatmapWeek(
                cells = (0..6).map { d ->
                    val date = monday.plusDays(d.toLong())
                    if (date.isAfter(today)) {
                        HeatmapCell(date, steps = null, level = 0)
                    } else {
                        val steps = window[date] ?: 0L
                        HeatmapCell(date, steps, level(steps, q1, q2, q3))
                    }
                },
                monthStart = monthStart,
                monthLabel = monday.month.getDisplayName(TextStyle.SHORT, locale)
                    .lowercase(locale)
            )
        }
        return HeatmapGrid(columns)
    }

    private fun level(steps: Long, q1: Long?, q2: Long?, q3: Long?): Int = when {
        steps <= 0L || q1 == null || q2 == null || q3 == null -> if (steps > 0) LEVELS else 0
        steps < q1 -> 1
        steps < q2 -> 2
        steps < q3 -> 3
        else -> 4
    }

    /**
     * Upper-bound style quartile (index = floor(size × f)): with `<` comparisons
     * this spreads N distinct values evenly over the levels and sends a single
     * lonely value to the top bucket — one active day is that user's max, not a
     * dim dot.
     */
    private fun percentile(sorted: List<Long>, fraction: Double): Long? =
        sorted.getOrNull((sorted.size * fraction).toInt().coerceAtMost(sorted.size - 1))
}
