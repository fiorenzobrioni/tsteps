package com.callbackdev.tsteps.export

import com.callbackdev.tsteps.domain.SessionItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The two shapes the archive can take, and the command that writes each. */
enum class ExportFormat(val command: String) {
    JSON("tsteps export --json"),
    CSV("tsteps export --csv")
}

/**
 * One day of the archive. A committed day carries the numbers frozen at its
 * commit (the profile of that day); a day still in the working tree — today, or
 * a straggler the safety net hasn't committed yet — is computed live and says
 * so through [committed], so the archive never passes an open day off as
 * history.
 */
data class ExportDay(
    val date: LocalDate,
    /** The day's git-shaped name, the same hash the log screen shows. */
    val commit: String,
    val steps: Long,
    val activeMinutes: Int,
    val distanceMeters: Double,
    /** Null without a weight in the profile: the metric is missing, not zero. */
    val activeKcal: Double?,
    val goalSteps: Int,
    /** Null when no goal was set — the check was skipped, not failed. */
    val goalMet: Boolean?,
    val committed: Boolean
)

/** Everything one export writes, gathered and ready to be rendered. */
data class ExportBundle(
    val exportedAtMillis: Long,
    val zone: ZoneId,
    val days: List<ExportDay>,
    val sessions: List<SessionItem>
) {
    val isEmpty: Boolean get() = days.isEmpty() && sessions.isEmpty()
}

/** A rendered file, ready for the sink: name, MIME type, whole content. */
data class ExportFile(val name: String, val mimeType: String, val content: String)

/**
 * Renders the archive. Pure and unit-testable: no Android, no I/O — the sink
 * takes what comes out of here and puts it in Downloads.
 *
 * Two decisions the format hangs on:
 *
 * - **Canonical units, always.** Meters and kcal whatever `units.system` says,
 *   with the unit named in the key (`distance_m`). The app renames keys with
 *   the unit because a screen must not lie to the person reading it; an archive
 *   is read by machines and by a future you, and both are better served by one
 *   unit that never moves than by columns whose meaning follows a setting.
 * - **The estimate disclaimer rides the JSON, not the CSV.** CSV has no comment
 *   channel a spreadsheet tolerates, so its header stays clean and the README
 *   carries the sentence instead.
 * - **Every estimate ships the factor that produced it.** `distance_m` is
 *   `steps × stride`, and without the stride a future reader cannot tell a
 *   6 km day measured at 0.78 m from one guessed at the 0.72 m fallback, nor
 *   recompute anything if they later measure their own. [strideMeters] recovers
 *   it from the row itself rather than storing a second copy that could drift:
 *   the distance IS the product, so the quotient is the factor, frozen with the
 *   day whatever the profile says today.
 */
object ExportDocuments {

    /**
     * Bumped only if the shape changes in a way a reader would notice.
     * v2 added `stride_m` to days and sessions.
     */
    const val SCHEMA_VERSION = 2

    const val JSON_MIME = "application/json"
    const val CSV_MIME = "text/csv"

    /**
     * The files one command writes. JSON is a single document because it can
     * nest; CSV is one table per file — days and sessions are different rows,
     * and pretending otherwise is what makes an exported spreadsheet useless.
     */
    fun files(bundle: ExportBundle, format: ExportFormat): List<ExportFile> {
        val stamp = Instant.ofEpochMilli(bundle.exportedAtMillis)
            .atZone(bundle.zone).toLocalDate()
        return when (format) {
            ExportFormat.JSON -> listOf(
                ExportFile("tsteps-export-$stamp.json", JSON_MIME, json(bundle))
            )
            ExportFormat.CSV -> listOf(
                ExportFile("tsteps-days-$stamp.csv", CSV_MIME, daysCsv(bundle)),
                ExportFile("tsteps-sessions-$stamp.csv", CSV_MIME, sessionsCsv(bundle))
            )
        }
    }

    fun json(bundle: ExportBundle): String = buildString {
        appendLine("{")
        appendLine("""  "app": "tsteps",""")
        appendLine("""  "schema": $SCHEMA_VERSION,""")
        appendLine("""  "exported_at": "${isoInstant(bundle.exportedAtMillis)}",""")
        appendLine("""  "timezone": "${escape(bundle.zone.id)}",""")
        appendLine("""  "units": "steps, meters, minutes, kcal",""")
        appendLine(
            """  "estimates": "distance_m and active_kcal are estimated from your """ +
                """profile, not measured","""
        )
        appendArray("days", bundle.days.map(::dayObject))
        appendLine(",")
        appendArray("sessions", bundle.sessions.map { sessionObject(it, bundle.zone) })
        appendLine()
        appendLine("}")
    }

