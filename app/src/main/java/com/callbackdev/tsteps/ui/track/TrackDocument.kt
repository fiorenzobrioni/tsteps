package com.callbackdev.tsteps.ui.track

import android.content.res.Resources
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.callbackdev.tsteps.R
import com.callbackdev.tsteps.data.SessionMetric
import com.callbackdev.tsteps.data.TrackingState
import com.callbackdev.tsteps.data.TranscriptEntry
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.domain.SessionMetrics
import com.callbackdev.tsteps.tracking.TrackingService
import com.callbackdev.tsteps.ui.components.CanvasLine
import com.callbackdev.tsteps.ui.components.CodeLine
import com.callbackdev.tsteps.ui.components.commentLine
import com.callbackdev.tsteps.ui.format.UnitFormat
import com.callbackdev.tsteps.ui.theme.SyntaxColors
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The `$ tsteps track` terminal buffer: the command line, the process status,
 * one transcript line per active minute (`05:00  512 steps  0.4 km`), pauses as
 * `^Z`/`fg` marks, and the live line at the bottom. Pure so every line is a
 * unit test.
 */
object TrackDocument {

    fun build(
        resources: Resources,
        state: TrackingState,
        nowMillis: Long,
        units: UnitsSystem,
        sessionMetric: SessionMetric,
        locale: Locale,
        zone: ZoneId,
        syntax: SyntaxColors,
        stopArmed: Boolean = false,
        onCycleType: (() -> Unit)? = null,
        cycleTypeLabel: String? = null
    ): List<CanvasLine> = buildList {
        val numbers = NumberFormat.getIntegerInstance(locale)
        val session = state.session

        // `$ tsteps track walk` — the type is the one editable token of the line.
        add(
            CodeLine(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = syntax.comment)) { append("$ ") }
                    withStyle(SpanStyle(color = syntax.diffAdd)) { append("tsteps track ") }
                    withStyle(SpanStyle(color = syntax.string)) { append(session.type) }
                },
                onClick = onCycleType,
                onClickLabel = cycleTypeLabel
            )
        )
        add(
            commentLine(
                when {
                    stopArmed -> "// " + resources.getString(R.string.note_tap_stop)
                    session.paused -> resources.getString(R.string.note_paused)
                    else -> resources.getString(R.string.note_tracking)
                },
                syntax
            ).let { line ->
                if (stopArmed) {
                    CodeLine(AnnotatedString(line.text.text, SpanStyle(color = syntax.diffDel)))
                } else {
                    line
                }
            }
        )
        add(blank())

        val startClock = Instant.ofEpochMilli(session.startMillis).atZone(zone)
            .format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH))
        add(commentLine("00:00  start  $startClock", syntax))
        state.transcript.forEach { entry ->
            when (entry) {
                is TranscriptEntry.Minute -> add(
                    commentLine(
                        "${TrackingService.formatElapsed(entry.elapsedMillis)}  " +
                            "${numbers.format(entry.steps)} steps  " +
                            UnitFormat.distance(entry.distanceMeters, units),
                        syntax
                    )
                )
                TranscriptEntry.Paused -> add(
                    CodeLine(AnnotatedString("^Z  paused", SpanStyle(color = syntax.number)))
                )
                TranscriptEntry.Resumed -> add(
                    CodeLine(AnnotatedString("fg  resumed", SpanStyle(color = syntax.diffAdd)))
                )
            }
        }
        add(blank())

        // The live line: elapsed · steps · distance · speed-or-pace (if computable).
        val activeMillis = session.activeMillis(nowMillis)
        val live = buildString {
            append(TrackingService.formatElapsed(activeMillis))
            append("  ${numbers.format(session.steps)} steps")
            append(" · ${UnitFormat.distance(state.distanceMeters, units)}")
            liveMetric(state, activeMillis, units, sessionMetric)?.let { append(" · $it") }
        }
        add(CodeLine(AnnotatedString(live)))
    }

    private fun liveMetric(
        state: TrackingState,
        activeMillis: Long,
        units: UnitsSystem,
        metric: SessionMetric
    ): String? = when (metric) {
        SessionMetric.SPEED ->
            SessionMetrics.avgSpeedKmh(state.distanceMeters, activeMillis)?.let { kmh ->
                UnitFormat.speedValue(kmh, units) +
                    " " + UnitFormat.speedLabel(units)
            }
        SessionMetric.PACE ->
            SessionMetrics.pacePerUnit(
                state.distanceMeters, activeMillis,
                UnitFormat.unitMeters(units)
            )?.let { pace ->
                "$pace " + UnitFormat.paceLabel(units)
            }
    }

    private fun blank() = CodeLine(AnnotatedString(""))
}
