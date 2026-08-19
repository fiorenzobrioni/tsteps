package com.callbackdev.tsteps.healthconnect

import com.callbackdev.tsteps.domain.BucketShare
import com.callbackdev.tsteps.domain.SessionItem
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Steps another app counted today, grouped per origin — shown, never added. */
data class OriginSteps(
    val packageName: String,
    /** Short display key derived from the package (`shealth`, `fitbit`). */
    val label: String,
    val steps: Long
)

/**
 * Pure mapping between tsteps' own shapes and the gateway DTOs. All the
 * decisions live here where they are unit-testable: hourly buckets become
 * interval records keyed and versioned for idempotent upserts, sessions carry
 * their honest titles, and external records are grouped **per origin** and
 * never summed across origins — two apps that watched the same walk cannot be
 * added together, so tsteps doesn't (VISION §7: dedup by source, never double
 * count).
 */
object HealthInterop {

    fun stepsClientId(date: LocalDate, hour: Int): String =
        "tsteps-steps-%s-%02d".format(date, hour)

    fun sessionClientId(id: Long): String = "tsteps-session-$id"

    /**
     * Non-empty hourly buckets as interval records. The version IS the step
     * count: buckets only ever grow, so an unchanged hour is a no-op upsert
     * and a grown one replaces its record — no dirty-tracking state needed.
     */
    fun hourSteps(buckets: List<BucketShare>, zone: ZoneId): List<HcHourSteps> =
        buckets.filter { it.steps > 0 }.mapNotNull { bucket ->
            val start = ZonedDateTime.of(bucket.date, LocalTime.of(bucket.hour, 0), zone)
            // A DST-skipped hour resolves onto its neighbor: such a bucket can
            // only be corrupted state — drop it rather than double the neighbor.
            if (start.hour != bucket.hour) return@mapNotNull null
            val end = start.plusHours(1)
            val startMillis = start.toInstant().toEpochMilli()
            val endMillis = end.toInstant().toEpochMilli()
            if (endMillis <= startMillis) return@mapNotNull null
            HcHourSteps(
                clientId = stepsClientId(bucket.date, bucket.hour),
                startMillis = startMillis,
                endMillis = endMillis,
                steps = bucket.steps
            )
        }

    /**
     * Completed sessions as exercise records. The version is the write pass
     * (now): boundary edits happened before this sync, so rewriting with a
     * fresh version always carries the current shape.
     */
    fun sessions(items: List<SessionItem>, nowMillis: Long): List<HcSessionRecord> =
        items.map { session ->
            HcSessionRecord(
                clientId = sessionClientId(session.id),
                version = nowMillis,
                startMillis = session.startMillis,
                endMillis = session.endMillis,
                walking = session.type == "walk",
                title = session.type + if (session.auto) " (auto)" else ""
            )
        }

    /**
     * External records grouped per origin, ours excluded, biggest first.
     * Labels are the last package segment (`com.sec.android.app.shealth` →
     * `shealth`); a collision keeps both by numbering the later one.
     */
    fun externalByOrigin(
        records: List<HcExternalSteps>,
        ownPackage: String
    ): List<OriginSteps> {
        val grouped = records.asSequence()
            .filter { it.originPackage != ownPackage && it.steps > 0 }
            .groupBy { it.originPackage }
            .map { (pkg, rows) -> pkg to rows.sumOf { it.steps } }
            .sortedByDescending { it.second }
        val used = mutableSetOf<String>()
        return grouped.map { (pkg, steps) ->
            var label = pkg.substringAfterLast('.')
                .lowercase()
                .filter { it.isLetterOrDigit() || it == '_' }
                .ifBlank { "app" }
            var suffix = 2
            val base = label
            while (!used.add(label)) label = "${base}_${suffix++}"
            OriginSteps(packageName = pkg, label = label, steps = steps)
        }
    }
}
