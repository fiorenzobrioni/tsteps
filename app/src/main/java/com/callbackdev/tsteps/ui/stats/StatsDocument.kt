package com.callbackdev.tsteps.ui.stats

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.domain.HeatmapGrid
import com.callbackdev.tsteps.domain.Records
import com.callbackdev.tsteps.domain.SessionItem
import com.callbackdev.tsteps.domain.WindowAverages
import com.callbackdev.tsteps.ui.components.CanvasLine
import com.callbackdev.tsteps.ui.components.CodeLine
import com.callbackdev.tsteps.ui.components.buildMarkdownLines
import com.callbackdev.tsteps.ui.components.commentLine
import com.callbackdev.tsteps.ui.format.TableAlign
import com.callbackdev.tsteps.ui.format.TableCell
import com.callbackdev.tsteps.ui.format.TableColumn
import com.callbackdev.tsteps.ui.format.UnitFormat
import com.callbackdev.tsteps.ui.format.markdownTable
import com.callbackdev.tsteps.ui.theme.SyntaxColors
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** The `## streak` section — absent entirely when no goal is set (no guilt). */
data class StreakInfo(val current: Int, val longest: Int)

/**
 * The `stats.md` document: the movement contribution graph plus streaks,
 * averages and record tags — rendered as highlighted markdown SOURCE like every
 * file in the series. Static sections ride [buildMarkdownLines]; the heatmap
 * rows are hand-colored (a markdown tokenizer has no notion of intensity), and
 * the tag rows take an onClick that jumps the Log to their commit.
 */
object StatsDocument {

    /** Intensity alphas for levels 1..4 over the diff-add green; level 0 is `·`. */
    private val LevelAlphas = listOf(0.30f, 0.55f, 0.78f, 1f)

    fun build(
        grid: HeatmapGrid?,
        streak: StreakInfo?,
        averages: List<WindowAverages>,
        bestDay: Pair<LocalDate, Long>?,
        longestWalk: SessionItem?,
        bestWeek: Records.BestWeek?,
        committedDays: Int,
        units: UnitsSystem,
        locale: Locale,
        zone: java.time.ZoneId,
        syntax: SyntaxColors,
        onOpenCommit: (LocalDate) -> Unit = {},
        openCommitLabel: (String) -> String = { it }
    ): List<CanvasLine> = buildList {
        addAll(md("# stats.md", "", syntax = syntax))

        if (grid != null) {
            addAll(md("## contributions (last ${grid.weeks.size} weeks)", "", syntax = syntax))
            addAll(heatmapLines(grid, locale, syntax))
            add(blank())
        }

        if (committedDays == 0) {
            add(commentLine("// nothing committed yet — records and averages", syntax))
            add(commentLine("// appear with the first day's commit", syntax))
            return@buildList
        }

        if (streak != null) {
            addAll(md("## streak", "", syntax = syntax))
            addAll(
                md(
                    "current: **${streak.current} days** · longest: **${streak.longest} days**",
                    "",
                    syntax = syntax
                )
            )
        }

        if (averages.isNotEmpty()) {
            val numbers = NumberFormat.getIntegerInstance(locale)
            addAll(md("## averages", "", syntax = syntax))
            val rows = markdownTable(
                columns = listOf(
                    TableColumn("window"),
                    TableColumn("steps", TableAlign.RIGHT),
                    TableColumn("distance", TableAlign.RIGHT),
                    TableColumn("active", TableAlign.RIGHT)
                ),
                rows = averages.map { avg ->
                    listOf(
                        TableCell("${avg.windowDays}d"),
                        TableCell(numbers.format(avg.avgSteps)),
                        TableCell(UnitFormat.distance(avg.avgDistanceMeters, units)),
                        TableCell("${avg.avgActiveMinutes} min")
                    )
                }
            )
            addAll(buildMarkdownLines(rows, syntax))
            add(blank())
        }

        val tags = tagRows(bestDay, longestWalk, bestWeek, units, locale, zone)
        if (tags.isNotEmpty()) {
            addAll(md("## tags", "", syntax = syntax))
            val rendered = markdownTable(
                columns = listOf(
                    TableColumn("tag"),
                    TableColumn("value", TableAlign.RIGHT),
                    TableColumn("date")
                ),
                rows = tags.map { it.cells.map { cell -> TableCell(cell) } }
            )
            buildMarkdownLines(rendered, syntax).forEachIndexed { index, line ->
                // Header and separator first, then one line per tag row.
                val tag = tags.getOrNull(index - 2)
                if (tag?.commitDate != null) {
                    add(
                        line.copy(
                            onClick = { onOpenCommit(tag.commitDate) },
                            onClickLabel = openCommitLabel(tag.commitDate.toString())
                        )
                    )
                } else {
                    add(line)
                }
            }
            add(blank())
        }

        addAll(
            md("*computed on read from $committedDays committed days*", syntax = syntax)
        )
    }

