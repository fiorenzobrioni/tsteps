package com.callbackdev.tsteps.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionResizeTest {

    private val zone: ZoneId = ZoneId.of("Europe/Rome")
    private val date: LocalDate = LocalDate.parse("2026-08-18")

    private fun at(hour: Int, minute: Int): Long =
        ZonedDateTime.of(date, LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()

    @Test
    fun `samples covering the range give proportional steps and full coverage`() {
        val samples = listOf(
            SampleSpan(at(9, 0), at(9, 15), 1_500),
            SampleSpan(at(9, 15), at(9, 30), 300)
        )
        val (steps, coverage) = SessionResize.stepsFromSamples(
            samples, fromMillis = at(9, 5), toMillis = at(9, 20)
        )
        // 10 of 15 min from the first span (1000) + 5 of 15 from the second (100).
        assertEquals(1_100L, steps)
        assertEquals(1.0, coverage, 1e-9)
    }

    @Test
    fun `poor sample coverage falls back to the hourly buckets`() {
        // Recording started at 9:56: four minutes of samples for a 60-min range.
        val steps = SessionResize.steps(
            samples = listOf(SampleSpan(at(9, 56), at(10, 0), 400)),
            hourly = listOf(
                BucketShare(date, 9, 3_000),
                BucketShare(date, 10, 600)
            ),
            fromMillis = at(9, 0),
            toMillis = at(10, 0),
            zone = zone
        )
        // Fallback: the whole hour-9 bucket overlaps the range.
        assertEquals(3_000L, steps)
    }

    @Test
    fun `good sample coverage wins over the hourly estimate`() {
        val steps = SessionResize.steps(
            samples = listOf(SampleSpan(at(9, 0), at(9, 30), 2_000)),
            hourly = listOf(BucketShare(date, 9, 9_999)),
            fromMillis = at(9, 0),
            toMillis = at(9, 30),
            zone = zone
        )
        assertEquals(2_000L, steps)
    }

    @Test
    fun `the hourly fallback splits buckets proportionally across the range`() {
        val steps = SessionResize.stepsFromHourly(
            hourly = listOf(
                BucketShare(date, 9, 1_200),
                BucketShare(date, 10, 600)
            ),
            fromMillis = at(9, 30),
            toMillis = at(10, 30),
            zone = zone
        )
        // Half of hour 9 (600) + half of hour 10 (300).
        assertEquals(900L, steps)
    }

    @Test
    fun `an empty or inverted range yields zero`() {
        val (steps, coverage) = SessionResize.stepsFromSamples(
            listOf(SampleSpan(at(9, 0), at(9, 15), 1_500)),
            fromMillis = at(9, 10),
            toMillis = at(9, 10)
        )
        assertEquals(0L, steps)
        assertEquals(0.0, coverage, 1e-9)
    }
}
