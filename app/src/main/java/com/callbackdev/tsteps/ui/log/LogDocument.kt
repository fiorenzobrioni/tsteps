package com.callbackdev.tsteps.ui.log

import android.content.res.Resources
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.callbackdev.tsteps.R
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.domain.CommitHash
import com.callbackdev.tsteps.domain.SessionItem
import com.callbackdev.tsteps.ui.format.UnitFormat
import com.callbackdev.tsteps.ui.components.CanvasLine
import com.callbackdev.tsteps.ui.components.CodeLine
import com.callbackdev.tsteps.ui.components.commentLine
import com.callbackdev.tsteps.ui.theme.SyntaxColors
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

/** The working tree's summary for the `# Changes not yet committed` section. */
data class UncommittedToday(
    val date: LocalDate,
    val steps: Long,
    val distanceMeters: Double,
    val activeMinutes: Int
)

/** One committed day, decoupled from the Room entity so the builder stays pure. */
data class CommitDay(
    val date: LocalDate,
    val steps: Long,
    val activeMinutes: Int,
    val distanceMeters: Double,
    val activeKcal: Double?,
    val goalSteps: Int,
    val goalMet: Boolean?
)

/**
 * The `steps_history.diff` document — the git log made real. Today sits on top as
 * uncommitted changes; every finished day is a commit (stable hash, author,
 * factual goal-check line) that expands into its diff, where steps are the added
 * lines. Week boundaries render as separators carrying that week's committed
 * total; the week-over-week comparison is `week.diff`, the screen's other file.
 */
object LogDocument {

    const val AUTHOR = "you@tsteps.app"

    fun build(
        resources: Resources,
        today: UncommittedToday?,
        days: List<CommitDay>,
        expanded: Set<LocalDate>,
        bestDay: LocalDate?,
        units: UnitsSystem,
        locale: Locale,
        syntax: SyntaxColors,
        todaySessions: List<SessionItem> = emptyList(),
        sessionsByDate: Map<LocalDate, List<SessionItem>> = emptyMap(),
        zone: ZoneId = ZoneId.systemDefault(),
        onToggle: (LocalDate) -> Unit = {},
        toggleLabel: (String) -> String = { it }
    ): List<CanvasLine> = buildList {
        val numbers = NumberFormat.getIntegerInstance(locale)
        val dayName = DateTimeFormatter.ofPattern("EEE", locale)

        if (today != null) {
            add(commentLine("# " + resources.getString(R.string.note_branch), syntax))
            add(commentLine("# " + resources.getString(R.string.note_uncommitted), syntax))
            add(
                commentLine(
                    "#   ${numbers.format(today.steps)} steps · " +
                        "${distance(today.distanceMeters, units)} · ${today.activeMinutes} min",
                    syntax
                )
            )
            // Today's walks: hunks the midnight commit will absorb.
            todaySessions.forEach { session ->
                addSessionHunk(session, units, numbers, zone, syntax)
            }
        }

        if (days.isEmpty()) {
            add(blank())
            add(commentLine("// " + resources.getString(R.string.note_no_commits), syntax))
            return@buildList
        }

        val weekFields = WeekFields.ISO
        val weekOf = { date: LocalDate ->
            date.get(weekFields.weekBasedYear()) to date.get(weekFields.weekOfWeekBasedYear())
        }
        val weekTotals: Map<Pair<Int, Int>, Long> = days.groupBy { weekOf(it.date) }
            .mapValues { (_, weekDays) -> weekDays.sumOf { it.steps } }
        var currentWeek: Pair<Int, Int>? = null
        days.forEach { day ->
            val week = weekOf(day.date)
            if (week != currentWeek) {
                currentWeek = week
                add(blank())
                add(weekSeparator(week, weekTotals, numbers, syntax))
            }
            add(blank())
            addCommit(
                day = day,
                sessions = sessionsByDate[day.date].orEmpty(),
                isBestDay = day.date == bestDay,
                isExpanded = day.date in expanded,
                units = units,
                numbers = numbers,
                dayName = dayName,
                zone = zone,
                syntax = syntax,
                onToggle = onToggle,
                toggleLabel = toggleLabel
            )
        }
    }

    /** `--- week 34 · 52,340 steps (+2,340 vs week 33) ---`, delta in diff colors. */
    /**
     * A divider between the weeks of the commit stream, carrying that week's
     * committed total and nothing more.
     *
     * It used to append a `(+2,204 vs week 33)` delta, and that delta was wrong
     * twice over (Fase 15). It compared whatever days each week happened to have
     * in the log — a Monday-only week against a two-day one — and stated the
     * result as a week-over-week change; and because the commit stream excludes
     * today, its current-week total disagreed with `week.diff`, one tab away, by
     * exactly today's steps. Two numbers for one week in the same app. The
     * comparison now lives in the file built to make it honestly, so the
     * separator went back to being a separator.
     */
    private fun weekSeparator(
        week: Pair<Int, Int>,
        totals: Map<Pair<Int, Int>, Long>,
        numbers: NumberFormat,
        syntax: SyntaxColors
    ): CodeLine = CodeLine(
        AnnotatedString(
            "--- week ${week.second} · ${numbers.format(totals.getValue(week))} steps ---",
            SpanStyle(color = syntax.comment)
        )
    )

