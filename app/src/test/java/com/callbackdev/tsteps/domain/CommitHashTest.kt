package com.callbackdev.tsteps.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitHashTest {

    @Test
    fun `hash is stable across calls and devices - it only depends on the date`() {
        val date = LocalDate.parse("2026-08-17")
        assertEquals(CommitHash.of(date), CommitHash.of(LocalDate.parse("2026-08-17")))
    }

    @Test
    fun `hash is 7 lowercase hex chars, git-abbrev style`() {
        val hash = CommitHash.of(LocalDate.parse("2026-08-17"))
        assertEquals(7, hash.length)
        assertTrue(hash.all { it in "0123456789abcdef" })
    }

    @Test
    fun `different days get different hashes`() {
        assertNotEquals(
            CommitHash.of(LocalDate.parse("2026-08-16")),
            CommitHash.of(LocalDate.parse("2026-08-17"))
        )
    }
}
