package com.callbackdev.tsteps.ui.steps

import kotlin.math.roundToInt

/**
 * The two glyph strings of `steps_data.json`. Pure string builders so their
 * behavior (scaling, rounding, edge cases) is unit-testable without Compose.
 */
object StepsGlyphs {

    /** Rendering window of the `"hourly"` sparkline: 06:00..20:59 inclusive. */
    const val SPARKLINE_FROM_HOUR = 6
    const val SPARKLINE_TO_HOUR = 20

    private const val BLOCKS = "▁▂▃▄▅▆▇█" // ▁▂▃▄▅▆▇█

    /**
     * One glyph per hour of the window, scaled to the window's own busiest hour
     * (relative shape, not absolute magnitude — the question it answers is *when*,
     * not *how much*). A silent day renders as a flat baseline.
     */
    fun sparkline(
        hourlySteps: List<Long>,
        fromHour: Int = SPARKLINE_FROM_HOUR,
        toHour: Int = SPARKLINE_TO_HOUR
    ): String {
        val window = (fromHour..toHour).map { hourlySteps.getOrElse(it) { 0L } }
        val max = window.max().coerceAtLeast(1L)
        return window.joinToString("") { steps ->
            val level = ((steps.toDouble() / max) * (BLOCKS.length - 1)).roundToInt()
            BLOCKS[level].toString()
        }
    }

    /**
     * The goal check's progress bar: `▓▓▓▓░░░░ 52%`. Fills left to right, caps the
     * bar at full but lets the percentage tell the truth past 100.
     */
    fun goalBar(steps: Long, goalSteps: Int, width: Int = 16): String {
        require(goalSteps > 0) { "goalBar needs a goal; the check is skipped without one" }
        val ratio = steps.toDouble() / goalSteps
        val filled = (ratio * width).toInt().coerceIn(0, width)
        val percent = (ratio * 100).toInt()
        return "▓".repeat(filled) + "░".repeat(width - filled) + " $percent%"
    }
}
