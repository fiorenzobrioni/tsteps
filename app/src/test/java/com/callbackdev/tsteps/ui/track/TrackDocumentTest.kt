package com.callbackdev.tsteps.ui.track

import com.callbackdev.tsteps.data.SessionMetric
import com.callbackdev.tsteps.data.TrackingState
import com.callbackdev.tsteps.data.TranscriptEntry
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.domain.LiveSessionTracker
import com.callbackdev.tsteps.ui.components.CanvasLine
import com.callbackdev.tsteps.ui.components.CodeLine
import com.callbackdev.tsteps.ui.theme.ObsidianSyntax
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider
import android.content.Context

@RunWith(RobolectricTestRunner::class)
class TrackDocumentTest {

    private val resources =
        ApplicationProvider.getApplicationContext<Context>().resources

    private val syntax = ObsidianSyntax
    private val rome = ZoneId.of("Europe/Rome")
    private val startMillis =
        LocalDateTime.parse("2026-08-18T09:32:00").atZone(rome).toInstant().toEpochMilli()

    private fun state(paused: Boolean = false): TrackingState {
        var session = LiveSessionTracker.start("walk", startMillis)
        session = LiveSessionTracker.onReading(session, 50_000L)
        session = LiveSessionTracker.onReading(session, 52_431L)
        if (paused) session = LiveSessionTracker.pause(session, startMillis + 10 * 60_000L)
        return TrackingState(
            session = session,
            transcript = listOf(
                TranscriptEntry.Minute(60_000L, 96L, 70.0),
                TranscriptEntry.Paused,
                TranscriptEntry.Resumed
            ),
            strideMeters = 0.72
        )
    }

    private fun build(
        state: TrackingState = state(),
        nowMillis: Long = startMillis + 24 * 60_000L + 18_000L,
        metric: SessionMetric = SessionMetric.SPEED,
        stopArmed: Boolean = false
    ) = TrackDocument.build(
        resources,
        state, nowMillis, UnitsSystem.METRIC, metric, Locale.ENGLISH, rome, syntax, stopArmed
    )

    private fun List<CanvasLine>.lineWith(sub: String): CodeLine {
        val line = filterIsInstance<CodeLine>().firstOrNull { it.text.text.contains(sub) }
        assertNotNull(
            "no line contains '$sub' in:\n" +
                filterIsInstance<CodeLine>().joinToString("\n") { it.text.text },
            line
        )
        return line!!
    }

    @Test
    fun `the buffer opens with the command line and the process status`() {
        val lines = build()
        lines.lineWith("$ tsteps track walk")
        lines.lineWith("tracking… (^C to stop)")
        lines.lineWith("00:00  start  09:32")
    }

    @Test
    fun `transcript renders minute marks and shell-style pause events`() {
        val lines = build()
        lines.lineWith("1:00  96 steps  0.1 km")
        assertEquals(syntax.number, lines.lineWith("^Z  paused").text.spanStyles.first().item.color)
        assertEquals(syntax.diffAdd, lines.lineWith("fg  resumed").text.spanStyles.first().item.color)
    }

    @Test
    fun `the live line carries elapsed, steps, distance and speed`() {
        // 2431 steps × 0.72 m = 1.75 km in 24:18 → 4.3 km/h
        build().lineWith("24:18  2,431 steps · 1.8 km · 4.3 km/h")
    }

    @Test
    fun `pace preference swaps the live metric`() {
        // 24.3 min over 1.75 km = 13:53 min/km
        build(metric = SessionMetric.PACE).lineWith("min/km")
    }

    @Test
    fun `a paused process says so instead of tracking`() {
        val lines = build(state = state(paused = true))
        lines.lineWith("paused… (fg to resume)")
    }

    @Test
    fun `an armed stop turns the status line into the red confirm`() {
        val lines = build(stopArmed = true)
        val confirm = lines.lineWith("// tap ^C again to stop")
        assertEquals(syntax.diffDel, confirm.text.spanStyles.first().item.color)
        assertTrue(lines.filterIsInstance<CodeLine>().none { it.text.text.contains("tracking…") })
    }

    /**
     * The shell tokens are what the reader has to press; the words around them
     * are what tell them why. So `^C` and `fg` come through both languages
     * unchanged and everything else moves.
     */
    @Test
    @Config(qualifiers = "it")
    fun `in Italian the shell tokens survive and the words move`() {
        val running = build()
        running.lineWith("$ tsteps track walk")
        val status = running.lineWith("^C").text.text
        assertTrue(status, status.contains("traccio…"))
        assertTrue(status, !status.contains("tracking…"))

        val armed = build(stopArmed = true).lineWith("// tocca di nuovo ^C per fermare")
        assertTrue(armed.text.text.contains("^C"))
    }

}
