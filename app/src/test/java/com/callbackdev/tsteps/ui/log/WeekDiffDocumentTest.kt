package com.callbackdev.tsteps.ui.log

import androidx.compose.ui.graphics.Color
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.domain.WeekDay
import com.callbackdev.tsteps.domain.WeekDiff
import com.callbackdev.tsteps.ui.components.CanvasLine
import com.callbackdev.tsteps.ui.components.CodeLine
import com.callbackdev.tsteps.ui.theme.ObsidianSyntax
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `week.diff` line by line: the shape of the diff, and what it refuses to claim. */
class WeekDiffDocumentTest {

    private val syntax = ObsidianSyntax
    private val tuesday = LocalDate.parse("2026-08-18") // ISO week 34, day 2

    private fun day(date: String, steps: Long, walks: Int = 1, goalMet: Boolean? = true) =
        WeekDay(
            date = LocalDate.parse(date),
            steps = steps,
            distanceMeters = steps * 0.72,
            activeMinutes = (steps / 100).toInt(),
            walks = walks,
            goalMet = goalMet
        )

    /** Week 33 complete at 7,000/day; week 34 two days in at 5,000/day. */
    private fun days(): List<WeekDay> =
        (0..6).map { day(LocalDate.parse("2026-08-10").plusDays(it.toLong()).toString(), 7_000) } +
            listOf(day("2026-08-17", 5_000), day("2026-08-18", 5_000, goalMet = null))

    private fun build(
        days: List<WeekDay>? = days(),
        units: UnitsSystem = UnitsSystem.METRIC
    ) = WeekDiffDocument.build(
        comparison = days?.let { WeekDiff.of(it, tuesday) },
        units = units,
        locale = Locale.ENGLISH,
        syntax = syntax
    )

    private fun List<CanvasLine>.texts(): List<String> =
        filterIsInstance<CodeLine>().map { it.text.text }

    private fun List<CanvasLine>.lineWith(sub: String): CodeLine {
        val line = filterIsInstance<CodeLine>().firstOrNull { it.text.text.contains(sub) }
        assertNotNull("no line contains '$sub' in:\n${texts().joinToString("\n")}", line)
        return line!!
    }

    private fun CodeLine.colorOf(sub: String): Color {
        val start = text.text.indexOf(sub)
        assertTrue("'$sub' not found in '${text.text}'", start >= 0)
        return text.spanStyles.first { it.start <= start && start + sub.length <= it.end }.item.color
    }

    @Test
    fun `the file is the output of the command that names it`() {
        build().lineWith("$ git diff @{last.week}")
    }

    @Test
    fun `the headers name both weeks with their range and their day count`() {
        val lines = build()
        lines.lineWith("--- a/week 33   aug 10..16   7/7 days")
        lines.lineWith("+++ b/week 34   aug 17..23   2/7 days")
    }

    @Test
    fun `a week in progress says so instead of quietly pro-rating`() {
        val lines = build()
        lines.lineWith("// week 34 is still being written: 2 of 7 days so far")
        // The totals stay the totals: no scaled-up figure anywhere.
        lines.lineWith("+ 10,000")
    }

    @Test
    fun `a complete week carries no in-progress note`() {
        val complete = (0..6).map {
            day(LocalDate.parse("2026-08-17").plusDays(it.toLong()).toString(), 5_000)
        } + (0..6).map {
            day(LocalDate.parse("2026-08-10").plusDays(it.toLong()).toString(), 7_000)
        }
        assertTrue(build(days = complete).texts().none { it.contains("still being written") })
    }

    @Test
    fun `each metric is a hunk carrying its delta in the header`() {
        val lines = build()
        // 49,000 → 10,000
        lines.lineWith("@@ steps @@  -39,000  -80%")
        lines.lineWith("@@ distance_km @@  -28.1")
        lines.lineWith("@@ active_min @@  -390")
        lines.lineWith("@@ walks @@  -5")
    }

    @Test
    fun `the old and new values are the diff's removed and added lines`() {
        val lines = build()
        lines.lineWith("- 49,000")
        lines.lineWith("+ 10,000")
        assertEquals(syntax.diffDel, lines.lineWith("- 49,000").colorOf("- 49,000"))
        assertEquals(syntax.diffAdd, lines.lineWith("+ 10,000").colorOf("+ 10,000"))
    }

    @Test
    fun `the delta is colored by direction, not by which side it sits on`() {
        val down = build().lineWith("@@ steps @@")
        assertEquals(syntax.diffDel, down.colorOf("-39,000"))
        // Same shape the other way round: week 34 ahead of week 33.
        val up = build(
            days = (0..6).map {
                day(LocalDate.parse("2026-08-10").plusDays(it.toLong()).toString(), 1_000)
            } + listOf(day("2026-08-17", 9_000))
        ).lineWith("@@ steps @@")
        assertEquals(syntax.diffAdd, up.colorOf("+2,000"))
    }

    @Test
    fun `only steps carry a percentage - the others would repeat it`() {
        val lines = build()
        assertEquals(1, lines.texts().count { it.contains("%") })
    }

    @Test
    fun `checks are the CI column, and a day whose check has not run is a dot`() {
        val lines = build()
        // Week 33: seven passes. Week 34: monday passed, today has not run yet.
        lines.lineWith("- ✓✓✓✓✓✓✓  7/7")
        lines.lineWith("+ ✓·  1/1")
    }

    @Test
    fun `a week whose checks have not run yet shows glyphs, not a zero over zero`() {
        val monday = (0..6).map {
            day(LocalDate.parse("2026-08-10").plusDays(it.toLong()).toString(), 7_000)
        } + listOf(day("2026-08-17", 5_000, goalMet = null))
        val lines = build(days = monday)
        lines.lineWith("+ ·")
        assertTrue(lines.texts().none { it.contains("0/0") })
        lines.lineWith("- ✓✓✓✓✓✓✓  7/7")
    }

    @Test
    fun `no check ever ran means no check hunk`() {
        val never = days().map { it.copy(goalMet = null) }
        assertTrue(build(days = never).texts().none { it.contains("goal_checks") })
    }

    @Test
    fun `an empty previous week is a stated absence, never a zero`() {
        val lines = build(days = listOf(day("2026-08-18", 5_000)))
        lines.lineWith("// nothing to compare: week 33 has no committed days")
        assertTrue(lines.texts().none { it.contains("@@ steps @@") })
    }

    @Test
    fun `a percentage against a week that took no step is left out, not infinite`() {
        val lines = build(
            days = listOf(
                day("2026-08-10", 0, walks = 0),
                day("2026-08-17", 5_000)
            )
        )
        lines.lineWith("@@ steps @@  +5,000")
        assertTrue(lines.texts().none { it.contains("%") })
    }

    @Test
    fun `imperial renames the distance key, like every other file`() {
        val lines = build(units = UnitsSystem.IMPERIAL)
        lines.lineWith("@@ distance_mi @@")
        assertTrue(lines.texts().none { it.contains("distance_km") })
    }

    @Test
    fun `a week straddling two months spells out both`() {
        val lines = WeekDiffDocument.build(
            comparison = WeekDiff.of(
                (0..6).map {
                    day(LocalDate.parse("2026-08-24").plusDays(it.toLong()).toString(), 7_000)
                } + listOf(day("2026-09-01", 5_000)),
                LocalDate.parse("2026-09-01")
            ),
            units = UnitsSystem.METRIC,
            locale = Locale.ENGLISH,
            syntax = syntax
        )
        lines.lineWith("+++ b/week 36   aug 31..sep 6")
    }
}
