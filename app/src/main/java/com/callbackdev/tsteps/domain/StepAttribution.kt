package com.callbackdev.tsteps.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** A step delta's share landing in one (date, hour) bucket of the local calendar. */
data class BucketShare(
    val date: LocalDate,
    val hour: Int,
    val steps: Long
)

/**
 * Spreads a step delta over the local-time (date, hour) buckets its interval
 * covers, proportionally to time. This is what makes the hourly sparkline and the
 * day boundary honest when samples are sparse: a batch read at 00:10 whose span
 * started at 23:50 credits two dates, not whichever day the read happened on.
 *
 * All calendar math takes the zone as a parameter — DST transitions (23h/25h
 * days) and timezone changes are ordinary inputs here, covered by tests, not
 * device-only surprises.
 */
object StepAttribution {

    /**
     * Spans longer than this are clamped (keeping the interval's end). Samples
     * normally arrive minutes apart; a span this long means the app was dead or
     * dozed for days, and pretending we can spread steps evenly over a week is
     * false precision — better to keep them near the moment we actually observed.
     */
    const val MAX_SPREAD_HOURS = 48L

    fun attribute(
        deltaSteps: Long,
        fromMillis: Long,
        toMillis: Long,
        zone: ZoneId
    ): List<BucketShare> {
        if (deltaSteps <= 0L) return emptyList()

        val to = Instant.ofEpochMilli(toMillis).atZone(zone)
        val clampedFromMillis = maxOf(fromMillis, toMillis - MAX_SPREAD_HOURS * 3_600_000L)
        if (clampedFromMillis >= toMillis) {
            return listOf(BucketShare(to.toLocalDate(), to.hour, deltaSteps))
        }
        val from = Instant.ofEpochMilli(clampedFromMillis).atZone(zone)

        // Walk hour windows [cursor, nextHourTop) across the span, weighting each
        // bucket by overlap. Floor each share and hand the rounding remainder to
        // the last bucket so the total always equals deltaSteps exactly.
        val spanMillis = (toMillis - clampedFromMillis).toDouble()
        val shares = mutableListOf<BucketShare>()
        var cursor: ZonedDateTime = from
        var assigned = 0L
        while (cursor.toInstant().toEpochMilli() < toMillis) {
            val hourTop = cursor.truncatedTo(ChronoUnit.HOURS).plusHours(1)
            val windowEndMillis = minOf(hourTop.toInstant().toEpochMilli(), toMillis)
            val overlap = windowEndMillis - cursor.toInstant().toEpochMilli()
            val share = (deltaSteps * (overlap / spanMillis)).toLong()
            if (share > 0) {
                shares += BucketShare(cursor.toLocalDate(), cursor.hour, share)
                assigned += share
            }
            cursor = Instant.ofEpochMilli(windowEndMillis).atZone(zone)
        }
        val remainder = deltaSteps - assigned
        if (remainder > 0) {
            val lastBucket = to.minusNanos(1)
            val last = shares.lastOrNull()
            if (last != null && last.date == lastBucket.toLocalDate() && last.hour == lastBucket.hour) {
                shares[shares.lastIndex] = last.copy(steps = last.steps + remainder)
            } else {
                shares += BucketShare(lastBucket.toLocalDate(), lastBucket.hour, remainder)
            }
        }
        return shares
    }
}