    private fun MutableList<CanvasLine>.addCommit(
        day: CommitDay,
        sessions: List<SessionItem>,
        isBestDay: Boolean,
        isExpanded: Boolean,
        units: UnitsSystem,
        numbers: NumberFormat,
        dayName: DateTimeFormatter,
        zone: ZoneId,
        syntax: SyntaxColors,
        onToggle: (LocalDate) -> Unit,
        toggleLabel: (String) -> String
    ) {
        val hash = CommitHash.of(day.date)
        add(
            CodeLine(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = syntax.key)) { append("commit $hash") }
                    if (isBestDay) {
                        // Tags render in number-orange, git's tag yellow in our palette.
                        withStyle(SpanStyle(color = syntax.number)) { append("  (tag: best-day)") }
                    }
                },
                onClick = { onToggle(day.date) },
                onClickLabel = toggleLabel(day.date.toString())
            )
        )
        add(commentLine("Author: $AUTHOR", syntax))
        add(commentLine("Date:   ${dayName.format(day.date)} ${day.date}", syntax))
        add(blank())

        val message = buildString {
            append("${numbers.format(day.steps)} steps")
            append(" · ${distance(day.distanceMeters, units)}")
            append(" · ${day.activeMinutes} min")
            day.activeKcal?.let { append(" · ${numbers.format(it.toInt())} kcal") }
        }
        add(CodeLine(AnnotatedString("    $message")))
        if (day.goalSteps > 0 && day.goalMet != null) {
            val (glyph, verdict, color) = if (day.goalMet) {
                Triple("✓", "passed", syntax.diffAdd)
            } else {
                Triple("✗", "failed", syntax.diffDel)
            }
            val relation = if (day.goalMet) "≥" else "<"
            add(
                CodeLine(
                    AnnotatedString(
                        "    $glyph goal check $verdict " +
                            "(${numbers.format(day.steps)} $relation ${numbers.format(day.goalSteps)})",
                        SpanStyle(color = color)
                    )
                )
            )
        }

        if (!isExpanded) return
        add(blank())
        add(commentLine("--- a/steps_data.json", syntax))
        add(commentLine("+++ b/steps_data.json", syntax))
        // Hunk header in key-blue, like git's cyan.
        add(CodeLine(AnnotatedString("@@ ${day.date} @@", SpanStyle(color = syntax.key))))
        add(addedLine("\"steps\": ${day.steps}", syntax))
        add(addedLine("\"${distanceKey(units)}\": ${distanceValue(day.distanceMeters, units)}", syntax))
        add(addedLine("\"active_min\": ${day.activeMinutes}", syntax))
        day.activeKcal?.let { add(addedLine("\"active_kcal\": ${it.toInt()}", syntax)) }
        // The day's walks, each its own hunk with the time range as header.
        sessions.forEach { session -> addSessionHunk(session, units, numbers, zone, syntax) }
    }

    /**
     * `@@ 09:32..10:18 @@ walk` + one green line — a walk as a diff hunk. An
     * inferred walk says so: `@@ ~09:30..~10:15 @@ walk (auto)`, tildes on the
     * boundaries that are still the detector's guess (Fase 11).
     */
    private fun MutableList<CanvasLine>.addSessionHunk(
        session: SessionItem,
        units: UnitsSystem,
        numbers: NumberFormat,
        zone: ZoneId,
        syntax: SyntaxColors
    ) {
        val range = UnitFormat.clockTime(session.startMillis, zone, session.startApprox) + ".." +
            UnitFormat.clockTime(session.endMillis, zone, session.endApprox)
        val marker = if (session.auto) " (auto)" else ""
        add(
            CodeLine(
                AnnotatedString("@@ $range @@ ${session.type}$marker", SpanStyle(color = syntax.key))
            )
        )
        add(
            addedLine(
                "${numbers.format(session.steps)} steps · " +
                    "${UnitFormat.distance(session.distanceMeters, units)} · " +
                    "${session.activeMinutes} min",
                syntax
            )
        )
    }

    /** Whole `+` line in diff green over a faint tint; the gutter matches (tweather). */
    private fun addedLine(body: String, syntax: SyntaxColors): CodeLine = CodeLine(
        AnnotatedString(
            "+ $body",
            SpanStyle(color = syntax.diffAdd, background = syntax.diffAdd.copy(alpha = 0.12f))
        ),
        indent = 1,
        gutterColor = syntax.diffAdd
    )

    private fun distanceKey(units: UnitsSystem) =
        if (units == UnitsSystem.METRIC) "distance_km" else "distance_mi"

    private fun distanceValue(meters: Double, units: UnitsSystem): String =
        "%.1f".format(
            Locale.ROOT,
            if (units == UnitsSystem.METRIC) meters / 1_000.0 else meters / 1_609.344
        )

    private fun distance(meters: Double, units: UnitsSystem): String =
        distanceValue(meters, units) + if (units == UnitsSystem.METRIC) " km" else " mi"

    private fun blank() = CodeLine(AnnotatedString(""))
}
