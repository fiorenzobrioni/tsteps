package com.callbackdev.tsteps.healthconnect

import com.callbackdev.tsteps.domain.BucketShare
import com.callbackdev.tsteps.domain.SessionItem
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthInteropTest {

    private val rome: ZoneId = ZoneId.of("Europe/Rome")

    private fun millis(dateTime: String): Long =
        LocalDateTime.parse(dateTime).atZone(rome).toInstant().toEpochMilli()

    @Test
    fun `hourly buckets become keyed interval records, zeros skipped`() {
        val date = LocalDate.parse("2026-08-19")
        val records = HealthInterop.hourSteps(
            listOf(
                BucketShare(date, 8, 0),
                BucketShare(date, 9, 1_200),
                BucketShare(date, 14, 300)
            ),
            rome
        )
        assertEquals(2, records.size)
        val nine = records.first()
        assertEquals("tsteps-steps-2026-08-19-09", nine.clientId)
        assertEquals(millis("2026-08-19T09:00:00"), nine.startMillis)
        assertEquals(millis("2026-08-19T10:00:00"), nine.endMillis)
        assertEquals(1_200L, nine.steps)
        assertEquals("tsteps-steps-2026-08-19-14", records.last().clientId)
    }

    @Test
    fun `a DST-skipped hour is dropped instead of shifting onto its neighbor`() {
        // Europe/Rome 2026-03-29: 02:00 does not exist. Such a bucket can only
        // come from corrupted state; it must not masquerade as the 03:00 hour.
        val date = LocalDate.parse("2026-03-29")
        val records = HealthInterop.hourSteps(
            listOf(
                BucketShare(date, 2, 500),
                BucketShare(date, 3, 700)
            ),
            rome
        )
        assertEquals(listOf("tsteps-steps-2026-03-29-03"), records.map { it.clientId })
    }

    @Test
    fun `sessions carry their honest titles and exercise kind`() {
        val start = millis("2026-08-19T09:00:00")
        fun item(id: Long, type: String, auto: Boolean) = SessionItem(
            id = id, startMillis = start, endMillis = start + 1_800_000L,
            type = type, steps = 3_000, distanceMeters = 2_160.0,
            activeMillis = 1_800_000L, avgCadenceSpm = 100, auto = auto
        )
        val records = HealthInterop.sessions(
            listOf(
                item(1, "walk", auto = false),
                item(2, "walk", auto = true),
                item(3, "other", auto = false)
            ),
            nowMillis = 42L
        )
        assertEquals(
            listOf("tsteps-session-1", "tsteps-session-2", "tsteps-session-3"),
            records.map { it.clientId }
        )
        assertEquals(listOf("walk", "walk (auto)", "other"), records.map { it.title })
        assertEquals(listOf(true, true, false), records.map { it.walking })
        assertTrue(records.all { it.version == 42L })
    }

    @Test
    fun `external records group per origin, ours excluded, biggest first`() {
        val origins = HealthInterop.externalByOrigin(
            listOf(
                HcExternalSteps("com.sec.android.app.shealth", 3_000),
                HcExternalSteps("com.callbackdev.tsteps", 8_000), // ours: out
                HcExternalSteps("com.fitbit.FitbitMobile", 4_988),
                HcExternalSteps("com.sec.android.app.shealth", 2_102),
                HcExternalSteps("com.quiet.app", 0) // nothing counted: out
            ),
            ownPackage = "com.callbackdev.tsteps"
        )
        assertEquals(
            listOf(
                OriginSteps("com.sec.android.app.shealth", "shealth", 5_102),
                OriginSteps("com.fitbit.FitbitMobile", "fitbitmobile", 4_988)
            ),
            origins
        )
    }

    @Test
    fun `label collisions keep both origins apart`() {
        val origins = HealthInterop.externalByOrigin(
            listOf(
                HcExternalSteps("com.vendor.tracker", 900),
                HcExternalSteps("org.other.tracker", 400)
            ),
            ownPackage = "com.callbackdev.tsteps"
        )
        assertEquals(listOf("tracker", "tracker_2"), origins.map { it.label })
    }
}
