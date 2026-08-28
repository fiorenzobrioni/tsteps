package com.callbackdev.tsteps.ui.steps

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.callbackdev.tsteps.R
import com.callbackdev.tsteps.data.SUGGESTED_DAILY_GOAL_STEPS
import com.callbackdev.tsteps.data.SessionMetric
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.domain.SessionItem
import com.callbackdev.tsteps.domain.SessionMetrics
import com.callbackdev.tsteps.healthconnect.OriginSteps
import com.callbackdev.tsteps.ui.components.CanvasLine
import com.callbackdev.tsteps.ui.components.CodeLine
import com.callbackdev.tsteps.ui.components.TerminalInput
import com.callbackdev.tsteps.ui.components.WidgetLine
import com.callbackdev.tsteps.ui.components.commentLine
import com.callbackdev.tsteps.ui.components.punctLine
import com.callbackdev.tsteps.ui.format.UnitFormat
import com.callbackdev.tsteps.ui.theme.SyntaxColors
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Everything `steps_data.json` needs to render one day. Assembled by the
 * ViewModel from Room + settings; the builders below are pure so the whole
 * document is unit-testable line by line.
 */
data class TodaySnapshot(
    val date: LocalDate,
    val steps: Long,
    /**
     * 0 = no goal: no check, no bar, no streak (no guilt without opt-in). The
     * key still exists as an explicit `null` offering one — see [StepsDocument].
     */
    val goalSteps: Int,
    val distanceMeters: Double,
    val activeMinutes: Int,
    /** Null = no weight in the profile: the line is absent, not invented. */
    val activeKcal: Double?,
    /** 24 buckets, index = local hour. */
    val hourlySteps: List<Long>,
    val streakDays: Int
)

/** Sensor pipeline state — decides which document the editor shows. */
enum class SensorStatus {
    /** Live counting. */
    OK,

    /** ACTIVITY_RECOGNITION not granted: the file offers the grant command. */
    NO_PERMISSION,

    /** No hardware step counter on this device. */
    NO_SENSOR
}

/**
 * Today's session verbs (Fase 11), bundled so [StepsDocument.build] stays
 * readable: which session has `[rm]` armed, which one is editing its
 * boundaries through the terminal prompt, and the callbacks each control fires.
 * The working tree is the only place sessions are managed — committed history
 * stays read-only, like any git history.
 */
data class SessionControls(
    val armedRemoveId: Long? = null,
    val editingId: Long? = null,
    val editValue: String = "",
    val editError: String? = null,
    val onEditValue: (String) -> Unit = {},
    val onStartEdit: (SessionItem) -> Unit = {},
    val onSubmitEdit: () -> Unit = {},
    val onCancelEdit: () -> Unit = {},
    val onRemove: (SessionItem) -> Unit = {},
    val removeLabel: (SessionItem) -> String = { "" },
    val editLabel: (SessionItem) -> String = { "" },
    val cancelLabel: String? = null
)

/**
 * The `steps_data.json` document. Hand-built lines (not [buildJsonLines]) because
 * this file carries what generic JSON can't: trailing `//` hints on value lines,
 * a tappable `$` command in the error state, keys that appear or vanish with the
 * data they describe, and session entries that expand in place into their detail
 * object (tap to toggle — the editor's way of opening a collapsed node).
 *
 * It takes a [Resources] because since Fase 20 the `//` hints that are sentences
 * are the reader's (VISION §1.3). That is a different answer from tweather's sky
 * document, which takes its sentences in as strings: **that** builder promises no
 * Android in it, and this one never did — it already speaks `SyntaxColors` and
 * returns `CanvasLine`. Threading resources through it costs nothing the file was
 * protecting, and it saves a second copy of the English.
 */
object StepsDocument {

