package com.callbackdev.tsteps.notifications

import androidx.test.core.app.ApplicationProvider
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.data.local.DaySummaryEntity
import com.callbackdev.tsteps.domain.CommitHash
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StepsNotificationsTest {

    private val resources =
        ApplicationProvider.getApplicationContext<android.content.Context>().resources

    private val hash = CommitHash.of(LocalDate.parse("2026-08-17"))

    private fun day(goalSteps: Int = 10_000, goalMet: Boolean? = true, kcal: Double? = 421.0) =
        DaySummaryEntity(
            date = "2026-08-17",
            steps = 11_204,
            activeMinutes = 96,
            distanceMeters = 8_300.0,
            activeKcal = kcal,
            goalSteps = goalSteps,
            goalMet = goalMet
        )

    @Test
    fun `daily commit - localized title, one-line summary, git-log expansion`() {
        val content = StepsNotifications.dailyCommit(
            day(), UnitsSystem.METRIC, Locale.ENGLISH, resources
        )
        assertEquals("👣 Day committed — 11,204 steps", content.title)
        // Collapsed: what the title doesn't already say, verdict as a glyph.
        assertEquals("commit $hash · 8.3 km · 96 min · ✓", content.summary)
        assertFalse(content.summary.contains("\n"))
        // Expanded: the day as `git log` prints it, one fact per line.
        assertEquals(
            listOf(
                "commit $hash",
                "Date: Mon 2026-08-17",
                "11,204 steps · 8.3 km · 96 min · 421 kcal",
                "✓ goal check passed (11,204 ≥ 10,000)",
                "$ tsteps log"
            ),
            content.expanded.lines()
        )
    }

    @Test
    fun `a failed check keeps the summary factual with its glyph`() {
        val content = StepsNotifications.dailyCommit(
            day(goalMet = false), UnitsSystem.METRIC, Locale.ENGLISH, resources
        )
        assertEquals("commit $hash · 8.3 km · 96 min · ✗", content.summary)
        assertTrue(
            content.expanded.lines().contains("✗ goal check failed (11,204 < 10,000)")
        )
    }

    @Test
    fun `daily commit without a goal has no check, without weight no kcal`() {
        val content = StepsNotifications.dailyCommit(
            day(goalSteps = 0, goalMet = null, kcal = null),
            UnitsSystem.METRIC, Locale.ENGLISH, resources
        )
        assertEquals("commit $hash · 8.3 km · 96 min", content.summary)
        assertFalse(content.expanded.contains("goal check"))
        assertFalse(content.expanded.contains("kcal"))
        assertEquals("$ tsteps log", content.expanded.lines().last())
    }

    @Test
    fun `goal reached - check line collapsed, streak and hint when expanded`() {
        val content = StepsNotifications.goalReached(
            steps = 10_012, goalSteps = 10_000, streakDays = 7,
            locale = Locale.ENGLISH, resources = resources
        )
        assertEquals("✓ Goal reached — 10,012 steps", content.title)
        assertEquals("✓ goal check passed (10,012 ≥ 10,000)", content.summary)
        assertFalse(content.summary.contains("\n"))
        assertEquals(
            listOf(
                "✓ goal check passed (10,012 ≥ 10,000)",
                "streak: 7 days",
                "$ tsteps log --today"
            ),
            content.expanded.lines()
        )
    }

    @Test
    fun `a one-day streak is not worth a line`() {
        val content = StepsNotifications.goalReached(
            10_012, 10_000, streakDays = 1, locale = Locale.ENGLISH, resources = resources
        )
        assertTrue(content.expanded.lines().none { it.startsWith("streak") })
    }
}
