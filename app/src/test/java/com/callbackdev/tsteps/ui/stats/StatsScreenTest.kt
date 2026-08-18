package com.callbackdev.tsteps.ui.stats

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.callbackdev.tsteps.domain.Heatmap
import com.callbackdev.tsteps.domain.Records
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StatsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.parse("2026-08-18")

    private fun state() = StatsUiState(
        grid = Heatmap.build(mapOf(LocalDate.parse("2026-08-17") to 11_204L), today),
        streak = StreakInfo(6, 19),
        bestDay = LocalDate.parse("2026-07-12") to 14_823L,
        bestWeek = Records.BestWeek(2026, 33, 52_340),
        committedDays = 42
    )

    private fun line(text: String) = run {
        compose.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText(text, substring = true))
        compose.onNodeWithText(text, substring = true)
    }

    @Test
    fun `the stats file renders tab, graph heading and status bar`() {
        compose.setContent { TstepsTheme { StatsScreen(state = state()) } }
        compose.onNodeWithText("stats.md").assertIsDisplayed()
        line("## contributions (last 12 weeks)").assertIsDisplayed()
        compose.onNodeWithText("ro").assertIsDisplayed()
        compose.onNodeWithText("42 days").assertIsDisplayed()
    }

    @Test
    fun `tapping a tag row asks to open its commit`() {
        var opened: LocalDate? = null
        compose.setContent {
            TstepsTheme { StatsScreen(state = state(), onOpenCommit = { opened = it }) }
        }
        line("best-day").performClick()
        assertEquals(LocalDate.parse("2026-07-12"), opened)
    }
}
