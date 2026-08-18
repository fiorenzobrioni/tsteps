package com.callbackdev.tsteps.ui.steps

import androidx.compose.ui.graphics.Color
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.ui.components.CodeLine
import com.callbackdev.tsteps.ui.theme.ObsidianSyntax
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepsDocumentTest {

    private val syntax = ObsidianSyntax

    private fun snapshot(
        steps: Long = 8_432,
        goal: Int = 10_000,
        kcal: Double? = 327.0,
        streak: Int = 6
    ) = TodaySnapshot(
        date = LocalDate.parse("2026-08-18"),
        steps = steps,
        goalSteps = goal,
        distanceMeters = 6_123.0,
        activeMinutes = 74,
        activeKcal = kcal,
        hourlySteps = LongArray(24).toList(),
        streakDays = streak
    )

    private fun List<CodeLine>.lineWith(sub: String): CodeLine {
        val line = firstOrNull { it.text.text.contains(sub) }
        assertNotNull("no line contains '$sub' in:\n${joinToString("\n") { it.text.text }}", line)
        return line!!
    }

    private fun CodeLine.colorOf(sub: String): Color {
        val start = text.text.indexOf(sub)
        assertTrue("'$sub' not found in '${text.text}'", start >= 0)
        val range = text.spanStyles.first { it.start <= start && start + sub.length <= it.end }
        return range.item.color
    }

    private fun build(
        snapshot: TodaySnapshot? = snapshot(),
        status: SensorStatus = SensorStatus.OK,
        units: UnitsSystem = UnitsSystem.METRIC,
        comingSoon: Boolean = false,
        onGrant: (() -> Unit)? = null
    ) = StepsDocument.build(snapshot, status, units, syntax, comingSoon, onGrant, "grant")

    @Test
    fun `the full document renders every section with token colors`() {
        val lines = build()
        assertEquals(syntax.string, lines.lineWith("2026-08-18").colorOf("\"2026-08-18\""))
        assertEquals(syntax.number, lines.lineWith("\"count\"").colorOf("8432"))
        assertEquals(syntax.number, lines.lineWith("\"goal\"").colorOf("10000"))
        assertEquals(syntax.string, lines.lineWith("\"check\"").colorOf("▓"))
        assertEquals(syntax.number, lines.lineWith("\"distance_km\"").colorOf("6.1"))
        assertEquals(syntax.number, lines.lineWith("\"active_min\"").colorOf("74"))
        assertEquals(syntax.number, lines.lineWith("\"active_kcal\"").colorOf("327"))
        lines.lineWith("\"hourly\"")
        lines.lineWith("\"sessions\": []")
        assertEquals(syntax.number, lines.lineWith("\"streak_days\"").colorOf("6"))
    }

    @Test
    fun `estimate hints ride the value lines as dim comments`() {
        val lines = build()
        assertTrue(lines.lineWith("\"distance_km\"").text.text.contains("// estimated from stride length"))
        assertTrue(lines.lineWith("\"active_min\"").text.text.contains("// estimated at 100 steps/min"))
        assertTrue(lines.lineWith("\"hourly\"").text.text.contains("// 06..20"))
    }

    @Test
    fun `imperial units rename the key - the file must not lie`() {
        val lines = build(units = UnitsSystem.IMPERIAL)
        // 6123 m = 3.8 mi
        assertEquals(syntax.number, lines.lineWith("\"distance_mi\"").colorOf("3.8"))
        assertTrue(lines.none { it.text.text.contains("distance_km") })
    }

    @Test
    fun `no goal means no goal, no check, no streak`() {
        val lines = build(snapshot = snapshot(goal = 0))
        assertTrue(lines.none { it.text.text.contains("\"goal\"") })
        assertTrue(lines.none { it.text.text.contains("\"check\"") })
        assertTrue(lines.none { it.text.text.contains("\"streak_days\"") })
        lines.lineWith("\"count\"")
    }

    @Test
    fun `kcal without a weight is a hint, not a number`() {
        val lines = build(snapshot = snapshot(kcal = null))
        assertTrue(lines.none { it.text.text.contains("\"active_kcal\"") })
        lines.lineWith("// active_kcal: set profile.weight_kg to enable")
    }

    @Test
    fun `missing permission renders the error and a tappable grant command`() {
        var granted = false
        val lines = build(status = SensorStatus.NO_PERMISSION, onGrant = { granted = true })
        lines.lineWith("// E: ACTIVITY_RECOGNITION permission not granted")
        val command = lines.lineWith("tsteps grant activity-recognition")
        assertNotNull(command.onClick)
        command.onClick!!.invoke()
        assertTrue(granted)
        // The data below is an honest null, not a fake zero.
        assertEquals(syntax.comment, lines.lineWith("\"steps\"").colorOf("null"))
        assertTrue(lines.none { it.text.text.contains("\"count\"") })
    }

    @Test
    fun `missing sensor renders the compiler-style error`() {
        val lines = build(status = SensorStatus.NO_SENSOR)
        lines.lineWith("// E: no step sensor on this device")
        assertTrue(lines.none { it.text.text.contains("grant activity-recognition") })
        assertEquals(syntax.comment, lines.lineWith("\"steps\"").colorOf("null"))
    }

    @Test
    fun `the disabled FAB answers with a transient comment`() {
        val lines = build(comingSoon = true)
        assertEquals(
            syntax.comment,
            lines.lineWith("// $ tsteps track — coming soon").colorOf("coming soon")
        )
    }
}
