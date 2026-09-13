package com.callbackdev.tsteps.recording

import com.callbackdev.tsteps.domain.BucketShare
import com.callbackdev.tsteps.domain.StepAttribution
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Recorded hours → the (date, hour) buckets the day is made of. Pure, so the
 * calendar edges that bite on a device — DST, a timezone moved mid-flight — are
 * unit tests here rather than surprises there.
 *
 * The gain over the sensor path is not the battery, it is the truth: a recorded
 * hour carries its own interval, so the steps land in the hour they were walked
 * instead of being spread proportionally across everything the app did not see
 * ([StepAttribution] stays for exactly that fallback).
 */
object RecordingInterop {

    /** Start of the local hour containing [millis] — where a read range begins. */
    fun alignDownToHour(millis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(millis)
            .atZone(zone)
            .truncatedTo(ChronoUnit.HOURS)
            .toInstant()
            .toEpochMilli()

    /**
     * Empty hours are dropped rather than written as zeros: a bucket that does
     * not exist and a bucket holding zero read the same on every screen, and the
     * first costs no row.
     */
    fun hourShares(hours: List<RecordedHour>, zone: ZoneId): List<BucketShare> =
        hours.filter { it.steps > 0L && it.endMillis > it.startMillis }
            .flatMap { hour ->
                val start = Instant.ofEpochMilli(hour.startMillis).atZone(zone)
                val last = Instant.ofEpochMilli(hour.endMillis - 1).atZone(zone)
                if (start.toLocalDate() == last.toLocalDate() && start.hour == last.hour) {
                    listOf(BucketShare(start.toLocalDate(), start.hour, hour.steps))
                } else {
                    // A bucket that straddles two local hours (an unaligned range,
                    // or an offset change inside it) is spread by time like any
                    // other multi-hour delta instead of being credited wholesale
                    // to the hour it happens to start in.
                    StepAttribution.attribute(hour.steps, hour.startMillis, hour.endMillis, zone)
                }
            }
}
