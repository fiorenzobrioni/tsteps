package com.callbackdev.tsteps.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepAttributionTest {

    private val rome = ZoneId.of("Europe/Rome")

    private fun millis(dateTime: String): Long =
        LocalDateTime.parse(dateTime).atZone(rome).toInstant().toEpochMilli()

    private fun total(shares: List<BucketShare>) = shares.sumOf { it.steps }

    @Test
    fun `zero or negative delta attributes nothing`() {
        assertEquals(emptyList<BucketShare>(), StepAttribution.attribute(0, 0, 1000, rome))
        assertEquals(emptyList<BucketShare>(), StepAttribution.attribute(-5, 0, 1000, rome))
    }

    @Test
    fun `interval inside one hour lands in that single bucket`() {
        val shares = StepAttribution.attribute(
            500, millis("2026-08-18T14:10:00"), millis("2026-08-18T14:40:00"), rome
        )
        assertEquals(listOf(BucketShare(LocalDate.parse("2026-08-18"), 14, 500L)), shares)
    }

    @Test
    fun `interval across two hours splits proportionally and sums exactly`() {
        // 14:45 → 15:30: 15 min in hour 14, 30 min in hour 15.
        val shares = StepAttribution.attribute(
            900, millis("2026-08-18T14:45:00"), millis("2026-08-18T15:30:00"), rome
        )
        assertEquals(900L, total(shares))
        assertEquals(300L, shares.first { it.hour == 14 }.steps)
        assertEquals(600L, shares.first { it.hour == 15 }.steps)
    }

    @Test
    fun `interval across midnight credits both dates`() {
        val shares = StepAttribution.attribute(
            600, millis("2026-08-18T23:40:00"), millis("2026-08-19T00:20:00"), rome
        )
        assertEquals(600L, total(shares))
        assertEquals(
            300L,
            shares.first { it.date == LocalDate.parse("2026-08-18") && it.hour == 23 }.steps
        )
        assertEquals(
            300L,
            shares.first { it.date == LocalDate.parse("2026-08-19") && it.hour == 0 }.steps
        )
    }

    @Test
    fun `zero-length interval drops everything in the reading's bucket`() {
        val at = millis("2026-08-18T09:05:00")
        val shares = StepAttribution.attribute(250, at, at, rome)
        assertEquals(listOf(BucketShare(LocalDate.parse("2026-08-18"), 9, 250L)), shares)
    }

    @Test
    fun `tiny delta over many hours still sums exactly`() {
        val shares = StepAttribution.attribute(
            1, millis("2026-08-18T10:00:00"), millis("2026-08-18T13:00:00"), rome
        )
        assertEquals(1L, total(shares))
    }

    @Test
    fun `fall-back DST day spreads over the repeated hour and sums exactly`() {
        // Rome, 2026-10-25: clocks fall back 03:00→02:00, the 02 hour happens twice.
        // 01:30 CEST + 3 real hours = 03:30 CET; hour bucket 2 collects 2 of the 3 hours.
        val from = millis("2026-10-25T01:30:00")
        val to = from + 3 * 3_600_000L
        val shares = StepAttribution.attribute(300, from, to, rome)
        assertEquals(300L, total(shares))
        assertEquals(200L, shares.filter { it.hour == 2 }.sumOf { it.steps })
    }

    @Test
    fun `spring-forward DST day skips the missing hour and sums exactly`() {
        // Rome, 2026-03-29: clocks jump 02:00→03:00, hour 2 does not exist.
        val from = millis("2026-03-29T01:30:00")
        val to = from + 2 * 3_600_000L // wall clock: 01:30 → 04:30
        val shares = StepAttribution.attribute(240, from, to, rome)
        assertEquals(240L, total(shares))
        assertTrue(shares.none { it.hour == 2 })
    }

    @Test
    fun `spans longer than the cap are clamped near the observed end`() {
        val to = millis("2026-08-18T12:00:00")
        val from = to - 10L * 24 * 3_600_000L // ten days earlier
        val shares = StepAttribution.attribute(4800, from, to, rome)
        assertEquals(4800L, total(shares))
        // Nothing older than the 48h window before `to`.
        val oldestAllowed = LocalDate.parse("2026-08-16")
        assertTrue(shares.all { !it.date.isBefore(oldestAllowed) })
    }
}
