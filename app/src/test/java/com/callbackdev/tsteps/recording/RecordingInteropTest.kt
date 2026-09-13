package com.callbackdev.tsteps.recording

import com.callbackdev.tsteps.domain.BucketShare
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingInteropTest {

    private val rome = ZoneId.of("Europe/Rome")

    private fun utc(instant: String): Long = Instant.parse(instant).toEpochMilli()

    private fun hour(startUtc: String, endUtc: String, steps: Long) =
        RecordedHour(utc(startUtc), utc(endUtc), steps)

    @Test
    fun `an aligned hour lands whole in its local bucket`() {
        val shares = RecordingInterop.hourShares(
            listOf(hour("2026-09-12T07:00:00Z", "2026-09-12T08:00:00Z", 1_200L)),
            rome
        )
        // 07:00Z is 09:00 in Rome (CEST): the local hour is what a bucket is keyed by.
        assertEquals(listOf(BucketShare(LocalDate.parse("2026-09-12"), 9, 1_200L)), shares)
    }

    @Test
    fun `hours without steps are dropped instead of written as zeros`() {
        val shares = RecordingInterop.hourShares(
            listOf(
                hour("2026-09-12T07:00:00Z", "2026-09-12T08:00:00Z", 0L),
                hour("2026-09-12T08:00:00Z", "2026-09-12T09:00:00Z", 40L)
            ),
            rome
        )
        assertEquals(1, shares.size)
        assertEquals(40L, shares.single().steps)
    }

    /** A degenerate bucket must never be credited wholesale to the hour it starts in. */
    @Test
    fun `a bucket straddling two local hours is spread by time`() {
        val shares = RecordingInterop.hourShares(
            listOf(hour("2026-09-12T07:30:00Z", "2026-09-12T08:30:00Z", 600L)),
            rome
        )
        assertEquals(2, shares.size)
        assertEquals(listOf(9, 10), shares.map { it.hour })
        assertEquals(600L, shares.sumOf { it.steps })
    }

    /**
     * The 25-hour day: at the end of DST Rome lives 02:00 twice. Both recorded
     * hours are hour 2 of the same date, and nothing is lost or renamed — the
     * day's total is what the two add up to.
     */
    @Test
    fun `the repeated hour of a DST night keeps both its buckets`() {
        val shares = RecordingInterop.hourShares(
            listOf(
                hour("2026-10-25T00:00:00Z", "2026-10-25T01:00:00Z", 100L),
                hour("2026-10-25T01:00:00Z", "2026-10-25T02:00:00Z", 50L)
            ),
            rome
        )
        assertEquals(2, shares.size)
        assertTrue(shares.all { it.hour == 2 && it.date == LocalDate.parse("2026-10-25") })
        assertEquals(150L, shares.sumOf { it.steps })
    }

    @Test
    fun `a range is aligned down to the start of its local hour`() {
        val aligned = RecordingInterop.alignDownToHour(utc("2026-09-12T07:43:19Z"), rome)
        assertEquals(utc("2026-09-12T07:00:00Z"), aligned)
    }

    /** The alignment has to survive the hour that does not exist in spring. */
    @Test
    fun `aligning inside the spring-forward gap stays on the local clock`() {
        // 01:15Z is 03:15 in Rome on the night the clock jumps 02:00 to 03:00.
        val aligned = RecordingInterop.alignDownToHour(utc("2026-03-29T01:15:00Z"), rome)
        assertEquals(utc("2026-03-29T01:00:00Z"), aligned)
    }
}
