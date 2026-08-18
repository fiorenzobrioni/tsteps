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
 * BODY is terminal output (English by design) — the commit itself, the check
 * line, a command hint.
 */
object StepsNotifications {

    data class Content(val title: String, val body: String)

    /** The closed day's commit message — what the log will show, in the shade. */
    fun dailyCommit(
        day: DaySummaryEntity,
        units: UnitsSystem,
        locale: Locale,
        resources: Resources
    ): Content {
        val numbers = NumberFormat.getIntegerInstance(locale)
        val date = LocalDate.parse(day.date)
        val body = buildString {
            append("commit ${CommitHash.of(date)} (${DayName.format(date)} ${day.date})\n")
            append(numbers.format(day.steps))
            append(" steps · ${UnitFormat.distance(day.distanceMeters, units)}")
            append(" · ${day.activeMinutes} min")
            day.activeKcal?.let { append(" · ${numbers.format(it.toInt())} kcal") }
            if (day.goalSteps > 0 && day.goalMet != null) {
                val (glyph, verdict, relation) =
                    if (day.goalMet) Triple("✓", "passed", "≥") else Triple("✗", "failed", "<")
                append(
                    "\n$glyph goal check $verdict " +
                        "(${numbers.format(day.steps)} $relation ${numbers.format(day.goalSteps)})"
                )
            }
        }
        return Content(
            title = resources.getString(
                R.string.notif_title_daily_commit, "👣", numbers.format(day.steps)
            ),
            body = body
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
        val body = buildString {
            append("✓ goal check passed (${numbers.format(steps)} ≥ ${numbers.format(goalSteps)})")
            if (streakDays > 1) append("\nstreak: $streakDays days")
            append("\n$ tsteps log --today")
        }
        return Content(
            title = resources.getString(
                R.string.notif_title_goal, "✓", numbers.format(steps)
            ),
            body = body
        )
    }

    private val DayName = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
}
