package com.callbackdev.tsteps.ui.stats

import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.domain.Heatmap
import com.callbackdev.tsteps.domain.HeatmapGrid
import com.callbackdev.tsteps.domain.Records
import com.callbackdev.tsteps.domain.SessionItem
import com.callbackdev.tsteps.domain.WindowAverages
import com.callbackdev.tsteps.ui.components.CanvasLine
import com.callbackdev.tsteps.ui.components.CodeLine
import com.callbackdev.tsteps.ui.theme.ObsidianSyntax
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider
import android.content.Context

@RunWith(RobolectricTestRunner::class)
class StatsDocumentTest {

    private val resources =
        ApplicationProvider.getApplicationContext<Context>().resources

    private val syntax = ObsidianSyntax
    private val rome = ZoneId.of("Europe/Rome")
    private val today = LocalDate.parse("2026-08-18")

    private fun grid(vararg days: Pair<String, Long>): HeatmapGrid =
        Heatmap.build(days.associate { LocalDate.parse(it.first) to it.second }, today)

    private fun build(
        grid: HeatmapGrid? = grid("2026-08-17" to 11_204L),
        streak: StreakInfo? = StreakInfo(6, 19),
        averages: List<WindowAverages> = listOf(WindowAverages(7, 5, 8_120, 5_900.0, 68)),
        totals: Totals? = Totals(LocalDate.parse("2026-06-01"), 412_309L, 291_400.0, 3_480),
        bestDay: Pair<LocalDate, Long>? = LocalDate.parse("2026-07-12") to 14_823L,
        longestWalk: SessionItem? = walk(),
        bestWeek: Records.BestWeek? = Records.BestWeek(2026, 33, 52_340),
        committedDays: Int = 42,
        units: UnitsSystem = UnitsSystem.METRIC,
        onOpenCommit: (LocalDate) -> Unit = {}
    ) = StatsDocument.build(
        resources,
        grid, streak, averages, totals, bestDay, longestWalk, bestWeek, committedDays,
        units, Locale.ENGLISH, rome, syntax, onOpenCommit
    )

    private fun walk(): SessionItem {
        val start = LocalDateTime.parse("2026-06-28T09:00:00").atZone(rome).toInstant().toEpochMilli()
        return SessionItem(
            id = 7,
            startMillis = start,
            endMillis = start + 92 * 60_000L,
            type = "walk",
            steps = 9_120,
            distanceMeters = 6_600.0,
            activeMillis = 92 * 60_000L,
            avgCadenceSpm = 99
        )
    }

    private fun List<CanvasLine>.texts() = filterIsInstance<CodeLine>().map { it.text.text }

    private fun List<CanvasLine>.lineWith(sub: String): CodeLine {
        val line = filterIsInstance<CodeLine>().firstOrNull { it.text.text.contains(sub) }
        assertNotNull("no line contains '$sub' in:\n${texts().joinToString("\n")}", line)
        return line!!
    }

    @Test
    fun `totals add up the whole repo since the first day that moved`() {
        val lines = build()
        lines.lineWith("## totals")
        lines.lineWith("since 2026-06-01: **412,309 steps** · 291.4 km · 58 h")
    }

    @Test
    fun `an imperial reader gets miles in the totals too`() {
        build(units = UnitsSystem.IMPERIAL).lineWith("181.1 mi")
    }

    @Test
    fun `nothing moved yet means no totals section`() {
        val lines = build(totals = null)
        assertTrue(lines.texts().none { it.contains("## totals") })
    }

    @Test
    fun `the file opens with its name and the contributions heading`() {
        val lines = build()
        assertEquals("# stats.md", lines.texts().first())
        lines.lineWith("## contributions (last 12 weeks)")
    }

