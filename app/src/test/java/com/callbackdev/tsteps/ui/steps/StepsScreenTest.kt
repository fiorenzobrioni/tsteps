package com.callbackdev.tsteps.ui.steps

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.callbackdev.tsteps.data.MainEditorFile
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.domain.SessionItem
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun setContent(
        state: StepsUiState,
        onGrant: () -> Unit = {},
        activeFile: MainEditorFile = MainEditorFile.JSON
    ) {
        compose.setContent {
            TstepsTheme {
                StepsScreen(state = state, activeFile = activeFile, onGrantPermission = onGrant)
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
    fun `the editor has two tabs and switching asks for the README`() {
        var selected: MainEditorFile? = null
        compose.setContent {
            TstepsTheme {
                StepsScreen(
                    state = StepsUiState(snapshot = snapshot(), status = SensorStatus.OK),
                    onSelectFile = { selected = it }
                )
            }
        }
        compose.onNodeWithText("steps_data.json").assertIsDisplayed()
        compose.onNodeWithText("README.md").performClick()
        assertEquals(MainEditorFile.README, selected)
    }

    @Test
    fun `the README tab renders the day as prose`() {
        setContent(
            StepsUiState(snapshot = snapshot(), status = SensorStatus.OK),
            activeFile = MainEditorFile.README
        )
        compose.onNodeWithText("# Tuesday 18 August 2026").assertIsDisplayed()
        compose.onNodeWithText("## Today").assertIsDisplayed()
    }

    @Test
    fun `a running session dresses the FAB and the status bar`() {
        compose.setContent {
            TstepsTheme {
                StepsScreen(
                    state = StepsUiState(snapshot = snapshot(), status = SensorStatus.OK),
                    trackingActive = true
                )
            }
        }
        compose.onNodeWithContentDescription("Open the running session").assertIsDisplayed()
        compose.onNodeWithText("▶ tracking").assertIsDisplayed()
        // the sensor chip yields to the running process
        compose.onNodeWithText("sensor: OK").assertDoesNotExist()
    }

    // --- Fase 11: session verbs in the working tree --------------------------

    private val rome = ZoneId.of("Europe/Rome")

    private fun millis(dateTime: String): Long =
        LocalDateTime.parse(dateTime).atZone(rome).toInstant().toEpochMilli()

    private fun autoSession() = SessionItem(
        id = 1L,
        startMillis = millis("2026-08-18T09:32:00"),
        endMillis = millis("2026-08-18T10:18:00"),
        type = "walk",
        steps = 4_820,
        distanceMeters = 3_400.0,
        activeMillis = 46 * 60_000L,
        avgCadenceSpm = 105,
        auto = true,
        startApprox = true,
        endApprox = true
    )

    private fun line(text: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToNodeAction())
            .performScrollToNode(hasText(text, substring = true))
        return compose.onNodeWithText(text, substring = true)
    }

    private fun sessionState() = StepsUiState(
        snapshot = snapshot(),
        status = SensorStatus.OK,
        sessions = listOf(autoSession()),
        expandedSessions = setOf(1L),
        zone = rome
    )

    @Test
    fun `rm arms first and removes only on the confirming tap`() {
        var removed: Long? = null
        compose.setContent {
            TstepsTheme {
                StepsScreen(state = sessionState(), onRemoveSession = { removed = it })
            }
        }
        line("[rm]").performClick()
        assertNull(removed)
        line("// tap again to remove").assertIsDisplayed()
        line("[rm]").performClick()
        assertEquals(1L, removed)
    }

    @Test
    fun `the range prompt edits an auto session's boundaries`() {
        var resized: Triple<Long, Long, Long>? = null
        compose.setContent {
            TstepsTheme {
                StepsScreen(
                    state = sessionState(),
                    onResizeSession = { id, start, end -> resized = Triple(id, start, end) }
                )
            }
        }
        line("\"start\": \"~09:32\"").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("09:20..10:18")
        compose.onNode(hasSetTextAction()).performImeAction()
        assertEquals(
            Triple(1L, millis("2026-08-18T09:20:00"), millis("2026-08-18T10:18:00")),
            resized
        )
    }

    @Test
    fun `esc leaves the boundaries untouched`() {
        var resized = false
        compose.setContent {
            TstepsTheme {
                StepsScreen(
                    state = sessionState(),
                    onResizeSession = { _, _, _ -> resized = true }
                )
            }
        }
        line("\"end\": \"~10:18\"").performClick()
        line("[esc]").performClick()
        line("\"start\": \"~09:32\"").assertIsDisplayed()
        assertTrue(!resized)
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
