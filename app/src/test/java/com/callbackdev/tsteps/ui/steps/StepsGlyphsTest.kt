package com.callbackdev.tsteps.ui.steps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StepsGlyphsTest {

    @Test
    fun `sparkline covers the 06 to 20 window, one glyph per hour`() {
        val hourly = LongArray(24).toList()
        assertEquals(15, StepsGlyphs.sparkline(hourly).length)
    }

    @Test
    fun `a silent day is a flat baseline`() {
        assertEquals("▁".repeat(15), StepsGlyphs.sparkline(LongArray(24).toList()))
    }

    @Test
    fun `the busiest hour peaks and the shape is relative to it`() {
        val hourly = MutableList(24) { 0L }
        hourly[9] = 4_000L  // peak
        hourly[10] = 2_000L // half
        val line = StepsGlyphs.sparkline(hourly)
        assertEquals('█', line[9 - StepsGlyphs.SPARKLINE_FROM_HOUR])
        // Half the peak lands mid-scale (index 4 of ▁▂▃▄▅▆▇█ with .5 rounding).
        assertEquals('▅', line[10 - StepsGlyphs.SPARKLINE_FROM_HOUR])
        assertEquals('▁', line[0])
    }

    @Test
    fun `steps outside the window do not distort the scale`() {
        val hourly = MutableList(24) { 0L }
        hourly[3] = 50_000L // night noise, outside 06..20
        hourly[9] = 1_000L
        val line = StepsGlyphs.sparkline(hourly)
        assertEquals('█', line[9 - StepsGlyphs.SPARKLINE_FROM_HOUR])
    }

    @Test
    fun `goal bar fills proportionally and reports the percent`() {
        assertEquals("▓▓▓▓▓▓▓▓░░░░░░░░ 50%", StepsGlyphs.goalBar(5_000, 10_000))
        assertEquals("░░░░░░░░░░░░░░░░ 0%", StepsGlyphs.goalBar(0, 10_000))
        assertEquals("▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ 100%", StepsGlyphs.goalBar(10_000, 10_000))
    }

    @Test
    fun `past the goal the bar caps but the percent tells the truth`() {
        assertEquals("▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ 128%", StepsGlyphs.goalBar(12_800, 10_000))
    }

    @Test
    fun `the bar refuses to run without a goal`() {
        assertThrows(IllegalArgumentException::class.java) { StepsGlyphs.goalBar(100, 0) }
    }
}