    fun build(
        resources: Resources,
        snapshot: TodaySnapshot?,
        status: SensorStatus,
        units: UnitsSystem,
        syntax: SyntaxColors,
        sessions: List<SessionItem> = emptyList(),
        expandedSessionIds: Set<Long> = emptySet(),
        sessionMetric: SessionMetric = SessionMetric.SPEED,
        zone: ZoneId = ZoneId.systemDefault(),
        onGrantPermission: (() -> Unit)? = null,
        grantClickLabel: String? = null,
        onAcceptGoal: (() -> Unit)? = null,
        acceptGoalLabel: String? = null,
        onToggleSession: (Long) -> Unit = {},
        sessionToggleLabel: (String) -> String = { it },
        controls: SessionControls = SessionControls(),
        externalSteps: List<OriginSteps> = emptyList()
    ): List<CanvasLine> = buildList {
        when (status) {
            SensorStatus.NO_SENSOR -> {
                add(commentLine("// E: " + resources.getString(R.string.note_no_sensor), syntax))
                add(commentLine("// " + resources.getString(R.string.note_no_sensor_passive), syntax))
                add(blank())
                addAll(emptyDocument(snapshot?.date, syntax))
            }

            SensorStatus.NO_PERMISSION -> {
                add(commentLine("// E: " + resources.getString(R.string.note_no_permission), syntax))
                add(commentLine("// " + resources.getString(R.string.note_on_device), syntax))
                add(grantCommandLine(syntax, onGrantPermission, grantClickLabel))
                add(blank())
                addAll(emptyDocument(snapshot?.date, syntax))
            }

            SensorStatus.OK -> addAll(
                dataDocument(
                    resources,
                    snapshot ?: return@buildList, units, syntax, sessions,
                    expandedSessionIds, sessionMetric, zone, onToggleSession,
                    sessionToggleLabel, controls, externalSteps,
                    onAcceptGoal, acceptGoalLabel
                )
            )
        }
    }

    private fun dataDocument(
        resources: Resources,
        snapshot: TodaySnapshot,
        units: UnitsSystem,
        syntax: SyntaxColors,
        sessions: List<SessionItem>,
        expandedSessionIds: Set<Long>,
        sessionMetric: SessionMetric,
        zone: ZoneId,
        onToggleSession: (Long) -> Unit,
        sessionToggleLabel: (String) -> String,
        controls: SessionControls,
        externalSteps: List<OriginSteps>,
        onAcceptGoal: (() -> Unit)?,
        acceptGoalLabel: String?
    ): List<CanvasLine> = buildList {
        add(punctLine("{", 0, syntax))
        add(stringLine("date", snapshot.date.toString(), comma = true, syntax, indent = 1))

        add(keyOpen("steps", syntax, indent = 1))
        val hasGoal = snapshot.goalSteps > 0
        add(numberLine("count", snapshot.steps.toString(), comma = true, syntax, indent = 2))
        if (hasGoal) {
            add(numberLine("goal", snapshot.goalSteps.toString(), comma = true, syntax, indent = 2))
            add(
                stringLine(
                    "check",
                    StepsGlyphs.goalBar(snapshot.steps, snapshot.goalSteps),
                    comma = false, syntax, indent = 2
                )
            )
        } else {
            // No goal is a real state, not an empty one: an explicit null that
            // OFFERS a number instead of imposing one. Tapping it is the whole
            // opt-in — the same tappable-way-out idiom as the grant command
            // above, and until it is tapped no check runs and no streak exists.
            add(
                rawValueLine(
                    "goal", "null", comma = false, syntax, indent = 2,
                    hint = "// " + resources.getString(R.string.note_tap_set_goal, SUGGESTED_DAILY_GOAL_STEPS),
                    onClick = onAcceptGoal,
                    onClickLabel = acceptGoalLabel
                )
            )
        }
        add(punctLine("},", 1, syntax))

        add(keyOpen("movement", syntax, indent = 1))
        add(
            numberLine(
                UnitFormat.distanceKey(units),
                UnitFormat.distanceValue(snapshot.distanceMeters, units),
                comma = true, syntax, indent = 2,
                hint = "// " + resources.getString(R.string.note_estimated_stride)
            )
        )
        add(
            numberLine(
                "active_min", snapshot.activeMinutes.toString(),
                comma = snapshot.activeKcal != null, syntax, indent = 2,
                hint = "// " + resources.getString(R.string.note_estimated_cadence)
            )
        )
        val kcal = snapshot.activeKcal
        if (kcal != null) {
            add(
                numberLine(
                    "active_kcal", kcal.toInt().toString(), comma = false, syntax, indent = 2,
                    hint = "// " + resources.getString(R.string.note_kcal_formula)
                )
            )
        } else {
            add(commentLine("// " + resources.getString(R.string.note_kcal_needs_weight), syntax, indent = 2))
        }
        add(punctLine("},", 1, syntax))

        add(
            stringLine(
                "hourly", StepsGlyphs.sparkline(snapshot.hourlySteps),
                comma = true, syntax, indent = 1,
                hint = "// %02d..%02d".format(
                    StepsGlyphs.SPARKLINE_FROM_HOUR, StepsGlyphs.SPARKLINE_TO_HOUR
                )
            )
        )

        val hasExternal = externalSteps.isNotEmpty()
        addSessions(
            resources,
            sessions, expandedSessionIds, hasGoal || hasExternal, units, sessionMetric,
            zone, syntax, onToggleSession, sessionToggleLabel, controls
        )
        if (hasExternal) {
            addExternalSteps(resources, externalSteps, trailingComma = hasGoal, syntax)
        }
        if (hasGoal) {
            add(numberLine("streak_days", snapshot.streakDays.toString(), comma = false, syntax, indent = 1))
        }
        add(punctLine("}", 0, syntax))
    }

