package com.callbackdev.tsteps.ui.steps

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.domain.DayStats
import com.callbackdev.tsteps.domain.Records
import com.callbackdev.tsteps.domain.SessionItem
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StepsReadmeTest {

    private val resources: Resources =
        ApplicationProvider.getApplicationContext<android.content.Context>().resources
    private val rome = ZoneId.of("Europe/Rome")
    private val today = LocalDate.parse("2026-08-18") // a Tuesday

    private fun snapshot(goal: Int = 10_000, kcal: Double? = 327.0, steps: Long = 8_432) =
        TodaySnapshot(
            date = today,
            steps = steps,
            goalSteps = goal,
            distanceMeters = 6_123.0,
            activeMinutes = 74,
            activeKcal = kcal,
            hourlySteps = LongArray(24).toList(),
            streakDays = 6
        )

    private fun walk(): SessionItem {
        val start = LocalDateTime.parse("2026-08-18T09:32:00").atZone(rome).toInstant().toEpochMilli()
        return SessionItem(
            id = 1, startMillis = start, endMillis = start + 46 * 60_000L, type = "walk",
            steps = 4_820, distanceMeters = 3_400.0, activeMillis = 46 * 60_000L, avgCadenceSpm = 105
        )
    }

    private fun SessionItem.asAuto() =
        copy(auto = true, startApprox = true, endApprox = true)

    private fun build(
        snapshot: TodaySnapshot? = snapshot(),
        status: SensorStatus = SensorStatus.OK,
        sessions: List<SessionItem> = emptyList(),
        history: List<DayStats> = listOf(
            DayStats(LocalDate.parse("2026-08-17"), 11_204, 8_300.0, 96)
        ),
        records: DayRecords? = DayRecords(
            bestDay = LocalDate.parse("2026-07-12") to 14_823L,
            longestWalk = walk().copy(activeMillis = 92 * 60_000L, distanceMeters = 6_600.0),
            bestWeek = Records.BestWeek(2026, 33, 52_340)
        )
    ) = StepsReadme.build(
        snapshot, status, sessions, history, records,
        UnitsSystem.METRIC, rome, Locale.ENGLISH, resources
    )

    private fun List<String>.lineWith(sub: String): String {
        val line = firstOrNull { it.contains(sub) }
        assertTrue("no line contains '$sub' in:\n${joinToString("\n")}", line != null)
        return line!!
    }

    @Test
    fun `the day opens with its localized prose title`() {
        assertEquals("# Tuesday 18 August 2026", build().first())
    }

    @Test
    fun `today section is prose with the bold step count`() {
        val lines = build()
        lines.lineWith("## Today")
        lines.lineWith("**8,432 steps** · 6.1 km · 74 min")
        lines.lineWith("Active calories: 327 kcal (estimate)")
    }

    @Test
    fun `kcal line vanishes without a weight`() {
        assertTrue(build(snapshot = snapshot(kcal = null)).none { it.contains("kcal") })
    }

    @Test
    fun `status is neutral progress words, never guilt`() {
        val lines = build()
        lines.lineWith("8,432 of 10,000 steps (84%) · 1,568 to go")
        lines.lineWith("Current streak: 6 days")
    }

    @Test
    fun `the percentage is the one the JSON check bar shows`() {
        // README prose and steps_data.json must never disagree on the number.
        val lines = build(snapshot = snapshot(steps = 5_000, goal = 8_000))
        lines.lineWith("(62%)")
        assertTrue(StepsGlyphs.goalBar(5_000, 8_000).endsWith(" 62%"))
        // 29 of 100 is where a double round-trip used to lose a point.
        build(snapshot = snapshot(steps = 29, goal = 100)).lineWith("(29%)")
        assertTrue(StepsGlyphs.goalBar(29, 100).endsWith(" 29%"))
    }

    @Test
    fun `a reached goal gets its check`() {
        build(snapshot = snapshot(steps = 11_000))
            .lineWith("✓ Goal reached: 11,000 of 10,000 steps (110%)")
    }

    @Test
    fun `without a goal the status says so and carries no streak`() {
        val lines = build(snapshot = snapshot(goal = 0))
        lines.lineWith("No goal set.")
        assertTrue(lines.none { it.contains("streak", ignoreCase = true) })
    }

    @Test
    fun `sensor problems are blockquote warnings`() {
        build(status = SensorStatus.NO_PERMISSION)
            .lineWith("> ⚠️ Missing permission to read the step counter")
        build(status = SensorStatus.NO_SENSOR)
            .lineWith("> ⚠️ No step sensor on this device")
    }

    @Test
    fun `walks render as an aligned table with right-aligned numbers`() {
        val lines = build(sessions = listOf(walk()))
        lines.lineWith("## Walks")
        val header = lines.lineWith("| Start |")
        val row = lines.lineWith("| 09:32 |")
        assertEquals(header.length, row.length) // padded to the same rectangle
        lines.lineWith("---:") // real right-alignment markers
        assertTrue(row.contains("4,820"))
    }

    @Test
    fun `an auto walk's start wears its tilde in the table`() {
        val lines = build(sessions = listOf(walk().asAuto()))
        lines.lineWith("| ~09:32 |")
    }

    @Test
    fun `the week table has seven rows, today bold and live, gaps as dashes`() {
        val lines = build()
        lines.lineWith("## Week")
        val weekRows = lines.dropWhile { !it.contains("## Week") }
            .filter { it.startsWith("|") && !it.contains("--") }
        assertEquals(8, weekRows.size) // header + 7 days
        val todayRow = lines.lineWith("**tue**")
        assertTrue(todayRow.contains("8,432"))
        val yesterdayRow = lines.lineWith("| mon")
        assertTrue(yesterdayRow.contains("11,204"))
        // Wednesday last week: no commit → dashes, not zeros
        val gapRow = lines.lineWith("| wed")
        assertTrue(gapRow.contains("—"))
        // Rectangle: every week row is as wide as the header
        assertEquals(1, weekRows.map { it.length }.distinct().size)
    }

    @Test
    fun `the week closes with its totals, in the shape of the Today line`() {
        // today (8,432 · 6,123 m · 74 min) + yesterday (11,204 · 8,300 m · 96 min)
        build().lineWith("Total: **19,636 steps** · 14.4 km · 170 min")
    }

    @Test
    fun `week totals count only the days there is data for`() {
        val lines = build(history = emptyList())
        lines.lineWith("Total: **8,432 steps** · 6.1 km · 74 min")
    }

    @Test
    fun `an empty week has no totals line to print`() {
        val lines = build(snapshot = null, status = SensorStatus.NO_SENSOR, history = emptyList())
        assertTrue(lines.none { it.contains("Total:") })
    }

    @Test
    fun `records close the file as sentences, not a third table`() {
        val lines = build()
        lines.lineWith("## Records")
        lines.lineWith("Best day: **14,823 steps** (12 July 2026)")
        lines.lineWith("Longest walk: **92 min** · 6.6 km (18 August 2026)")
        lines.lineWith("Best week: **52,340 steps** (week 33)")
        // Prose, so no pipes: stats.md owns the tabular view of the same tags.
        val records = lines.dropWhile { !it.contains("## Records") }
        assertTrue(records.none { it.startsWith("|") })
    }

    @Test
    fun `a record that does not exist yet prints no line`() {
        val lines = build(records = DayRecords(bestDay = LocalDate.parse("2026-07-12") to 9L))
        lines.lineWith("## Records")
        assertTrue(lines.none { it.contains("Longest walk") })
        assertTrue(lines.none { it.contains("Best week") })
    }

    @Test
    fun `no records at all means no section`() {
        assertTrue(build(records = null).none { it.contains("## Records") })
        assertTrue(build(records = DayRecords()).none { it.contains("## Records") })
    }

    @Test
    fun `the footer says where the numbers live`() {
        build().lineWith("*Computed on device · 1 committed days*")
    }
}
