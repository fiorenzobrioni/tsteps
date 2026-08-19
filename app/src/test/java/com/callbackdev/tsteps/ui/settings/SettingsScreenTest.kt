package com.callbackdev.tsteps.ui.settings

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.callbackdev.tsteps.data.AppSettings
import com.callbackdev.tsteps.data.HealthConnectSettings
import com.callbackdev.tsteps.healthconnect.HcAvailability
import com.callbackdev.tsteps.healthconnect.HcSectionStatus
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private class RecordedActions {
        var lineNumbers: Boolean? = null
        var goal: Int? = null
        var weight: Double? = Double.NaN // NaN = never called; null is a real value
        var unitsToggled = false
        var theme: String? = null
        var resetCalled = false

        var dailyCommit: Boolean? = null
        var autoDetect: Boolean? = null
        var healthConnect: Boolean? = null

        fun actions() = SettingsActions(
            onLineNumbers = { lineNumbers = it },
            onWordWrap = {},
            onDailyCommit = { dailyCommit = it },
            onGoalCheck = {},
            onDailyGoal = { goal = it },
            onAutoDetect = { autoDetect = it },
            onHealthConnect = { healthConnect = it },
            onWeight = { weight = it },
            onHeight = {},
            onToggleUnits = { unitsToggled = true },
            onToggleSessionMetric = {},
            onThemeProfile = { theme = it },
            onCycleWidgetOpacity = {},
            onOpenUrl = {},
            onReset = { resetCalled = true }
        )
    }

    private fun setContent(
        settings: AppSettings = AppSettings(dailyGoalSteps = 10_000, weightKg = 78.0, heightCm = 175),
        recorded: RecordedActions = RecordedActions(),
        hcStatus: HcSectionStatus = HcSectionStatus(availability = HcAvailability.AVAILABLE)
    ): RecordedActions {
        compose.setContent {
            TstepsTheme {
                SettingsScreen(
                    settings = settings,
                    actions = recorded.actions(),
                    hcStatus = hcStatus
                )
            }
        }
        return recorded
    }

    /** The config is longer than the JVM test viewport: scroll the canvas first. */
    private fun line(text: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText(text, substring = true))
        return compose.onNodeWithText(text, substring = true)
    }

    @Test
    fun `booleans flip on tap`() {
        val recorded = setContent()
        line("\"line_numbers\": false").performClick()
        assertEquals(true, recorded.lineNumbers)
    }

    @Test
    fun `health_connect explains itself in clear text and flips off by default`() {
        val recorded = setContent(settings = AppSettings())
        line("// on-device interop with other health apps").assertExists()
        line("// external steps are shown, never added to yours").assertExists()
        line("\"sync\": false").performClick()
        assertEquals(true, recorded.healthConnect)
    }

    @Test
    fun `without Health Connect on the device the section says so, no toggle`() {
        setContent(settings = AppSettings(), hcStatus = HcSectionStatus())
        line("// E: Health Connect is not available on this device").assertExists()
        compose.onNodeWithText("\"sync\"", substring = true).assertDoesNotExist()
    }

    @Test
    fun `connected status lists exactly what was granted`() {
        setContent(
            settings = AppSettings(healthConnect = HealthConnectSettings(sync = true)),
            hcStatus = HcSectionStatus(
                availability = HcAvailability.AVAILABLE,
                writeSteps = true,
                readSteps = true
            )
        )
        line("// connected: writes steps · reads other apps").assertExists()
    }

    @Test
    fun `sync on with every permission revoked shows the red grant line`() {
        setContent(
            settings = AppSettings(healthConnect = HealthConnectSettings(sync = true)),
            hcStatus = HcSectionStatus(availability = HcAvailability.AVAILABLE)
        )
        line("// ERROR: no permission granted — tap to grant").assertExists()
    }

    @Test
    fun `auto_detect defaults to false and flips on tap`() {
        val recorded = setContent(settings = AppSettings())
        line("\"auto_detect\": false").performClick()
        assertEquals(true, recorded.autoDetect)
    }

    @Test
    fun `units cycle on tap`() {
        val recorded = setContent()
        line("\"system\": \"metric\"").performClick()
        assertTrue(recorded.unitsToggled)
    }

    @Test
    fun `active profile cycles, list entries activate directly`() {
        val recorded = setContent()
        line("\"active_profile\": \"Obsidian\"").performClick()
        assertEquals("Dracula", recorded.theme)
        line("\"Monokai\"").performClick()
        assertEquals("Monokai", recorded.theme)
    }

    @Test
    fun `goal edits through the terminal input`() {
        val recorded = setContent()
        line("\"daily_steps\": 10000").performClick()
        val input = compose.onNode(hasSetTextAction())
        input.performTextClearance()
        input.performTextInput("12000")
        input.performImeAction()
        assertEquals(12_000, recorded.goal)
        // The input closed: the plain line is back.
        line("\"daily_steps\": 10000").assertIsDisplayed()
    }

    @Test
    fun `an out-of-range goal shows the error and saves nothing`() {
        val recorded = setContent()
        line("\"daily_steps\": 10000").performClick()
        val input = compose.onNode(hasSetTextAction())
        input.performTextClearance()
        input.performTextInput("999999")
        input.performImeAction()
        assertNull(recorded.goal)
        line("// ERROR: expected 0..100000").assertIsDisplayed()
    }

    @Test
    fun `esc cancels the edit without saving`() {
        val recorded = setContent()
        line("\"daily_steps\": 10000").performClick()
        line("[esc]").performClick()
        assertNull(recorded.goal)
        line("\"daily_steps\": 10000").assertIsDisplayed()
    }

    @Test
    fun `an empty weight submit clears the value - empty hides kcal`() {
        val recorded = setContent()
        line("\"weight_kg\": 78.0").performClick()
        val input = compose.onNode(hasSetTextAction())
        input.performTextClearance()
        input.performImeAction()
        assertNull(recorded.weight)
    }

    @Test
    fun `a cleared profile value renders as JSON null`() {
        setContent(settings = AppSettings())
        line("\"weight_kg\": null").assertIsDisplayed()
        line("\"height_cm\": null").assertIsDisplayed()
    }

    @Test
    fun `reset is a two-tap git command`() {
        val recorded = setContent()
        val command = line("git restore settings.config")
        command.performClick()
        assertFalse(recorded.resetCalled)
        line("tap again to confirm").assertIsDisplayed()
        command.performClick()
        assertTrue(recorded.resetCalled)
    }

    @Test
    fun `notification toggles flip and the armed status line rides along`() {
        val recorded = setContent()
        line("// rides the midnight rollover and the step sync").assertIsDisplayed()
        line("\"daily_commit\": true").performClick()
        assertEquals(false, recorded.dailyCommit)
    }

    @Test
    fun `a missing permission is a red tappable error line`() {
        var tapped = false
        compose.setContent {
            TstepsTheme {
                SettingsScreen(
                    settings = AppSettings(),
                    actions = RecordedActions().actions(),
                    notifState = NotifLineState.MissingPermission,
                    onNotifLine = { tapped = true }
                )
            }
        }
        line("// ERROR: notifications permission missing — tap to grant").performClick()
        assertTrue(tapped)
    }
}
