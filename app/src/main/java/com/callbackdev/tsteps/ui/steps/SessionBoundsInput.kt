package com.callbackdev.tsteps.ui.steps

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Outcome of parsing a session boundary edit. */
sealed interface SessionBounds {
    data class Value(val startMillis: Long, val endMillis: Long) : SessionBounds
    data class Invalid(val error: String) : SessionBounds
}

/**
 * The terminal input that edits an auto session's boundaries speaks the hunk
 * header's own syntax: `09:32..10:18` — git range dots, times of [date]'s wall
 * clock. Pure and strict: sessions live inside their day (detection clips at
 * midnight, so a crossing range is a typo, not a feature) and cannot end in the
 * future. Errors read like the settings file's: `// ERROR: …`.
 */
object SessionBoundsInput {

    private val Pattern = Regex("""\s*(\d{1,2}):(\d{2})\s*\.\.\s*(\d{1,2}):(\d{2})\s*""")

    fun parse(
        text: String,
        date: LocalDate,
        zone: ZoneId,
        nowMillis: Long
    ): SessionBounds {
        val match = Pattern.matchEntire(text)
            ?: return SessionBounds.Invalid("// ERROR: expected HH:mm..HH:mm")
        val (h1, m1, h2, m2) = match.destructured
        val start = timeOf(h1, m1) ?: return SessionBounds.Invalid("// ERROR: not a time of day")
        val end = timeOf(h2, m2) ?: return SessionBounds.Invalid("// ERROR: not a time of day")
        if (!end.isAfter(start)) {
            return SessionBounds.Invalid("// ERROR: the end must follow the start")
        }
        val startMillis = ZonedDateTime.of(date, start, zone).toInstant().toEpochMilli()
        val endMillis = ZonedDateTime.of(date, end, zone).toInstant().toEpochMilli()
        if (endMillis > nowMillis) {
            return SessionBounds.Invalid("// ERROR: the end is in the future")
        }
        return SessionBounds.Value(startMillis, endMillis)
    }

    private fun timeOf(hour: String, minute: String): LocalTime? {
        val h = hour.toIntOrNull() ?: return null
        val m = minute.toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return LocalTime.of(h, m)
    }
}
