package com.callbackdev.tsteps.ui.steps

import androidx.compose.ui.graphics.Color
import com.callbackdev.tsteps.data.SessionMetric
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.domain.SessionItem
import com.callbackdev.tsteps.ui.components.CodeLine
import com.callbackdev.tsteps.ui.theme.ObsidianSyntax
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepsDocumentTest {

    private val syntax = ObsidianSyntax
    private val rome = ZoneId.of("Europe/Rome")

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
        sessions: List<SessionItem> = emptyList(),
        expandedIds: Set<Long> = emptySet(),
        sessionMetric: SessionMetric = SessionMetric.SPEED,
        onGrant: (() -> Unit)? = null,
        onToggleSession: (Long) -> Unit = {}
    ) = StepsDocument.build(
        snapshot = snapshot,
        status = status,
        units = units,
        syntax = syntax,
        sessions = sessions,
        expandedSessionIds = expandedIds,
        sessionMetric = sessionMetric,
        zone = rome,
        onGrantPermission = onGrant,
        grantClickLabel = "grant",
        onToggleSession = onToggleSession
    )

    private fun session(
        id: Long = 1L,
        start: String = "2026-08-18T09:32:00",
        activeMin: Int = 46,
        steps: Long = 4_820,
        meters: Double = 3_400.0,
        cadence: Int? = 105
    ) = SessionItem(
        id = id,
        startMillis = LocalDateTime.parse(start).atZone(rome).toInstant().toEpochMilli(),
        endMillis = LocalDateTime.parse(start).atZone(rome).toInstant().toEpochMilli() +
            activeMin * 60_000L,
        type = "walk",
        steps = steps,
        distanceMeters = meters,
        activeMillis = activeMin * 60_000L,
        avgCadenceSpm = cadence
    )

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
    fun `a completed walk renders as one inline session entry`() {
        val lines = build(sessions = listOf(session()))
        val inline = lines.lineWith("\"time\": \"09:32\"")
        assertTrue(inline.text.text.contains("\"type\": \"walk\""))
        assertTrue(inline.text.text.contains("\"min\": 46"))
        assertTrue(inline.text.text.contains("\"steps\": 4820"))
        assertTrue(inline.text.text.contains("\"km\": 3.4"))
        assertNotNull(inline.onClick)
    }

    @Test
    fun `an expanded session shows its detail with speed by preference`() {
        val lines = build(sessions = listOf(session()), expandedIds = setOf(1L))
        lines.lineWith("\"start\": \"09:32\"")
        lines.lineWith("\"end\": \"10:18\"")
        lines.lineWith("\"active_min\": 46")
        // 3.4 km in 46 min = 4.4 km/h
        lines.lineWith("\"avg_speed_kmh\": 4.4")
        lines.lineWith("\"avg_cadence_spm\": 105")
        assertTrue(lines.none { it.text.text.contains("avg_pace") })
    }

    @Test
    fun `pace preference renders pace instead of speed`() {
        val lines = build(
            sessions = listOf(session()),
            expandedIds = setOf(1L),
            sessionMetric = SessionMetric.PACE
        )
        // 46 min over 3.4 km = 13:32 min/km
        lines.lineWith("\"avg_pace_min_km\": \"13:32\"")
        assertTrue(lines.none { it.text.text.contains("avg_speed") })
    }

    @Test
    fun `tapping a session entry toggles it`() {
        var toggled: Long? = null
        val lines = build(sessions = listOf(session()), onToggleSession = { toggled = it })
        lines.lineWith("\"time\": \"09:32\"").onClick!!.invoke()
        assertEquals(1L, toggled)
    }
}
