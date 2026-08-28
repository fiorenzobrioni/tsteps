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
import com.callbackdev.tsteps.export.ExportFormat
import com.callbackdev.tsteps.export.ExportResult
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
        var stride: Int? = -1 // -1 = never called; null is a real value
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
            onStride = { stride = it },
            onToggleUnits = { unitsToggled = true },
            onToggleSessionMetric = {},
            onThemeProfile = { theme = it },
            onCycleWidgetOpacity = {},
            onOpenUrl = {},
            onReset = { resetCalled = true }
        )
    }

    private var exported: ExportFormat? = null

    private fun setContent(
        settings: AppSettings = AppSettings(dailyGoalSteps = 10_000, weightKg = 78.0, heightCm = 175),
        recorded: RecordedActions = RecordedActions(),
        hcStatus: HcSectionStatus = HcSectionStatus(availability = HcAvailability.AVAILABLE),
        exportState: ExportState = ExportState.Idle
    ): RecordedActions {
        compose.setContent {
            TstepsTheme {
                SettingsScreen(
                    settings = settings,
                    actions = recorded.actions(),
                    hcStatus = hcStatus,
                    exportState = exportState,
                    onExport = { exported = it }
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
        line("\"stride_cm\": null").assertIsDisplayed()
    }

    @Test
    fun `stride_cm edits through the terminal input like every free number`() {
        val recorded = setContent()
        line("\"stride_cm\": null").performClick()
        val input = compose.onNode(hasSetTextAction())
        input.performTextInput("78")
        input.performImeAction()
        assertEquals(78, recorded.stride)
    }

    @Test
    fun `an out-of-range stride shows the error and saves nothing`() {
        val recorded = setContent()
        line("\"stride_cm\": null").performClick()
        val input = compose.onNode(hasSetTextAction())
        input.performTextInput("300")
        input.performImeAction()
        assertEquals(-1, recorded.stride)
        line("// ERROR: expected 30..120").assertIsDisplayed()
    }

    @Test
    fun `without an override height_cm still owns the stride estimate`() {
        setContent(settings = AppSettings(heightCm = 175))
        line("// empty uses the 0.72 m stride").assertIsDisplayed()
    }

    @Test
    fun `height_cm stops claiming a job stride_cm has taken over`() {
        setContent(settings = AppSettings(heightCm = 175, strideCm = 78))
        line("// unused: stride_cm overrides it").assertIsDisplayed()
    }

    @Test
    fun `with no goal the settings file names the number the JSON offers`() {
        setContent(settings = AppSettings())
        line("// no check · 8000 suggested").assertIsDisplayed()
    }

    @Test
    fun `with a goal set the hint is how to switch the check off`() {
        setContent(settings = AppSettings(dailyGoalSteps = 10_000))
        line("// 0 disables the check").assertIsDisplayed()
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
    fun `export is two commands, one per format, and runs on a single tap`() {
        setContent()
        line("$ tsteps export --json").performClick()
        assertEquals(ExportFormat.JSON, exported)
        line("$ tsteps export --csv").performClick()
        assertEquals(ExportFormat.CSV, exported)
        // Non-destructive: unlike the reset, no confirm to arm.
        compose.onNodeWithText("tap again to confirm", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a finished export prints every file it wrote and the tally`() {
        setContent(
            exportState = ExportState.Done(
                ExportResult.Written(
                    files = listOf("tsteps-days-2026-08-20.csv", "tsteps-sessions-2026-08-20.csv"),
                    days = 128,
                    sessions = 42
                )
            )
        )
        line("// wrote Downloads/tsteps-days-2026-08-20.csv").assertIsDisplayed()
        line("// wrote Downloads/tsteps-sessions-2026-08-20.csv").assertIsDisplayed()
        line("// 128 days · 42 sessions").assertIsDisplayed()
    }

    @Test
    fun `an empty history is an answer, not an error`() {
        setContent(exportState = ExportState.Done(ExportResult.Empty))
        line("// nothing to export yet").assertIsDisplayed()
    }

    /**
     * The sentence is the reader's, the evidence is the platform's. What broke
     * usually arrives as an errno or a class name that nothing can translate, so
     * the line says what happened in words and prints the exception's own after
     * it — the same split `sky.crontab` makes between a verdict and its number.
     */
    @Test
    fun `a failed export reads like a compiler message`() {
        setContent(
            exportState = ExportState.Done(ExportResult.Failed("Downloads is not writable"))
        )
        line("// ERROR: export failed — Downloads is not writable").assertIsDisplayed()
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
