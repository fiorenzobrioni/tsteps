package com.callbackdev.tsteps.ui.steps

import androidx.compose.ui.graphics.Color
import com.callbackdev.tsteps.data.SessionMetric
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.domain.SessionItem
import com.callbackdev.tsteps.healthconnect.OriginSteps
import com.callbackdev.tsteps.ui.components.CanvasLine
import com.callbackdev.tsteps.ui.components.CodeLine
import com.callbackdev.tsteps.ui.components.WidgetLine
import com.callbackdev.tsteps.ui.theme.ObsidianSyntax
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        onAcceptGoal: (() -> Unit)? = null,
        onToggleSession: (Long) -> Unit = {},
        controls: SessionControls = SessionControls(),
        externalSteps: List<OriginSteps> = emptyList()
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
        onAcceptGoal = onAcceptGoal,
        acceptGoalLabel = "set goal",
        onToggleSession = onToggleSession,
        controls = controls,
        externalSteps = externalSteps
    )

    private fun session(
        id: Long = 1L,
        start: String = "2026-08-18T09:32:00",
        activeMin: Int = 46,
        steps: Long = 4_820,
        meters: Double = 3_400.0,
        cadence: Int? = 105,
        auto: Boolean = false,
        startApprox: Boolean = auto,
        endApprox: Boolean = auto
    ) = SessionItem(
        id = id,
        startMillis = LocalDateTime.parse(start).atZone(rome).toInstant().toEpochMilli(),
        endMillis = LocalDateTime.parse(start).atZone(rome).toInstant().toEpochMilli() +
            activeMin * 60_000L,
        type = "walk",
        steps = steps,
        distanceMeters = meters,
        activeMillis = activeMin * 60_000L,
        avgCadenceSpm = cadence,
        auto = auto,
        startApprox = startApprox,
        endApprox = endApprox
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
        assertTrue(lines.texts().none { it.contains("distance_km") })
    }

    @Test
    fun `no goal means no check and no streak, only an offer`() {
        val lines = build(snapshot = snapshot(goal = 0))
        assertTrue(lines.texts().none { it.contains("\"check\"") })
        assertTrue(lines.texts().none { it.contains("\"streak_days\"") })
        lines.lineWith("\"count\"")
        // The key exists as an explicit null that offers a number, never imposes it.
        val goal = lines.lineWith("\"goal\"")
        assertTrue(goal.text.text.contains("null"))
        assertTrue(goal.text.text.contains("// tap to set 8000"))
    }

    @Test
    fun `tapping the null goal accepts the suggestion`() {
        var accepted = 0
        val lines = build(snapshot = snapshot(goal = 0), onAcceptGoal = { accepted++ })
        lines.lineWith("\"goal\"").onClick!!.invoke()
        assertEquals(1, accepted)
    }

    @Test
    fun `a goal that exists is a number, not an offer`() {
        val lines = build(snapshot = snapshot(goal = 10_000))
        val goal = lines.lineWith("\"goal\"")
        assertTrue(goal.text.text.contains("10000"))
        assertTrue(goal.text.text.none { it == '/' })
        assertNull(goal.onClick)
    }

    @Test
    fun `kcal without a weight is a hint, not a number`() {
        val lines = build(snapshot = snapshot(kcal = null))
        assertTrue(lines.texts().none { it.contains("\"active_kcal\"") })
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
        assertTrue(lines.texts().none { it.contains("\"count\"") })
    }

    @Test
    fun `missing sensor renders the compiler-style error`() {
        val lines = build(status = SensorStatus.NO_SENSOR)
        lines.lineWith("// E: no step sensor on this device")
        assertTrue(lines.texts().none { it.contains("grant activity-recognition") })
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
        assertTrue(lines.texts().none { it.contains("avg_pace") })
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
        assertTrue(lines.texts().none { it.contains("avg_speed") })
    }

    @Test
    fun `tapping a session entry toggles it`() {
        var toggled: Long? = null
        val lines = build(sessions = listOf(session()), onToggleSession = { toggled = it })
        lines.lineWith("\"time\": \"09:32\"").onClick!!.invoke()
        assertEquals(1L, toggled)
    }

    // --- Fase 11: auto sessions, [rm], boundary editing ----------------------

    @Test
    fun `an auto session wears tildes inline and its source in the detail`() {
        val inline = build(sessions = listOf(session(auto = true)))
        inline.lineWith("\"time\": \"~09:32\"")

        val expanded = build(sessions = listOf(session(auto = true)), expandedIds = setOf(1L))
        expanded.lineWith("\"start\": \"~09:32\"")
        expanded.lineWith("\"end\": \"~10:18\"")
        assertTrue(expanded.lineWith("\"source\": \"auto\"").text.text.contains("// inferred"))
    }

    @Test
    fun `a user-edited boundary drops its tilde, the other keeps it`() {
        val lines = build(
            sessions = listOf(session(auto = true, startApprox = false)),
            expandedIds = setOf(1L)
        )
        lines.lineWith("\"start\": \"09:32\"")
        lines.lineWith("\"end\": \"~10:18\"")
    }

    @Test
    fun `manual sessions have no source line and no editable boundaries`() {
        val lines = build(sessions = listOf(session()), expandedIds = setOf(1L))
        assertTrue(lines.texts().none { it.contains("\"source\"") })
        assertNull(lines.lineWith("\"start\": \"09:32\"").onClick)
    }

    @Test
    fun `rm arms on the first tap and removes on the second`() {
        var removed: Long? = null
        var armed: Long? = null
        val controls = SessionControls(
            onRemove = { s -> if (armed == s.id) removed = s.id else armed = s.id }
        )
        val lines = build(
            sessions = listOf(session(auto = true)), expandedIds = setOf(1L),
            controls = controls
        )
        lines.lineWith("[rm]").onClick!!.invoke()
        assertEquals(1L, armed)
        assertNull(removed)

        // Re-rendered armed: deletion-red confirm hint, second tap removes.
        val armedLines = build(
            sessions = listOf(session(auto = true)), expandedIds = setOf(1L),
            controls = controls.copy(armedRemoveId = 1L)
        )
        val rm = armedLines.lineWith("[rm]")
        assertTrue(rm.text.text.contains("// tap again to remove"))
        assertEquals(syntax.diffDel, rm.colorOf("[rm]"))
        rm.onClick!!.invoke()
        assertEquals(1L, removed)
    }

    @Test
    fun `editing swaps start and end for one range prompt with its error line`() {
        val lines = build(
            sessions = listOf(session(auto = true)), expandedIds = setOf(1L),
            controls = SessionControls(
                editingId = 1L,
                editValue = "09:32..10:18",
                editError = "// ERROR: expected HH:mm..HH:mm"
            )
        )
        assertTrue(lines.texts().none { it.contains("\"start\": ") })
        assertTrue(lines.texts().none { it.contains("\"end\": ") })
        assertTrue(lines.any { it is WidgetLine })
        lines.lineWith("// ERROR: expected HH:mm..HH:mm")
    }

    @Test
    fun `external steps render per origin with the honest hint, never summed`() {
        val lines = build(
            externalSteps = listOf(
                OriginSteps("com.sec.android.app.shealth", "shealth", 5_102),
                OriginSteps("com.fitbit.FitbitMobile", "fitbit", 4_988)
            )
        )
        val header = lines.lineWith("\"health_connect\"")
        assertTrue(header.text.text.contains("// other apps' steps — shown, never added"))
        lines.lineWith("\"shealth\": 5102,")
        lines.lineWith("\"fitbit\": 4988")
        // No merged total anywhere: 5102 + 4988 must not exist as a number.
        assertTrue(lines.texts().none { it.contains("10090") })
    }

    @Test
    fun `no external data means no health_connect block at all`() {
        val lines = build()
        assertTrue(lines.texts().none { it.contains("health_connect") })
    }

    @Test
    fun `tapping an auto start line opens the editor`() {
        var editing: SessionItem? = null
        val lines = build(
            sessions = listOf(session(auto = true)), expandedIds = setOf(1L),
            controls = SessionControls(onStartEdit = { editing = it })
        )
        lines.lineWith("\"start\": \"~09:32\"").onClick!!.invoke()
        assertEquals(1L, editing?.id)
    }
}
