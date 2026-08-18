package com.callbackdev.tsteps.ui.steps

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StepsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun snapshot() = TodaySnapshot(
        date = LocalDate.parse("2026-08-18"),
        steps = 8_432,
        goalSteps = 10_000,
        distanceMeters = 6_123.0,
        activeMinutes = 74,
        activeKcal = 327.0,
        hourlySteps = LongArray(24).toList(),
        streakDays = 6
    )

    private fun setContent(state: StepsUiState, onGrant: () -> Unit = {}) {
        compose.setContent {
            TstepsTheme {
                StepsScreen(state = state, onGrantPermission = onGrant)
            }
        }
    }

    @Test
    fun `the working tree renders count, tab and status bar`() {
        setContent(
            StepsUiState(
                snapshot = snapshot(),
                status = SensorStatus.OK,
                lastCommitDate = LocalDate.parse("2026-08-17")
            )
        )
        compose.onNodeWithText("steps_data.json").assertIsDisplayed()
        compose.onNodeWithText("\"count\": 8432,", substring = true).assertIsDisplayed()
        compose.onNodeWithText("⎇ main").assertIsDisplayed()
        compose.onNodeWithText("Last commit: 2026-08-17").assertIsDisplayed()
        compose.onNodeWithText("sensor: OK").assertIsDisplayed()
    }

    @Test
    fun `missing permission offers the tappable grant command`() {
        var granted = false
        setContent(
            StepsUiState(snapshot = snapshot(), status = SensorStatus.NO_PERMISSION),
            onGrant = { granted = true }
        )
        compose.onNodeWithText("// E: ACTIVITY_RECOGNITION permission not granted")
            .assertIsDisplayed()
        compose.onNode(hasText("$ tsteps grant activity-recognition") and hasClickAction())
            .assertIsDisplayed()
            .performClick()
        assertTrue(granted)
        compose.onNodeWithText("sensor: off").assertIsDisplayed()
    }

    @Test
    fun `the FAB starts a tracked walk`() {
        var started = false
        compose.setContent {
            TstepsTheme {
                StepsScreen(
                    state = StepsUiState(snapshot = snapshot(), status = SensorStatus.OK),
                    onStartTrack = { started = true }
                )
            }
        }
        compose.onNodeWithContentDescription("Start a tracked walk").performClick()
        assertTrue(started)
    }

    @Test
    fun `no FAB without the permission - the error document explains instead`() {
        setContent(StepsUiState(snapshot = snapshot(), status = SensorStatus.NO_PERMISSION))
        compose.onNodeWithContentDescription("Start a tracked walk").assertDoesNotExist()
    }

    @Test
    fun `imperial state renders the renamed key`() {
        setContent(
            StepsUiState(
                snapshot = snapshot(),
                status = SensorStatus.OK,
                units = UnitsSystem.IMPERIAL
            )
        )
        compose.onNodeWithText("\"distance_mi\"", substring = true).assertIsDisplayed()
    }
}
