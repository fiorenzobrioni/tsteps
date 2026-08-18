package com.callbackdev.tsteps.ui.log

import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.domain.CommitHash
import com.callbackdev.tsteps.ui.components.CanvasLine
import com.callbackdev.tsteps.ui.components.CodeLine
import com.callbackdev.tsteps.ui.theme.ObsidianSyntax
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogDocumentTest {

    private val syntax = ObsidianSyntax

    private fun today() = UncommittedToday(
        date = LocalDate.parse("2026-08-18"),
        steps = 8_432,
        distanceMeters = 6_123.0,
        activeMinutes = 74
    )

    private fun day(
        date: String,
        steps: Long = 11_204,
        kcal: Double? = 421.0,
        goal: Int = 10_000,
        goalMet: Boolean? = steps >= goal
    ) = CommitDay(
        date = LocalDate.parse(date),
        steps = steps,
        activeMinutes = 96,
        distanceMeters = 8_300.0,
        activeKcal = kcal,
        goalSteps = goal,
        goalMet = if (goal > 0) goalMet else null
    )

    private fun build(
        today: UncommittedToday? = today(),
        days: List<CommitDay> = emptyList(),
        expanded: Set<LocalDate> = emptySet(),
        bestDay: LocalDate? = null,
        units: UnitsSystem = UnitsSystem.METRIC,
        onToggle: (LocalDate) -> Unit = {}
    ) = LogDocument.build(
        today, days, expanded, bestDay, units, Locale.ENGLISH, syntax, onToggle
    )

    private fun List<CanvasLine>.texts() = filterIsInstance<CodeLine>().map { it.text.text }

    private fun List<CanvasLine>.lineWith(sub: String): CodeLine {
        val line = filterIsInstance<CodeLine>().firstOrNull { it.text.text.contains(sub) }
        assertNotNull("no line contains '$sub' in:\n${texts().joinToString("\n")}", line)
        return line!!
    }

    @Test
    fun `today sits on top as uncommitted changes`() {
        val lines = build().texts()
        assertEquals("# On branch main", lines[0])
        assertEquals("# Changes not yet committed (today)", lines[1])
        assertEquals("#   8,432 steps · 6.1 km · 74 min", lines[2])
    }

    @Test
    fun `no commits yet is an honest comment, not an empty screen`() {
        build().lineWith("// no commits yet — the first day commits at midnight")
    }

    @Test
    fun `a committed day is a commit with stable hash, author and message`() {
        val lines = build(days = listOf(day("2026-08-17")))
        lines.lineWith("commit " + CommitHash.of(LocalDate.parse("2026-08-17")))
        lines.lineWith("Author: you@tsteps.app")
        lines.lineWith("Date:   Mon 2026-08-17")
        lines.lineWith("11,204 steps · 8.3 km · 96 min · 421 kcal")
    }

    @Test
    fun `goal checks are factual - passed, failed, or absent without a goal`() {
        val lines = build(
            days = listOf(
                day("2026-08-17", steps = 11_204),
                day("2026-08-16", steps = 4_113, kcal = null),
                day("2026-08-15", goal = 0)
            )
        )
        val passed = lines.lineWith("✓ goal check passed (11,204 ≥ 10,000)")
        assertEquals(syntax.diffAdd, passed.text.spanStyles.first().item.color)
        val failed = lines.lineWith("✗ goal check failed (4,113 < 10,000)")
        assertEquals(syntax.diffDel, failed.text.spanStyles.first().item.color)
        // The skipped day has exactly two check glyph lines in the whole doc.
        assertEquals(2, lines.texts().count { it.contains("goal check") })
    }

    @Test
    fun `expanding a commit shows its diff - steps are the added lines`() {
        val date = LocalDate.parse("2026-08-17")
        val lines = build(days = listOf(day("2026-08-17")), expanded = setOf(date))
        lines.lineWith("--- a/steps_data.json")
        lines.lineWith("+++ b/steps_data.json")
        lines.lineWith("@@ 2026-08-17 @@")
        val added = lines.lineWith("+ \"steps\": 11204")
        assertEquals(syntax.diffAdd, added.gutterColor)
        lines.lineWith("+ \"distance_km\": 8.3")
        lines.lineWith("+ \"active_min\": 96")
        lines.lineWith("+ \"active_kcal\": 421")
    }

    @Test
    fun `collapsed commits keep their diff hidden`() {
        val lines = build(days = listOf(day("2026-08-17"))).texts()
        assertTrue(lines.none { it.contains("+++ b/steps_data.json") })
    }

    @Test
    fun `tapping the commit header toggles it`() {
        var toggled: LocalDate? = null
        val lines = build(days = listOf(day("2026-08-17")), onToggle = { toggled = it })
        val header = lines.lineWith("commit ")
        header.onClick!!.invoke()
        assertEquals(LocalDate.parse("2026-08-17"), toggled)
    }

    @Test
    fun `imperial units rename the diff key`() {
        val date = LocalDate.parse("2026-08-17")
        val lines = build(
            days = listOf(day("2026-08-17")),
            expanded = setOf(date),
            units = UnitsSystem.IMPERIAL
        )
        lines.lineWith("+ \"distance_mi\": 5.2")
        assertTrue(lines.texts().none { it.contains("distance_km") })
    }

    @Test
    fun `week separators carry the total and the delta vs the week before`() {
        // ISO weeks: Aug 17 2026 is a Monday (week 34); Aug 16 a Sunday (week 33).
        val lines = build(
            days = listOf(
                day("2026-08-17", steps = 11_204, goal = 0),
                day("2026-08-16", steps = 5_000, goal = 0),
                day("2026-08-14", steps = 4_000, goal = 0)
            )
        )
        lines.lineWith("--- week 34 · 11,204 steps (+2,204 vs week 33) ---")
        lines.lineWith("--- week 33 · 9,000 steps ---")
    }

    @Test
    fun `a lighter week gets a red minus delta`() {
        val lines = build(
            days = listOf(
                day("2026-08-17", steps = 3_000, goal = 0),
                day("2026-08-16", steps = 9_000, goal = 0)
            )
        )
        lines.lineWith("--- week 34 · 3,000 steps (-6,000 vs week 33) ---")
    }

    @Test
    fun `the best day wears its tag`() {
        val best = LocalDate.parse("2026-08-17")
        val lines = build(days = listOf(day("2026-08-17")), bestDay = best)
        lines.lineWith("(tag: best-day)")
    }
}
