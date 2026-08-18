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
    fun `daily commit - localized title, terminal body with hash and check`() {
        val content = StepsNotifications.dailyCommit(
            day(), UnitsSystem.METRIC, Locale.ENGLISH, resources
        )
        assertEquals("👣 Day committed — 11,204 steps", content.title)
        val lines = content.body.lines()
        assertEquals("commit ${CommitHash.of(LocalDate.parse("2026-08-17"))} (Mon 2026-08-17)", lines[0])
        assertEquals("11,204 steps · 8.3 km · 96 min · 421 kcal", lines[1])
        assertEquals("✓ goal check passed (11,204 ≥ 10,000)", lines[2])
    }

    @Test
    fun `daily commit without a goal has no check line, without weight no kcal`() {
        val content = StepsNotifications.dailyCommit(
            day(goalSteps = 0, goalMet = null, kcal = null),
            UnitsSystem.METRIC, Locale.ENGLISH, resources
        )
        assertFalse(content.body.contains("goal check"))
        assertFalse(content.body.contains("kcal"))
    }

    @Test
    fun `goal reached - check line, streak and the command hint`() {
        val content = StepsNotifications.goalReached(
            steps = 10_012, goalSteps = 10_000, streakDays = 7,
            locale = Locale.ENGLISH, resources = resources
        )
        assertEquals("✓ Goal reached — 10,012 steps", content.title)
        val lines = content.body.lines()
        assertEquals("✓ goal check passed (10,012 ≥ 10,000)", lines[0])
        assertEquals("streak: 7 days", lines[1])
        assertEquals("$ tsteps log --today", lines[2])
    }

    @Test
    fun `a one-day streak is not worth a line`() {
        val content = StepsNotifications.goalReached(
            10_012, 10_000, streakDays = 1, locale = Locale.ENGLISH, resources = resources
        )
        assertTrue(content.body.lines().none { it.startsWith("streak") })
    }
}
