package com.callbackdev.tsteps.ui.log

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.callbackdev.tsteps.data.LogEditorFile
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.domain.CommitHash
import com.callbackdev.tsteps.domain.WeekDay
import com.callbackdev.tsteps.domain.WeekDiff
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
    fun `the tab strip carries both files and the active one is the stored file`() {
        compose.setContent {
            TstepsTheme { LogScreen(state = state(), activeFile = LogEditorFile.HISTORY) }
        }
        compose.onNodeWithText("steps_history.diff").assertExists()
        compose.onNodeWithText("week.diff").assertExists()
        line("# Changes not yet committed (today)").assertExists()
    }

    @Test
    fun `selecting week diff asks for the other file`() {
        var selected: LogEditorFile? = null
        compose.setContent {
            TstepsTheme { LogScreen(state = state(), onSelectFile = { selected = it }) }
        }
        compose.onNodeWithText("week.diff").performClick()
        assertEquals(LogEditorFile.WEEK, selected)
    }

    @Test
    fun `the week tab renders the diff instead of the history`() {
        val comparison = WeekDiff.of(
            (0..6).map { back ->
                WeekDay(
                    date = LocalDate.parse("2026-08-10").plusDays(back.toLong()),
                    steps = 7_000, distanceMeters = 5_040.0, activeMinutes = 70,
                    walks = 1, goalMet = true
                )
            } + WeekDay(LocalDate.parse("2026-08-18"), 5_000, 3_600.0, 50, 1, null),
            LocalDate.parse("2026-08-18")
        )
        compose.setContent {
            TstepsTheme {
                LogScreen(
                    state = state().copy(weekDiff = comparison),
                    activeFile = LogEditorFile.WEEK
                )
            }
        }
        line("$ git diff @{last.week}").assertExists()
        line("@@ steps @@").assertExists()
        compose.onNodeWithText("# Changes not yet committed (today)", substring = true)
            .assertDoesNotExist()
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