    fun daysCsv(bundle: ExportBundle): String = buildString {
        appendLine(
            "date,commit,steps,active_min,distance_m,stride_m,active_kcal," +
                "goal_steps,goal_met,committed"
        )
        bundle.days.forEach { day ->
            appendLine(
                listOf(
                    day.date.toString(),
                    day.commit,
                    day.steps.toString(),
                    day.activeMinutes.toString(),
                    meters(day.distanceMeters),
                    strideMeters(day.steps, day.distanceMeters)?.let(::stride).orEmpty(),
                    day.activeKcal?.let(::kcal).orEmpty(),
                    day.goalSteps.toString(),
                    day.goalMet?.toString().orEmpty(),
                    day.committed.toString()
                ).joinToString(",", transform = ::cell)
            )
        }
    }

    fun sessionsCsv(bundle: ExportBundle): String = buildString {
        appendLine(
            "date,start,end,type,steps,distance_m,stride_m,active_min," +
                "avg_cadence_spm,source,start_approx,end_approx"
        )
        bundle.sessions.forEach { session ->
            appendLine(
                listOf(
                    localDate(session.startMillis, bundle.zone).toString(),
                    isoLocal(session.startMillis, bundle.zone),
                    isoLocal(session.endMillis, bundle.zone),
                    session.type,
                    session.steps.toString(),
                    meters(session.distanceMeters),
                    strideMeters(session.steps, session.distanceMeters)?.let(::stride).orEmpty(),
                    session.activeMinutes.toString(),
                    session.avgCadenceSpm?.toString().orEmpty(),
                    source(session),
                    session.startApprox.toString(),
                    session.endApprox.toString()
                ).joinToString(",", transform = ::cell)
            )
        }
    }

    /** One record per line: an archive reads better, and diffs better, that way. */
    private fun StringBuilder.appendArray(key: String, records: List<String>) {
        if (records.isEmpty()) {
            append("""  "$key": []""")
            return
        }
        appendLine("""  "$key": [""")
        records.forEachIndexed { index, record ->
            append("    ")
            append(record)
            if (index != records.lastIndex) append(",")
            appendLine()
        }
        append("  ]")
    }

    private fun dayObject(day: ExportDay): String = listOf(
        """"date": "${day.date}"""",
        """"commit": "${escape(day.commit)}"""",
        """"steps": ${day.steps}""",
        """"active_min": ${day.activeMinutes}""",
        """"distance_m": ${meters(day.distanceMeters)}""",
        """"stride_m": ${strideMeters(day.steps, day.distanceMeters)?.let(::stride) ?: "null"}""",
        """"active_kcal": ${day.activeKcal?.let(::kcal) ?: "null"}""",
        """"goal_steps": ${day.goalSteps}""",
        """"goal_met": ${day.goalMet?.toString() ?: "null"}""",
        """"committed": ${day.committed}"""
    ).joinToString(", ", prefix = "{ ", postfix = " }")

    private fun sessionObject(session: SessionItem, zone: ZoneId): String = listOf(
        """"date": "${localDate(session.startMillis, zone)}"""",
        """"start": "${isoLocal(session.startMillis, zone)}"""",
        """"end": "${isoLocal(session.endMillis, zone)}"""",
        """"type": "${escape(session.type)}"""",
        """"steps": ${session.steps}""",
        """"distance_m": ${meters(session.distanceMeters)}""",
        """"stride_m": ${strideMeters(session.steps, session.distanceMeters)?.let(::stride) ?: "null"}""",
        """"active_min": ${session.activeMinutes}""",
        """"avg_cadence_spm": ${session.avgCadenceSpm?.toString() ?: "null"}""",
        """"source": "${source(session)}"""",
        """"start_approx": ${session.startApprox}""",
        """"end_approx": ${session.endApprox}"""
    ).joinToString(", ", prefix = "{ ", postfix = " }")

    private fun source(session: SessionItem): String = if (session.auto) "auto" else "manual"

    /**
     * The stride the row's own distance was computed with. Null on a day that
     * took no step: there is no factor to report, and 0/0 is not 0.
     */
    private fun strideMeters(steps: Long, distanceMeters: Double): Double? =
        if (steps > 0) distanceMeters / steps else null

    private fun stride(value: Double): String = "%.3f".format(Locale.ROOT, value)

    private fun meters(value: Double): String = "%.1f".format(Locale.ROOT, value)

    private fun kcal(value: Double): String = "%.0f".format(Locale.ROOT, value)

    private fun localDate(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    private fun isoInstant(millis: Long): String = DateTimeFormatter.ISO_INSTANT
        .format(Instant.ofEpochMilli(millis).truncatedTo(ChronoUnit.SECONDS))

    /** Wall time with its offset: when a walk happened is a local fact. */
    private fun isoLocal(millis: Long, zone: ZoneId): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            Instant.ofEpochMilli(millis).atZone(zone).truncatedTo(ChronoUnit.SECONDS)
        )

    /** Every value here is machine-made, but a quoting bug is silent: quote anyway. */
    private fun cell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
