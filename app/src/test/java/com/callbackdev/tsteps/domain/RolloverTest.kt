package com.callbackdev.tsteps.domain

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class RolloverTest {

    private val rome = ZoneId.of("Europe/Rome")

    private fun millis(dateTime: String, zone: ZoneId = rome): Long =
        LocalDateTime.parse(dateTime).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `next midnight on an ordinary day is 24h minus the elapsed part`() {
        val next = Rollover.nextMidnightMillis(millis("2026-08-18T10:00:00"), rome)
        assertEquals(millis("2026-08-19T00:00:00"), next)
    }

    @Test
    fun `exactly at midnight the next one is tomorrow's, not now`() {
        val midnight = millis("2026-08-18T00:00:00")
        assertEquals(millis("2026-08-19T00:00:00"), Rollover.nextMidnightMillis(midnight, rome))
    }

    @Test
    fun `spring-forward day is 23 wall hours long`() {
        // Rome, 2026-03-29: 02:00 → 03:00.
        val midnight = millis("2026-03-29T00:00:00")
        val next = Rollover.nextMidnightMillis(midnight, rome)
        assertEquals(23L * 3_600_000L, next - midnight)
    }

    @Test
    fun `fall-back day is 25 wall hours long`() {
        // Rome, 2026-10-25: 03:00 → 02:00.
        val midnight = millis("2026-10-25T00:00:00")
        val next = Rollover.nextMidnightMillis(midnight, rome)
        assertEquals(25L * 3_600_000L, next - midnight)
    }

    @Test
    fun `the zone parameter decides whose midnight it is`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val now = millis("2026-08-18T10:00:00")
        assertEquals(millis("2026-08-19T00:00:00", tokyo), Rollover.nextMidnightMillis(now, tokyo))
    }
}