    // --- heatmap -------------------------------------------------------------

    /**
     * Seven Monday-first rows of 2-char cells (`■ `), labels on mon/wed/fri/sun,
     * a month-label row underneath. Intensity = diff-add green at the level's
     * alpha; a zero day is a dim `·`; a day that hasn't happened yet is blank.
     */
    private fun heatmapLines(
        grid: HeatmapGrid,
        locale: Locale,
        syntax: SyntaxColors
    ): List<CodeLine> = buildList {
        val labelWidth = 5 // "mon  "
        (0..6).forEach { dayIndex ->
            val showLabel = dayIndex % 2 == 0 // mon/wed/fri/sun
            val label = if (showLabel) {
                DayOfWeek.of(dayIndex + 1).getDisplayName(TextStyle.SHORT, locale)
                    .lowercase(locale).take(3).padEnd(labelWidth)
            } else {
                " ".repeat(labelWidth)
            }
            add(
                CodeLine(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = syntax.comment.copy(alpha = 0.6f))) {
                            append(label)
                        }
                        grid.weeks.forEach { week ->
                            val cell = week.cells[dayIndex]
                            when {
                                cell.steps == null ->
                                    append("  ")
                                cell.level == 0 ->
                                    withStyle(SpanStyle(color = syntax.comment.copy(alpha = 0.45f))) {
                                        append("· ")
                                    }
                                else ->
                                    withStyle(
                                        SpanStyle(
                                            color = syntax.diffAdd.copy(
                                                alpha = LevelAlphas[cell.level - 1]
                                            )
                                        )
                                    ) { append("■ ") }
                            }
                        }
                    }
                )
            )
        }
        // Month labels under the columns that start one, never overlapping.
        val width = labelWidth + grid.weeks.size * 2
        val labelRow = CharArray(width) { ' ' }
        var lastEnd = 0
        grid.weeks.forEachIndexed { index, week ->
            if (!week.monthStart) return@forEachIndexed
            val start = labelWidth + index * 2
            val label = week.monthLabel.take(3)
            if (start >= lastEnd && start + label.length <= width) {
                label.forEachIndexed { i, c -> labelRow[start + i] = c }
                lastEnd = start + label.length + 1
            }
        }
        add(
            CodeLine(
                AnnotatedString(
                    String(labelRow).trimEnd(),
                    SpanStyle(color = syntax.comment.copy(alpha = 0.6f))
                )
            )
        )
    }

    // --- tables ---------------------------------------------------------------

    private class TagRow(val cells: List<String>, val commitDate: LocalDate?)

    private fun tagRows(
        bestDay: Pair<LocalDate, Long>?,
        longestWalk: SessionItem?,
        bestWeek: Records.BestWeek?,
        units: UnitsSystem,
        locale: Locale,
        zone: java.time.ZoneId
    ): List<TagRow> = buildList {
        val numbers = NumberFormat.getIntegerInstance(locale)
        bestDay?.let { (date, steps) ->
            add(TagRow(listOf("best-day", "${numbers.format(steps)} steps", date.toString()), date))
        }
        longestWalk?.let { walk ->
            val date = java.time.Instant.ofEpochMilli(walk.startMillis).atZone(zone).toLocalDate()
            add(
                TagRow(
                    listOf(
                        "longest-walk",
                        "${walk.activeMinutes} min · ${UnitFormat.distance(walk.distanceMeters, units)}",
                        date.toString()
                    ),
                    date
                )
            )
        }
        bestWeek?.let { week ->
            add(
                TagRow(
                    listOf("best-week", "${numbers.format(week.steps)} steps", "week ${week.week}"),
                    commitDate = null
                )
            )
        }
    }

    private fun md(vararg lines: String, syntax: SyntaxColors): List<CodeLine> =
        buildMarkdownLines(lines.toList(), syntax)

    private fun blank() = CodeLine(AnnotatedString(""))
}