    @Test
    fun `the heatmap is seven rows with alternating day labels and a month row`() {
        val lines = build().texts()
        val rows = lines.filter { it.startsWith("mon") || it.startsWith("wed") ||
            it.startsWith("fri") || it.startsWith("sun") }
        assertEquals(4, rows.size)
        // All heatmap rows carry 12 columns of 2-char cells after the 5-char label.
        val mon = lines.first { it.startsWith("mon") }
        assertEquals(5 + 12 * 2, mon.length)
        assertTrue(lines.any { it.trim().startsWith("jun") && it.contains("aug") })
    }

    @Test
    fun `an active day is a green cell, intensity from the level`() {
        val lines = build(grid = grid("2026-08-17" to 11_204L))
        val mon = lines.lineWith("mon")
        val cellStart = mon.text.text.indexOf("■")
        assertTrue(cellStart >= 5)
        val span = mon.text.spanStyles.first { it.start <= cellStart && cellStart < it.end }
        assertEquals(1f, span.item.color.alpha) // single day = the user's max level
        assertEquals(syntax.diffAdd.red, span.item.color.red, 1e-6f)
    }

    @Test
    fun `streak section renders only when a goal exists`() {
        build().lineWith("current: **6 days** · longest: **19 days**")
        val without = build(streak = null).texts()
        assertTrue(without.none { it.contains("## streak") })
    }

    @Test
    fun `averages render as a padded markdown table`() {
        val lines = build()
        lines.lineWith("## averages")
        lines.lineWith("| 7d")
        val row = lines.lineWith("8,120").text.text
        assertTrue(row.contains("5.9 km"))
        assertTrue(row.contains("68 min"))
    }

    @Test
    fun `tags table rows link to their commits`() {
        var opened: LocalDate? = null
        val lines = build(onOpenCommit = { opened = it })
        val bestDay = lines.lineWith("best-day")
        assertTrue(bestDay.text.text.contains("14,823 steps"))
        bestDay.onClick!!.invoke()
        assertEquals(LocalDate.parse("2026-07-12"), opened)

        val longest = lines.lineWith("longest-walk")
        assertTrue(longest.text.text.contains("92 min · 6.6 km"))
        longest.onClick!!.invoke()
        assertEquals(LocalDate.parse("2026-06-28"), opened)

        // best-week has no single commit to open
        assertNull(lines.lineWith("best-week").onClick)
        lines.lineWith("week 33")
    }

    @Test
    fun `imperial units convert the table values`() {
        val lines = build(units = UnitsSystem.IMPERIAL)
        lines.lineWith("3.7 mi") // 5900 m
        lines.lineWith("4.1 mi") // longest walk 6600 m
    }

    @Test
    fun `an empty history is honest and stops after the graph`() {
        val lines = build(
            streak = null, averages = emptyList(), bestDay = null,
            longestWalk = null, bestWeek = null, committedDays = 0
        )
        lines.lineWith("// nothing committed yet — records and averages")
        assertTrue(lines.texts().none { it.contains("## tags") })
        assertTrue(lines.texts().none { it.contains("computed on read") })
    }

    @Test
    fun `the footer says where the numbers come from`() {
        build().lineWith("*computed on read from 42 committed days*")
    }

    /**
     * Both halves of the seam on one screen: the `//` is the file's syntax and
     * the renderer keeps it, the sentence behind it is the reader's. The footer
     * comes along too — it is a sentence about the file, not a key in it.
     */
    @Test
    @Config(qualifiers = "it")
    fun `in Italian the marker stays and the sentence moves`() {
        val empty = build(
            streak = null, averages = emptyList(), bestDay = null,
            longestWalk = null, bestWeek = null, committedDays = 0
        )
        empty.lineWith("// ancora nessun commit — record e medie")
        assertTrue(empty.texts().none { it.contains("nothing committed yet") })

        val full = build()
        full.lineWith("*calcolato alla lettura su 42 giorni committati*")
        // Everything else in the file is the computed record, and it does not
        // move: the headings, the table columns, the tag names, the units that
        // line up with them. stats.md has exactly two sentences and both are here.
        full.lineWith("## contributions")
        full.lineWith("| window | steps | distance | active |")
        full.lineWith("| best-day")
        full.lineWith("current: **6 days**")
    }

}