    /**
     * The day's walks. Collapsed: one inline object per session, tweather's small-
     * object style. Expanded (tap): the full detail in place — duration, distance,
     * speed OR pace (the settings decide which), cadence; a metric too short to
     * compute is absent, not invented. Auto sessions (Fase 11) wear their nature:
     * `~` on boundaries still guessed by the detector, a `"source": "auto"` line,
     * editable start/end (a terminal prompt in hunk-range syntax). Every session
     * of the working tree carries `[rm]` with a two-tap confirm — history is the
     * log's business and stays read-only.
     */
    private fun MutableList<CanvasLine>.addSessions(
        resources: Resources,
        sessions: List<SessionItem>,
        expandedIds: Set<Long>,
        trailingComma: Boolean,
        units: UnitsSystem,
        sessionMetric: SessionMetric,
        zone: ZoneId,
        syntax: SyntaxColors,
        onToggle: (Long) -> Unit,
        toggleLabel: (String) -> String,
        controls: SessionControls
    ) {
        if (sessions.isEmpty()) {
            add(rawValueLine("sessions", "[]", comma = trailingComma, syntax, indent = 1))
            return
        }
        add(keyOpen("sessions", syntax, indent = 1, bracket = "["))
        sessions.forEachIndexed { index, session ->
            val comma = index != sessions.lastIndex
            val startShown = UnitFormat.clockTime(session.startMillis, zone, session.startApprox)
            val endShown = UnitFormat.clockTime(session.endMillis, zone, session.endApprox)
            val label = toggleLabel(UnitFormat.clockTime(session.startMillis, zone))
            if (session.id !in expandedIds) {
                add(
                    CodeLine(
                        text = buildAnnotatedString {
                            appendPunct("{ ", syntax)
                            appendPair("time", quoted = startShown, syntax = syntax); appendPunct(", ", syntax)
                            appendPair("type", quoted = session.type, syntax = syntax); appendPunct(", ", syntax)
                            appendPair("min", number = session.activeMinutes.toString(), syntax = syntax)
                            appendPunct(", ", syntax)
                            appendPair("steps", number = session.steps.toString(), syntax = syntax)
                            appendPunct(", ", syntax)
                            appendPair(
                                UnitFormat.distanceLabel(units),
                                number = UnitFormat.distanceValue(session.distanceMeters, units),
                                syntax = syntax
                            )
                            appendPunct(" }", syntax)
                            if (comma) appendPunct(",", syntax)
                        },
                        indent = 2,
                        onClick = { onToggle(session.id) },
                        onClickLabel = label
                    )
                )
                return@forEachIndexed
            }
            add(
                CodeLine(
                    text = buildAnnotatedString { appendPunct("{", syntax) },
                    indent = 2,
                    onClick = { onToggle(session.id) },
                    onClickLabel = label
                )
            )
            if (controls.editingId == session.id) {
                addBoundsPrompt(session, syntax, controls)
            } else {
                add(
                    stringLine(
                        "start", startShown, comma = true, syntax, indent = 3,
                        hint = ("// " + resources.getString(R.string.note_tap_edit)).takeIf { session.auto },
                        onClick = { controls.onStartEdit(session) }.takeIf { session.auto },
                        onClickLabel = controls.editLabel(session).takeIf { session.auto }
                    )
                )
                add(
                    stringLine(
                        "end", endShown, comma = true, syntax, indent = 3,
                        onClick = { controls.onStartEdit(session) }.takeIf { session.auto },
                        onClickLabel = controls.editLabel(session).takeIf { session.auto }
                    )
                )
            }
            add(stringLine("type", session.type, comma = true, syntax, indent = 3))
            if (session.auto) {
                add(
                    stringLine(
                        "source", "auto", comma = true, syntax, indent = 3,
                        hint = "// " + resources.getString(R.string.note_inferred)
                    )
                )
            }
            add(numberLine("active_min", session.activeMinutes.toString(), comma = true, syntax, indent = 3))
            add(numberLine("steps", session.steps.toString(), comma = true, syntax, indent = 3))
            val metricLine = metricLine(session, units, sessionMetric, syntax)
            val hasCadence = session.avgCadenceSpm != null
            add(
                numberLine(
                    UnitFormat.distanceKey(units),
                    UnitFormat.distanceValue(session.distanceMeters, units),
                    comma = metricLine != null || hasCadence, syntax, indent = 3
                )
            )
            metricLine?.let { add(it.first(comma = hasCadence, syntax = syntax)) }
            session.avgCadenceSpm?.let {
                add(numberLine("avg_cadence_spm", it.toString(), comma = false, syntax, indent = 3))
            }
            addRemoveLine(resources, session, syntax, controls)
            add(punctLine(if (comma) "}," else "}", 2, syntax))
        }
        add(punctLine(if (trailingComma) "]," else "]", 1, syntax))
    }

