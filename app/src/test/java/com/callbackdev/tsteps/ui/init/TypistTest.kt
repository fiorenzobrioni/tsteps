package com.callbackdev.tsteps.ui.init

import androidx.compose.ui.text.AnnotatedString
import com.callbackdev.tsteps.ui.components.CodeLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The typing animation asserted instead of watched (Fase 23).
 *
 * [Typist] is a pure timeline on purpose: an animation that can only be judged by
 * looking at it is an animation nobody can keep honest, and the two things that
 * actually matter here — that the cursor is where the writing is, and that the run
 * is short — are both arithmetic.
 */
class TypistTest {

    private val cursor = AnnotatedString(BlockCursor)

    // Every beat here is written out rather than defaulted: these tests are about
    // the timeline's arithmetic, and the defaults are the screen's taste in rhythm
    // (they moved once already, on a device round).

    private fun line(text: String, indent: Int = 0, onClick: (() -> Unit)? = null) =
        CodeLine(AnnotatedString(text), indent, onClick)

    /** 13 characters at 10ms + a 100ms beat, then 7 at 5ms: 265ms in all. */
    private val typist = Typist(
        listOf(
            TypedLine(line("$ tsteps init"), msPerChar = 10, pauseAfterMs = 100),
            TypedLine(line("# ready"), msPerChar = 5, pauseAfterMs = 0)
        )
    )

    @Test
    fun `the run lasts exactly as long as its parts`() {
        assertEquals(13L * 10 + 100 + 7L * 5, typist.totalMs)
    }

    @Test
    fun `at time zero there is a cursor and nothing else`() {
        val shown = typist.linesAt(0, cursor)

        assertEquals(1, shown.size)
        assertEquals(BlockCursor, (shown.single() as CodeLine).text.text)
    }

    @Test
    fun `the cursor sits on the character being written`() {
        val shown = typist.linesAt(30, cursor).single() as CodeLine

        assertEquals("$ t$BlockCursor", shown.text.text)
    }

    /** A line still being written is not an answer yet: nothing to tap. */
    @Test
    fun `a half-written line carries no tap`() {
        val half = Typist(listOf(TypedLine(line("> skip", onClick = {}), msPerChar = 10)))

        assertNull((half.linesAt(20, cursor).single() as CodeLine).onClick)
    }

    /** The beat after a line is the cursor parked at its end, not a jump. */
    @Test
    fun `the beat after a line holds the cursor where the line ended`() {
        val shown = typist.linesAt(13L * 10 + 50, cursor)

        assertEquals(1, shown.size)
        assertEquals("$ tsteps init$BlockCursor", (shown.single() as CodeLine).text.text)
    }

    @Test
    fun `lines that have not started are absent, not blank`() {
        assertEquals(1, typist.linesAt(13L * 10 + 50, cursor).size)
        assertEquals(2, typist.linesAt(13L * 10 + 100 + 5, cursor).size)
    }

    @Test
    fun `at the end every line is whole and the cursor has left them`() {
        val shown = typist.linesAt(typist.totalMs, cursor)

        assertEquals(
            listOf("$ tsteps init", "# ready"),
            shown.map { (it as CodeLine).text.text }
        )
    }

    /** Past the end is the end: a late frame must not read off the script. */
    @Test
    fun `a frame after the last one changes nothing`() {
        assertEquals(
            typist.linesAt(typist.totalMs, cursor).size,
            typist.linesAt(typist.totalMs * 4, cursor).size
        )
    }

    @Test
    fun `a blank line costs nothing and still appears`() {
        val withGap = Typist(
            listOf(
                TypedLine(line("#"), msPerChar = 10, pauseAfterMs = 0),
                TypedLine(line(""), pauseAfterMs = 0),
                TypedLine(line("> skip"), msPerChar = 10, pauseAfterMs = 0)
            )
        )

        assertEquals(10L + 60L, withGap.totalMs)
        // The blank arrives with the line above it, not one beat later.
        assertTrue(withGap.linesAt(10, cursor).size >= 2)
    }
}
