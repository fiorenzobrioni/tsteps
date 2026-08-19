package com.callbackdev.tsteps.ui.steps

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionBoundsInputTest {

    private val rome = ZoneId.of("Europe/Rome")
    private val date: LocalDate = LocalDate.parse("2026-08-18")
    private val noon = millis("2026-08-18T12:00:00")

    private fun millis(dateTime: String): Long =
        LocalDateTime.parse(dateTime).atZone(rome).toInstant().toEpochMilli()

    private fun parse(text: String, now: Long = noon) =
        SessionBoundsInput.parse(text, date, rome, now)

    @Test
    fun `a well-formed range parses to epoch millis of the day`() {
        val value = parse("09:32..10:18") as SessionBounds.Value
        assertEquals(millis("2026-08-18T09:32:00"), value.startMillis)
        assertEquals(millis("2026-08-18T10:18:00"), value.endMillis)
    }

    @Test
    fun `spaces around the dots are tolerated`() {
        assertTrue(parse(" 9:05 .. 10:00 ") is SessionBounds.Value)
    }

    @Test
    fun `anything that is not a range is rejected with the expected shape`() {
        val invalid = parse("09:32-10:18") as SessionBounds.Invalid
        assertEquals("// ERROR: expected HH:mm..HH:mm", invalid.error)
        assertTrue(parse("") is SessionBounds.Invalid)
        assertTrue(parse("banana") is SessionBounds.Invalid)
    }

    @Test
    fun `impossible times of day are rejected`() {
        assertTrue(parse("25:00..26:00") is SessionBounds.Invalid)
        assertTrue(parse("09:61..10:00") is SessionBounds.Invalid)
    }

    @Test
    fun `the end must follow the start - no midnight crossing`() {
        val invalid = parse("10:18..09:32") as SessionBounds.Invalid
        assertEquals("// ERROR: the end must follow the start", invalid.error)
        assertTrue(parse("10:00..10:00") is SessionBounds.Invalid)
    }

    @Test
    fun `the end cannot sit in the future`() {
        val invalid = parse("11:00..13:00") as SessionBounds.Invalid
        assertEquals("// ERROR: the end is in the future", invalid.error)
    }
}