    /**
     * `[rm]` — the working tree's delete verb, two-tap like every destructive
     * command of the series: first tap arms it (confirm hint in deletion red),
     * second tap removes. Rendered inside the expanded object, where the user is
     * already looking at what they are about to remove.
     */
    private fun MutableList<CanvasLine>.addRemoveLine(
        resources: Resources,
        session: SessionItem,
        syntax: SyntaxColors,
        controls: SessionControls
    ) {
        val armed = controls.armedRemoveId == session.id
        add(
            CodeLine(
                text = buildAnnotatedString {
                    withStyle(
                        SpanStyle(color = if (armed) syntax.diffDel else syntax.comment)
                    ) { append("[rm]") }
                    if (armed) {
                        withStyle(SpanStyle(color = syntax.diffDel)) {
                            append("  // " + resources.getString(R.string.note_tap_again_remove))
                        }
                    }
                },
                indent = 3,
                onClick = { controls.onRemove(session) },
                onClickLabel = controls.removeLabel(session)
            )
        )
    }

    /**
     * The boundary editor: start and end collapse into one terminal prompt that
     * speaks the hunk header's own range syntax (`> 09:32..10:18`), with `[esc]`
     * to cancel and a transient `// ERROR:` line when the submit doesn't parse
     * — the settings file's numeric-input pattern, retold for a time range. A
     * bare `>` prompt instead of a key label: at this nesting depth a label
     * would push `[esc]` off narrow screens, and the value speaks for itself.
     */
    private fun MutableList<CanvasLine>.addBoundsPrompt(
        session: SessionItem,
        syntax: SyntaxColors,
        controls: SessionControls
    ) {
        add(
            WidgetLine(
                indent = 3,
                measureText = "> 00:00..00:00  [esc]  slack"
            ) {
                val focusRequester = remember { FocusRequester() }
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(132.dp)) {
                        TerminalInput(
                            value = controls.editValue,
                            onValueChange = { text ->
                                controls.onEditValue(
                                    text.filter { it.isDigit() || it == ':' || it == '.' }.take(12)
                                )
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { controls.onSubmitEdit() }),
                            modifier = Modifier.focusRequester(focusRequester)
                        )
                    }
                    Text(
                        text = "[esc]",
                        style = MaterialTheme.typography.bodySmall,
                        color = syntax.comment,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = controls.cancelLabel
                            ) { controls.onCancelEdit() }
                    )
                }
            }
        )
        controls.editError?.let { error ->
            add(CodeLine(AnnotatedString(error, SpanStyle(color = syntax.diffDel)), indent = 3))
        }
    }

    /** Deferred line so the comma can depend on what follows it. */
    private class PendingLine(private val build: (Boolean, SyntaxColors) -> CodeLine) {
        fun first(comma: Boolean, syntax: SyntaxColors) = build(comma, syntax)
    }

    /** Speed or pace, one of the two (VISION §5); null when too short to compute. */
    private fun metricLine(
        session: SessionItem,
        units: UnitsSystem,
        metric: SessionMetric,
        syntax: SyntaxColors
    ): PendingLine? = when (metric) {
        SessionMetric.SPEED ->
            SessionMetrics.avgSpeedKmh(session.distanceMeters, session.activeMillis)?.let { kmh ->
                val key = if (units == UnitsSystem.METRIC) "avg_speed_kmh" else "avg_speed_mph"
                PendingLine { comma, s ->
                    numberLine(key, UnitFormat.speedValue(kmh, units), comma, s, indent = 3)
                }
            }
        SessionMetric.PACE ->
            SessionMetrics.pacePerUnit(
                session.distanceMeters, session.activeMillis, UnitFormat.unitMeters(units)
            )?.let { pace ->
                val key = if (units == UnitsSystem.METRIC) "avg_pace_min_km" else "avg_pace_min_mi"
                PendingLine { comma, s -> stringLine(key, pace, comma, s, indent = 3) }
            }
    }

    /**
     * Fase 12: what other apps counted today, read from Health Connect. Grouped
     * per origin and NEVER summed — two apps may have watched the same walk, so
     * adding them would double-count; showing them side by side cannot. The
     * block exists only while sync is on and something external was read.
     */
    private fun MutableList<CanvasLine>.addExternalSteps(
        resources: Resources,
        origins: List<OriginSteps>,
        trailingComma: Boolean,
        syntax: SyntaxColors
    ) {
        add(
            CodeLine(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = syntax.key)) { append("\"health_connect\"") }
                    withStyle(SpanStyle(color = syntax.comment)) { append(": {") }
                    withStyle(SpanStyle(color = syntax.comment.copy(alpha = 0.6f))) {
                        append("  // " + resources.getString(R.string.note_external_steps))
                    }
                },
                indent = 1
            )
        )
        origins.forEachIndexed { index, origin ->
            add(
                numberLine(
                    origin.label, origin.steps.toString(),
                    comma = index != origins.lastIndex, syntax, indent = 2
                )
            )
        }
        add(punctLine(if (trailingComma) "}," else "}", 1, syntax))
    }

    /** The honest empty file: a date and an explicit null, never a fake zero. */
    private fun emptyDocument(date: LocalDate?, syntax: SyntaxColors): List<CodeLine> = buildList {
        add(punctLine("{", 0, syntax))
        add(stringLine("date", (date ?: LocalDate.now()).toString(), comma = true, syntax, indent = 1))
        add(rawValueLine("steps", "null", comma = false, syntax, indent = 1))
        add(punctLine("}", 0, syntax))
    }

    /** `$ tsteps grant activity-recognition` — the tappable way out of the error. */
    private fun grantCommandLine(
        syntax: SyntaxColors,
        onClick: (() -> Unit)?,
        clickLabel: String?
    ): CodeLine = CodeLine(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = syntax.comment)) { append("$ ") }
            withStyle(SpanStyle(color = syntax.diffAdd)) { append("tsteps grant activity-recognition") }
        },
        indent = 0,
        onClick = onClick,
        onClickLabel = clickLabel
    )

    private fun blank() = CodeLine(buildAnnotatedString { })

    // --- line builders -----------------------------------------------------
    // Local, not in JsonSyntax: this document mixes value colors and hints in
    // ways the generic builder deliberately doesn't support.

    private fun keyOpen(key: String, syntax: SyntaxColors, indent: Int, bracket: String = "{") =
        CodeLine(
            buildAnnotatedString {
                withStyle(SpanStyle(color = syntax.key)) { append("\"$key\"") }
                withStyle(SpanStyle(color = syntax.comment)) { append(": $bracket") }
            },
            indent
        )

    private fun stringLine(
        key: String,
        value: String,
        comma: Boolean,
        syntax: SyntaxColors,
        indent: Int,
        hint: String? = null,
        onClick: (() -> Unit)? = null,
        onClickLabel: String? = null
    ) = valueLine(key, "\"$value\"", syntax.string, comma, syntax, indent, hint, onClick, onClickLabel)

    private fun numberLine(
        key: String,
        value: String,
        comma: Boolean,
        syntax: SyntaxColors,
        indent: Int,
        hint: String? = null
    ) = valueLine(key, value, syntax.number, comma, syntax, indent, hint)

    /** Value rendered as punctuation (`[]`, `null`). */
    private fun rawValueLine(
        key: String,
        value: String,
        comma: Boolean,
        syntax: SyntaxColors,
        indent: Int,
        hint: String? = null,
        onClick: (() -> Unit)? = null,
        onClickLabel: String? = null
    ) = valueLine(key, value, syntax.comment, comma, syntax, indent, hint, onClick, onClickLabel)

    private fun valueLine(
        key: String,
        renderedValue: String,
        valueColor: androidx.compose.ui.graphics.Color,
        comma: Boolean,
        syntax: SyntaxColors,
        indent: Int,
        hint: String?,
        onClick: (() -> Unit)? = null,
        onClickLabel: String? = null
    ) = CodeLine(
        buildAnnotatedString {
            withStyle(SpanStyle(color = syntax.key)) { append("\"$key\"") }
            withStyle(SpanStyle(color = syntax.comment)) { append(": ") }
            withStyle(SpanStyle(color = valueColor)) { append(renderedValue) }
            if (comma) withStyle(SpanStyle(color = syntax.comment)) { append(",") }
            if (hint != null) {
                withStyle(SpanStyle(color = syntax.comment.copy(alpha = 0.6f))) {
                    append("  $hint")
                }
            }
        },
        indent,
        onClick = onClick,
        onClickLabel = onClickLabel
    )

    private fun androidx.compose.ui.text.AnnotatedString.Builder.appendPair(
        key: String,
        quoted: String? = null,
        number: String? = null,
        syntax: SyntaxColors
    ) {
        withStyle(SpanStyle(color = syntax.key)) { append("\"$key\"") }
        appendPunct(": ", syntax)
        if (quoted != null) {
            withStyle(SpanStyle(color = syntax.string)) { append("\"$quoted\"") }
        } else if (number != null) {
            withStyle(SpanStyle(color = syntax.number)) { append(number) }
        }
    }

    private fun androidx.compose.ui.text.AnnotatedString.Builder.appendPunct(
        text: String,
        syntax: SyntaxColors
    ) {
        withStyle(SpanStyle(color = syntax.comment)) { append(text) }
    }
}
