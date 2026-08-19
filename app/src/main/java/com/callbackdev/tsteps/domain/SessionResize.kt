package com.callbackdev.tsteps.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToLong

/**
 * Recomputes an auto session's steps after the user edits its boundaries (the
 * `[rm]`/edit verbs of Fase 11). An auto session's steps were inferred from
 * sampled spans in the first place, so a resize re-runs the same inference over
 * the new range — never keeps a step count the new times no longer describe
 * (the file must not lie).
 *
 * Primary source: the recorded [SampleSpan]s (minute-ish precision, retained a
 * few days). If they cover too little of the new range (recording starts when
 * the toggle turns on, and old spans get pruned), the hourly buckets step in as
 * an hour-grained proportional estimate — coarser, still honest.
 */
object SessionResize {

    /** Below this sample coverage of the range, fall back to hourly buckets. */
    const val MIN_SAMPLE_COVERAGE = 0.5

    fun steps(
        samples: List<SampleSpan>,
        hourly: List<BucketShare>,
        fromMillis: Long,
        toMillis: Long,
        zone: ZoneId
    ): Long {
        val (fromSamples, coverage) = stepsFromSamples(samples, fromMillis, toMillis)
        if (coverage >= MIN_SAMPLE_COVERAGE) return fromSamples
        return stepsFromHourly(hourly, fromMillis, toMillis, zone)
    }

    /**
     * Proportional share of every recorded span overlapping the range, plus how
     * much of the range the spans actually cover (0..1).
     */
    fun stepsFromSamples(
        samples: List<SampleSpan>,
        fromMillis: Long,
        toMillis: Long
    ): Pair<Long, Double> {
        val rangeMillis = toMillis - fromMillis
        if (rangeMillis <= 0L) return 0L to 0.0
        var steps = 0.0
        var coveredMillis = 0L
        samples.forEach { sample ->
            val overlap = minOf(toMillis, sample.toMillis) - maxOf(fromMillis, sample.fromMillis)
            if (overlap <= 0L) return@forEach
            coveredMillis += overlap
            val span = sample.toMillis - sample.fromMillis
            steps += if (span > 0L) sample.steps * (overlap.toDouble() / span) else 0.0
        }
        return steps.roundToLong() to (coveredMillis.toDouble() / rangeMillis)
    }

    /** Hour-grained fallback: each (date, hour) bucket contributes its overlap share. */
    fun stepsFromHourly(
        hourly: List<BucketShare>,
        fromMillis: Long,
        toMillis: Long,
        zone: ZoneId
    ): Long {
        var steps = 0.0
        hourly.forEach { bucket ->
            val window = hourWindow(bucket.date, bucket.hour, zone) ?: return@forEach
            val (hourStart, hourEnd) = window
            val overlap = minOf(toMillis, hourEnd) - maxOf(fromMillis, hourStart)
            val hourSpan = hourEnd - hourStart
            if (overlap > 0L && hourSpan > 0L) {
                steps += bucket.steps * (overlap.toDouble() / hourSpan)
            }
        }
        return steps.roundToLong()
    }

    /**
     * The epoch window of one local (date, hour) bucket. DST edges resolve the
     * ZonedDateTime way (a skipped hour shifts forward, giving it a zero span
     * that contributes nothing) — estimate-grade math for an estimate-grade
     * fallback.
     */
    private fun hourWindow(date: LocalDate, hour: Int, zone: ZoneId): Pair<Long, Long>? {
        if (hour !in 0..23) return null
        val start = ZonedDateTime.of(date, LocalTime.of(hour, 0), zone)
        val end = start.plusHours(1)
        return start.toInstant().toEpochMilli() to end.toInstant().toEpochMilli()
    }
}
