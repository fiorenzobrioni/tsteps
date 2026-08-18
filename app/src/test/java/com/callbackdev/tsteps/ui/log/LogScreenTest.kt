package com.callbackdev.tsteps.ui.log

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.domain.CommitHash
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LogScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun state(expanded: Set<LocalDate> = emptySet()) = LogUiState(
        today = UncommittedToday(
            date = LocalDate.parse("2026-08-18"),
            steps = 8_432,
            distanceMeters = 6_123.0,
            activeMinutes = 74
        ),
        days = listOf(
            CommitDay(LocalDate.parse("2026-08-17"), 11_204, 96, 8_300.0, 421.0, 10_000, true)
        ),
        expanded = expanded,
        bestDay = LocalDate.parse("2026-08-17"),
        units = UnitsSystem.METRIC
    )

    private fun line(text: String) = run {
        compose.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText(text, substring = true))
        compose.onNodeWithText(text, substring = true)
    }

    @Test
    fun `the log renders tab, uncommitted section, commit and status bar`() {
        compose.setContent { TstepsTheme { LogScreen(state = state()) } }
        compose.onNodeWithText("steps_history.diff").assertExists()
        line("# Changes not yet committed (today)").assertExists()
        line("commit " + CommitHash.of(LocalDate.parse("2026-08-17"))).assertExists()
        line("✓ goal check passed (11,204 ≥ 10,000)").assertExists()
        compose.onNodeWithText("1 commits").assertExists()
        compose.onNodeWithText(
            "HEAD → " + CommitHash.of(LocalDate.parse("2026-08-17"))
        ).assertExists()
    }

    @Test
    fun `tapping a commit header asks to toggle that day`() {
        var toggled: LocalDate? = null
        compose.setContent {
            TstepsTheme { LogScreen(state = state(), onToggle = { toggled = it }) }
        }
        line("commit " + CommitHash.of(LocalDate.parse("2026-08-17"))).performClick()
        assertEquals(LocalDate.parse("2026-08-17"), toggled)
    }

    @Test
    fun `an expanded day shows its diff body`() {
        compose.setContent {
            TstepsTheme { LogScreen(state = state(expanded = setOf(LocalDate.parse("2026-08-17")))) }
        }
        line("+ \"steps\": 11204").assertExists()
        line("@@ 2026-08-17 @@").assertExists()
    }
}
