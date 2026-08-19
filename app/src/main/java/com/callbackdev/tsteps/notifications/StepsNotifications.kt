package com.callbackdev.tsteps.notifications

import android.content.res.Resources
import com.callbackdev.tsteps.R
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.data.local.DaySummaryEntity
import com.callbackdev.tsteps.domain.CommitHash
import com.callbackdev.tsteps.ui.format.UnitFormat
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The two notifications' content, pure and testable. Same l10n rule as every
 * screen: the TITLE is chrome (localized, emoji included tweather-style), the
 * body is terminal output (English by design).
 *
 * Two shapes per notification (device feedback): the collapsed shade gets ONE
 * compact line ([Content.summary]), the expanded shade gets the same facts one
 * per line ([Content.expanded]) — a commit reads like `git log` when you give
 * it room, and gets out of the way when you don't.
 */
object StepsNotifications {

    data class Content(
        /** Chrome, localized. */
        val title: String,
        /** Collapsed shade: one compact terminal line, never wraps on `\n`. */
        val summary: String,
        /** Expanded shade (BigTextStyle): the same facts, one per line. */
        val expanded: String
    )

    /** The closed day's commit message — what the log will show, in the shade. */
    fun dailyCommit(
        day: DaySummaryEntity,
        units: UnitsSystem,
        locale: Locale,
        resources: Resources
    ): Content {
        val numbers = NumberFormat.getIntegerInstance(locale)
        val date = LocalDate.parse(day.date)
        val hash = CommitHash.of(date)
        val distance = UnitFormat.distance(day.distanceMeters, units)

        val metrics = buildString {
            append("${numbers.format(day.steps)} steps · $distance · ${day.activeMinutes} min")
            day.activeKcal?.let { append(" · ${numbers.format(it.toInt())} kcal") }
        }
        val goalGlyph: String?
        val checkLine: String?
        if (day.goalSteps > 0 && day.goalMet != null) {
            goalGlyph = if (day.goalMet) "✓" else "✗"
            val verdict = if (day.goalMet) "passed" else "failed"
            val relation = if (day.goalMet) "≥" else "<"
            checkLine = "$goalGlyph goal check $verdict " +
                "(${numbers.format(day.steps)} $relation ${numbers.format(day.goalSteps)})"
        } else {
            goalGlyph = null
            checkLine = null
        }

        return Content(
            title = resources.getString(
                R.string.notif_title_daily_commit, "👣", numbers.format(day.steps)
            ),
            // The title already carries the steps: the summary adds what it
            // doesn't say — hash, distance, time, the verdict as a bare glyph.
            summary = listOfNotNull(
                "commit $hash", distance, "${day.activeMinutes} min", goalGlyph
            ).joinToString(" · "),
            // Expanded: the day as `git log` would print it.
            expanded = buildString {
                appendLine("commit $hash")
                appendLine("Date: ${DayName.format(date)} ${day.date}")
                appendLine(metrics)
                checkLine?.let { appendLine(it) }
                append("$ tsteps log")
            }
        )
    }

    /** Today's check just went green — once per day, edge-triggered. */
    fun goalReached(
        steps: Long,
        goalSteps: Int,
        streakDays: Int,
        locale: Locale,
        resources: Resources
    ): Content {
        val numbers = NumberFormat.getIntegerInstance(locale)
        val checkLine =
            "✓ goal check passed (${numbers.format(steps)} ≥ ${numbers.format(goalSteps)})"
        return Content(
            title = resources.getString(
                R.string.notif_title_goal, "✓", numbers.format(steps)
            ),
            summary = checkLine,
            expanded = buildString {
                appendLine(checkLine)
                if (streakDays > 1) appendLine("streak: $streakDays days")
                append("$ tsteps log --today")
            }
        )
    }

    private val DayName = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
}
