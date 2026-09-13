package com.callbackdev.tsteps.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GapsTest {

    private fun date(iso: String) = LocalDate.parse(iso)

    @Test
    fun `two days apart leaves one day in between`() {
        assertEquals(
            Gap(date("2026-09-11"), date("2026-09-11")),
            Gaps.between(date("2026-09-12"), date("2026-09-10"))
        )
    }

    @Test
    fun `consecutive commits have no gap between them`() {
        assertNull(Gaps.between(date("2026-09-12"), date("2026-09-11")))
    }

    @Test
    fun `the same day twice is not a gap either`() {
        assertNull(Gaps.between(date("2026-09-12"), date("2026-09-12")))
    }

    /** A stream walked in the wrong order answers empty, never negatively. */
    @Test
    fun `dates out of order produce nothing`() {
        assertNull(Gaps.between(date("2026-09-10"), date("2026-09-12")))
    }

    @Test
    fun `the device case - two days between Wednesday and Saturday`() {
        val gap = Gaps.between(date("2026-09-12"), date("2026-09-09"))!!
        assertEquals(date("2026-09-10"), gap.from)
        assertEquals(date("2026-09-11"), gap.to)
        assertEquals(2, gap.days)
    }

    @Test
    fun `a window reports every hole it contains`() {
        val covered = setOf(date("2026-09-07"), date("2026-09-09"), date("2026-09-13"))

        val gaps = Gaps.inWindow(covered, date("2026-09-07"), date("2026-09-13"))

        assertEquals(
            listOf(
                Gap(date("2026-09-08"), date("2026-09-08")),
                Gap(date("2026-09-10"), date("2026-09-12"))
            ),
            gaps
        )
        assertEquals(4, Gaps.daysMissing(covered, date("2026-09-07"), date("2026-09-13")))
    }

    @Test
    fun `a fully covered window has no holes`() {
        val covered = (7..9).map { date("2026-09-0$it") }.toSet()
        assertEquals(emptyList<Gap>(), Gaps.inWindow(covered, date("2026-09-07"), date("2026-09-09")))
        assertEquals(0, Gaps.daysMissing(covered, date("2026-09-07"), date("2026-09-09")))
    }

    @Test
    fun `an empty history makes the whole window one gap`() {
        val gaps = Gaps.inWindow(emptySet(), date("2026-09-07"), date("2026-09-13"))
        assertEquals(listOf(Gap(date("2026-09-07"), date("2026-09-13"))), gaps)
        assertEquals(7, gaps.single().days)
    }

    @Test
    fun `a window that ends before it starts is not a gap`() {
        assertEquals(
            emptyList<Gap>(),
            Gaps.inWindow(emptySet(), date("2026-09-13"), date("2026-09-07"))
        )
    }
}
