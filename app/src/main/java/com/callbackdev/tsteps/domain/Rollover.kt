package com.callbackdev.tsteps.domain

import java.time.Instant
import java.time.ZoneId

/**
 * Midnight arithmetic for the day-commit job. Kept pure (zone is a parameter) so
 * DST transitions and timezone changes are unit tests: on a 23h/25h day the next
 * midnight is whatever the zone says it is, never "now + 24h".
 */
object Rollover {

    fun nextMidnightMillis(nowMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMillis)
            .atZone(zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
}
