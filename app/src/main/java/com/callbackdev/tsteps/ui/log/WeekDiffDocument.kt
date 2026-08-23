package com.callbackdev.tsteps.ui.log

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.domain.GoalCheckResult
import com.callbackdev.tsteps.domain.WeekComparison
import com.callbackdev.tsteps.domain.WeekDiff
import com.callbackdev.tsteps.domain.WeekSide
import com.callbackdev.tsteps.ui.components.CanvasLine
import com.callbackdev.tsteps.ui.components.CodeLine
import com.callbackdev.tsteps.ui.components.commentLine
import com.callbackdev.tsteps.ui.format.UnitFormat
import com.callbackdev.tsteps.ui.theme.SyntaxColors
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * `week.diff` — the Log screen's second file, and the last row of VISION §2's
 * metaphor table to get a screen of its own: `git diff last_week`.
 *
 * Shaped like a real unified diff and read the same way. The `---`/`+++` headers
 * name the two ISO weeks with their calendar range and, crucially, how many of
 * their seven days have data; each metric is a hunk whose header carries the
 * change (git's own `@@ … @@ context` slot) over the old and new values.
 *
 * Three rules keep it from lying:
 *
 * - **The previous side is the week immediately before**, empty or not (see
 *   [WeekDiff]); an empty one renders as a stated absence, never as a zero.
 * - **A week in progress says so** in the comment channel and in its day count.
 *   Nothing is pro-rated: the totals are the totals, and the reader is told what
 *   they cover.
 * - **Only steps get a percentage.** Distance and active minutes are both linear
 *   functions of steps, so their percentages would repeat the steps figure to
 *   within a rounding — three identical-looking numbers implying three
 *   measurements. The absolute delta is the part that carries information.
 */
object WeekDiffDocument {

    fun build(
        comparison: WeekComparison?,
        units: UnitsSystem,
        locale: Locale,
        syntax: SyntaxColors
    ): List<CanvasLine> = buildList {
        add(commandLine(syntax))
        add(blank())

        if (comparison == null || !comparison.previous.hasData) {
            val week = comparison?.previous?.week
            add(
                commentLine(
                    if (week != null) {
                        "// nothing to compare: week $week has no commits"
                    } else {
                        "// nothing to compare: no history yet"
                    },
                    syntax
                )
            )
            add(commentLine("// two consecutive weeks are needed", syntax))
            return@buildList
        }

        val numbers = NumberFormat.getIntegerInstance(locale)
        val (previous, current) = comparison
        add(header("---", "a", previous, locale, syntax.diffDel, syntax))
        add(header("+++", "b", current, locale, syntax.diffAdd, syntax))

        if (!current.isComplete) {
            add(blank())
            add(
                commentLine(
                    "// week ${current.week} in progress: " +
                        "${current.daysWithData} of ${WeekDiff.DAYS_IN_WEEK} days",
                    syntax
                )
            )
        }

        addMetric(
            key = "steps",
            old = numbers.format(previous.steps),
            new = numbers.format(current.steps),
            delta = numbers.format(abs(current.steps - previous.steps)),
            up = current.steps >= previous.steps,
            percent = percent(previous.steps.toDouble(), current.steps.toDouble()),
            syntax = syntax
        )
        addMetric(
            key = UnitFormat.distanceKey(units),
            old = UnitFormat.distanceValue(previous.distanceMeters, units),
            new = UnitFormat.distanceValue(current.distanceMeters, units),
            delta = UnitFormat.distanceValue(
                abs(current.distanceMeters - previous.distanceMeters), units
            ),
            up = current.distanceMeters >= previous.distanceMeters,
            syntax = syntax
        )
        addMetric(
            key = "active_min",
            old = previous.activeMinutes.toString(),
            new = current.activeMinutes.toString(),
            delta = abs(current.activeMinutes - previous.activeMinutes).toString(),
            up = current.activeMinutes >= previous.activeMinutes,
            syntax = syntax
        )
        addMetric(
            key = "walks",
            old = previous.walks.toString(),
            new = current.walks.toString(),
            delta = abs(current.walks - previous.walks).toString(),
            up = current.walks >= previous.walks,
            syntax = syntax
        )
        // The check row exists only if a check ever ran in either week: a goal
        // nobody set produces no row, here as everywhere else.
        if (previous.checksRun > 0 || current.checksRun > 0) {
            addMetric(
                key = "goal_checks",
                old = checkColumn(previous),
                new = checkColumn(current),
                // No check has run this week yet, so nothing has been lost:
                // a `-7` here would claim seven failures that never happened.
                delta = if (current.checksRun == 0) {
                    null
                } else {
                    abs(current.checksPassed - previous.checksPassed).toString()
                },
                up = current.checksPassed >= previous.checksPassed,
                syntax = syntax
            )
        }
    }

    /** `$ git diff @{last.week}` — the file is the output of a command. */
    private fun commandLine(syntax: SyntaxColors): CodeLine = CodeLine(
        buildAnnotatedString {
            withStyle(SpanStyle(color = syntax.comment)) { append("$ ") }
            withStyle(SpanStyle(color = syntax.diffAdd)) { append("git diff @{last.week}") }
        }
    )

    /** `--- a/week 33   aug 10..16   7/7 days` */
    private fun header(
        marker: String,
        side: String,
        week: WeekSide,
        locale: Locale,
        markerColor: androidx.compose.ui.graphics.Color,
        syntax: SyntaxColors
    ): CodeLine = CodeLine(
        buildAnnotatedString {
            withStyle(SpanStyle(color = markerColor)) { append("$marker $side/week ${week.week}") }
            withStyle(SpanStyle(color = syntax.comment.copy(alpha = 0.6f))) {
                append("   ${range(week.from, week.to, locale)}")
                append("   ${week.daysWithData}/${WeekDiff.DAYS_IN_WEEK} days")
            }
        }
    )

    /**
     * One metric as a hunk: the change in the header's context slot (where git
     * puts the enclosing function), the two values as the removed and added
     * lines. The delta is what the reader came for, so it sits where the eye
     * lands first rather than trailing a value line.
     */
    private fun MutableList<CanvasLine>.addMetric(
        key: String,
        old: String,
        new: String,
        delta: String?,
        up: Boolean,
        percent: String? = null,
        syntax: SyntaxColors
    ) {
        add(blank())
        add(
            CodeLine(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = syntax.comment)) {
                        append(if (delta == null) "@@ $key @@" else "@@ $key @@  ")
                    }
                    if (delta == null) return@buildAnnotatedString
                    withStyle(SpanStyle(color = if (up) syntax.diffAdd else syntax.diffDel)) {
                        append((if (up) "+" else "-") + delta)
                        if (percent != null) append("  $percent")
                    }
                }
            )
        )
        add(CodeLine(AnnotatedString("- $old", SpanStyle(color = syntax.diffDel))))
        add(CodeLine(AnnotatedString("+ $new", SpanStyle(color = syntax.diffAdd))))
    }

    /**
     * `✓✓✗✓✓✗✓  5/7` — the week's checks as the CI column they are. A week where
     * no check has run yet gets the glyphs alone: `0/0` is the honest ratio of
     * two real counts and still reads like a division bug, and the `·` glyphs
     * have already said the same thing.
     */
    private fun checkColumn(week: WeekSide): String {
        val glyphs = week.checks.joinToString("") { check ->
            when (check) {
                GoalCheckResult.PASSED -> "✓"
                GoalCheckResult.FAILED -> "✗"
                GoalCheckResult.SKIPPED -> "·"
            }
        }
        if (week.checksRun == 0) return glyphs
        return "$glyphs  ${week.checksPassed}/${week.checksRun}"
    }

    /**
     * `+8.6%`, and nothing at all against a week that took no step: a percentage
     * of zero is not infinite, it is undefined, and the absolute delta already
     * says everything there is to say.
     */
    private fun percent(old: Double, new: Double): String? {
        if (old <= 0.0) return null
        val change = ((new - old) / old * 100).roundToInt()
        return "${if (change >= 0) "+" else "-"}${abs(change)}%"
    }

    /** `aug 10..16`, or `aug 31..sep 6` when the week straddles two months. */
    private fun range(from: LocalDate, to: LocalDate, locale: Locale): String {
        val month = { date: LocalDate ->
            date.month.getDisplayName(TextStyle.SHORT, locale).lowercase(locale).take(3)
        }
        return if (from.month == to.month) {
            "${month(from)} ${from.dayOfMonth}..${to.dayOfMonth}"
        } else {
            "${month(from)} ${from.dayOfMonth}..${month(to)} ${to.dayOfMonth}"
        }
    }

    private fun blank() = CodeLine(AnnotatedString(""))
}
