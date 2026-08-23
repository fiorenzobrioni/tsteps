package com.callbackdev.tsteps.ui.steps

import android.content.res.Resources
import com.callbackdev.tsteps.R
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.domain.DayStats
import com.callbackdev.tsteps.domain.SessionItem
import com.callbackdev.tsteps.ui.format.TableAlign
import com.callbackdev.tsteps.ui.format.TableCell
import com.callbackdev.tsteps.ui.format.TableColumn
import com.callbackdev.tsteps.ui.format.UnitFormat
import com.callbackdev.tsteps.ui.format.markdownTable
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The day's `README.md` — the repo metaphor at its deepest (tweather Fase 10):
 * a real repo's README is the HUMAN summary of the machine content, so this is
 * the day in prose while `steps_data.json` stays the full data source.
 *
 * FULLY localized, headings included — a README is prose, not code, so the
 * keys-stay-English rule doesn't apply. `## Status` is the day's build badge:
 * goal progress in neutral words (never guilt), sensor problems as `>`
 * blockquote warnings. Tables are [markdownTable]s: columns padded to their
 * widest cell, numbers right-aligned (tweather's Fase 11c convention).
 */
object StepsReadme {

    fun build(
        snapshot: TodaySnapshot?,
        status: SensorStatus,
        sessions: List<SessionItem>,
        history: List<DayStats>,
        units: UnitsSystem,
        zone: ZoneId,
        locale: Locale,
        resources: Resources
    ): List<String> = buildList {
        fun s(id: Int, vararg args: Any): String = resources.getString(id, *args)
        val numbers = NumberFormat.getIntegerInstance(locale)
        val today = snapshot?.date ?: LocalDate.now(zone)

        add("# ${title(today, locale)}")

        if (snapshot != null && status == SensorStatus.OK) {
            add("")
            add("## ${s(R.string.readme_h_today)}")
            add(
                s(
                    R.string.readme_summary,
                    numbers.format(snapshot.steps),
                    UnitFormat.distance(snapshot.distanceMeters, units),
                    snapshot.activeMinutes
                )
            )
            snapshot.activeKcal?.let { kcal ->
                add(s(R.string.readme_kcal, numbers.format(kcal.toInt())))
            }
        }

        add("")
        add("## ${s(R.string.readme_h_status)}")
        when (status) {
            SensorStatus.NO_SENSOR -> add("> ${s(R.string.readme_warn_no_sensor)}")
            SensorStatus.NO_PERMISSION -> add("> ${s(R.string.readme_warn_no_permission)}")
            SensorStatus.OK -> if (snapshot != null) {
                val goal = snapshot.goalSteps
                if (goal > 0) {
                    // Literally the number the JSON's `check` bar draws, from
                    // the same function: the README is the human summary of the
                    // machine file, so the two can never disagree on it.
                    val percent = StepsGlyphs.goalPercent(snapshot.steps, goal)
                    if (snapshot.steps >= goal) {
                        add(
                            s(
                                R.string.readme_goal_reached,
                                numbers.format(snapshot.steps),
                                numbers.format(goal),
                                percent
                            )
                        )
                    } else {
                        add(
                            s(
                                R.string.readme_goal_progress,
                                numbers.format(snapshot.steps),
                                numbers.format(goal),
                                percent,
                                numbers.format(goal - snapshot.steps)
                            )
                        )
                    }
                    add(s(R.string.readme_streak, snapshot.streakDays))
                } else {
                    add(s(R.string.readme_goal_none))
                }
            }
        }

        if (sessions.isNotEmpty()) {
            add("")
            add("## ${s(R.string.readme_h_walks)}")
            addAll(
                markdownTable(
                    columns = listOf(
                        TableColumn(s(R.string.readme_t_start)),
                        TableColumn(s(R.string.readme_t_min), TableAlign.RIGHT),
                        TableColumn(s(R.string.readme_t_steps), TableAlign.RIGHT),
                        TableColumn(s(R.string.readme_t_distance), TableAlign.RIGHT)
                    ),
                    rows = sessions.map { session ->
                        listOf(
                            TableCell(
                                UnitFormat.clockTime(
                                    session.startMillis, zone, session.startApprox
                                )
                            ),
                            TableCell(session.activeMinutes.toString()),
                            TableCell(numbers.format(session.steps)),
                            TableCell(UnitFormat.distance(session.distanceMeters, units))
                        )
                    }
                )
            )
        }

        add("")
        add("## ${s(R.string.readme_h_week)}")
        val byDate = history.associateBy { it.date }
        val week = (6 downTo 0).map { back -> today.minusDays(back.toLong()) }
        addAll(
            markdownTable(
                columns = listOf(
                    TableColumn(s(R.string.readme_t_day)),
                    TableColumn(s(R.string.readme_t_steps), TableAlign.RIGHT),
                    TableColumn(s(R.string.readme_t_distance), TableAlign.RIGHT),
                    TableColumn(s(R.string.readme_t_active), TableAlign.RIGHT)
                ),
                rows = week.map { date ->
                    val name = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                        .lowercase(locale).take(3)
                    when {
                        // Today rides the table live from the working tree, in bold.
                        date == today && snapshot != null && status == SensorStatus.OK ->
                            listOf(
                                TableCell("**$name**"),
                                TableCell(numbers.format(snapshot.steps)),
                                TableCell(UnitFormat.distance(snapshot.distanceMeters, units)),
                                TableCell("${snapshot.activeMinutes} min")
                            )
                        byDate[date] != null -> byDate.getValue(date).let { day ->
                            listOf(
                                TableCell(name),
                                TableCell(numbers.format(day.steps)),
                                TableCell(UnitFormat.distance(day.distanceMeters, units)),
                                TableCell("${day.activeMinutes} min")
                            )
                        }
                        // An untracked day is missing data, not a zero.
                        else -> listOf(
                            TableCell(name),
                            TableCell("—"),
                            TableCell("—"),
                            TableCell("—")
                        )
                    }
                }
            )
        )

        // Totals under the table, not a row in it: a "**Total**" cell would
        // widen the day column on every line, and this shape mirrors the
        // `## Today` summary the reader has already met at the top. Days with
        // no data contribute nothing — they are missing, never zeros.
        val weekDays = week.mapNotNull { date ->
            when {
                date == today && snapshot != null && status == SensorStatus.OK ->
                    Triple(
                        snapshot.steps, snapshot.distanceMeters, snapshot.activeMinutes
                    )
                else -> byDate[date]?.let { Triple(it.steps, it.distanceMeters, it.activeMinutes) }
            }
        }
        if (weekDays.isNotEmpty()) {
            add("")
            add(
                s(
                    R.string.readme_week_total,
                    numbers.format(weekDays.sumOf { it.first }),
                    UnitFormat.distance(weekDays.sumOf { it.second }, units),
                    weekDays.sumOf { it.third }
                )
            )
        }

        add("")
        add("*${s(R.string.readme_footer, history.size)}*")
    }

    /** `Tuesday 18 August 2026`, capitalized — prose, so fully localized. */
    private fun title(date: LocalDate, locale: Locale): String =
        date.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", locale))
            .replaceFirstChar { it.titlecase(locale) }
}
