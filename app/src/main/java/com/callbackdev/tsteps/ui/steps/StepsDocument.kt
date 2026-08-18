package com.callbackdev.tsteps.ui.steps

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.ui.components.CodeLine
import com.callbackdev.tsteps.ui.components.commentLine
import com.callbackdev.tsteps.ui.components.punctLine
import com.callbackdev.tsteps.ui.theme.SyntaxColors
import java.time.LocalDate
import java.util.Locale

/**
 * Everything `steps_data.json` needs to render one day. Assembled by the
 * ViewModel from Room + settings; the builders below are pure so the whole
 * document is unit-testable line by line.
 */
data class TodaySnapshot(
    val date: LocalDate,
    val steps: Long,
    /** 0 = no goal: the check lines simply don't exist (no guilt without opt-in). */
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
 * The `steps_data.json` document. Hand-built lines (not [buildJsonLines]) because
 * this file carries what generic JSON can't: trailing `//` hints on value lines,
 * a tappable `$` command in the error state, and keys that appear or vanish with
 * the data they describe.
 */
object StepsDocument {

    fun build(
        snapshot: TodaySnapshot?,
        status: SensorStatus,
        units: UnitsSystem,
        syntax: SyntaxColors,
        trackComingSoon: Boolean = false,
        onGrantPermission: (() -> Unit)? = null,
        grantClickLabel: String? = null
    ): List<CodeLine> = buildList {
        when (status) {
            SensorStatus.NO_SENSOR -> {
                add(commentLine("// E: no step sensor on this device", syntax))
                add(commentLine("// passive counting is unavailable", syntax))
                add(blank())
                addAll(emptyDocument(snapshot?.date, syntax))
            }

            SensorStatus.NO_PERMISSION -> {
                add(commentLine("// E: ACTIVITY_RECOGNITION permission not granted", syntax))
                add(commentLine("// tsteps reads the step counter on-device: no GPS, no network", syntax))
                add(grantCommandLine(syntax, onGrantPermission, grantClickLabel))
                add(blank())
                addAll(emptyDocument(snapshot?.date, syntax))
            }

            SensorStatus.OK -> {
                if (trackComingSoon) {
                    add(commentLine("// $ tsteps track — coming soon", syntax))
                    add(blank())
                }
                addAll(dataDocument(snapshot ?: return@buildList, units, syntax))
            }
        }
    }

    private fun dataDocument(
        snapshot: TodaySnapshot,
        units: UnitsSystem,
        syntax: SyntaxColors
    ): List<CodeLine> = buildList {
        add(punctLine("{", 0, syntax))
        add(stringLine("date", snapshot.date.toString(), comma = true, syntax, indent = 1))

        add(keyOpen("steps", syntax, indent = 1))
        val hasGoal = snapshot.goalSteps > 0
        add(numberLine("count", snapshot.steps.toString(), comma = hasGoal, syntax, indent = 2))
        if (hasGoal) {
            add(numberLine("goal", snapshot.goalSteps.toString(), comma = true, syntax, indent = 2))
            add(
                stringLine(
                    "check",
                    StepsGlyphs.goalBar(snapshot.steps, snapshot.goalSteps),
                    comma = false, syntax, indent = 2
                )
            )
        }
        add(punctLine("},", 1, syntax))

        add(keyOpen("movement", syntax, indent = 1))
        val (distanceKey, distanceValue) = when (units) {
            UnitsSystem.METRIC -> "distance_km" to snapshot.distanceMeters / 1_000.0
            UnitsSystem.IMPERIAL -> "distance_mi" to snapshot.distanceMeters / 1_609.344
        }
        add(
            numberLine(
                distanceKey, decimal(distanceValue), comma = true, syntax, indent = 2,
                hint = "// estimated from stride length"
            )
        )
        add(
            numberLine(
                "active_min", snapshot.activeMinutes.toString(),
                comma = snapshot.activeKcal != null, syntax, indent = 2,
                hint = "// estimated at 100 steps/min"
            )
        )
        val kcal = snapshot.activeKcal
        if (kcal != null) {
            add(
                numberLine(
                    "active_kcal", kcal.toInt().toString(), comma = false, syntax, indent = 2,
                    hint = "// MET × weight × active time"
                )
            )
        } else {
            add(commentLine("// active_kcal: set profile.weight_kg to enable", syntax, indent = 2))
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

        add(rawValueLine("sessions", "[]", comma = hasGoal, syntax, indent = 1))
        if (hasGoal) {
            add(numberLine("streak_days", snapshot.streakDays.toString(), comma = false, syntax, indent = 1))
        }
        add(punctLine("}", 0, syntax))
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

    private fun keyOpen(key: String, syntax: SyntaxColors, indent: Int) = CodeLine(
        buildAnnotatedString {
            withStyle(SpanStyle(color = syntax.key)) { append("\"$key\"") }
            withStyle(SpanStyle(color = syntax.comment)) { append(": {") }
        },
        indent
    )

    private fun stringLine(
        key: String,
        value: String,
        comma: Boolean,
        syntax: SyntaxColors,
        indent: Int,
        hint: String? = null
    ) = valueLine(key, "\"$value\"", syntax.string, comma, syntax, indent, hint)

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
        indent: Int
    ) = valueLine(key, value, syntax.comment, comma, syntax, indent, hint = null)

    private fun valueLine(
        key: String,
        renderedValue: String,
        valueColor: androidx.compose.ui.graphics.Color,
        comma: Boolean,
        syntax: SyntaxColors,
        indent: Int,
        hint: String?
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
        indent
    )

    private fun decimal(value: Double): String = "%.1f".format(Locale.ROOT, value)
}
