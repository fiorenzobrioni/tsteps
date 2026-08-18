package com.callbackdev.tsteps.ui.track

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.callbackdev.tsteps.data.AppSettings
import com.callbackdev.tsteps.data.TrackingState
import com.callbackdev.tsteps.domain.LiveSessionTracker
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TrackScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun state(paused: Boolean = false): TrackingState {
        var session = LiveSessionTracker.start("walk", 0L)
        session = LiveSessionTracker.onReading(session, 1_000L)
        session = LiveSessionTracker.onReading(session, 1_500L)
        if (paused) session = LiveSessionTracker.pause(session, 60_000L)
        return TrackingState(session = session, strideMeters = 0.72)
    }

    private fun setContent(
        state: TrackingState?,
        onPause: () -> Unit = {},
        onResume: () -> Unit = {},
        onStop: () -> Unit = {}
    ) {
        compose.setContent {
            TstepsTheme {
                TrackScreen(
                    state = state,
                    settings = AppSettings(),
                    nowMillis = 5 * 60_000L,
                    onPause = onPause,
                    onResume = onResume,
                    onStop = onStop
                )
            }
        }
    }

    @Test
    fun `the process runs in a terminal tab with self-explaining controls`() {
        setContent(state())
        compose.onNodeWithText("$ tsteps track").assertIsDisplayed()
        compose.onNodeWithText("[ ^Z pause ]").assertIsDisplayed()
        compose.onNodeWithText("[ ^C stop ]").assertIsDisplayed()
    }

    @Test
    fun `pause button dispatches pause`() {
        var paused = false
        setContent(state(), onPause = { paused = true })
        compose.onNodeWithText("[ ^Z pause ]").performClick()
        assertTrue(paused)
    }

    @Test
    fun `while paused the button reads resume and dispatches it`() {
        var resumed = false
        setContent(state(paused = true), onResume = { resumed = true })
        compose.onNodeWithText("[ fg resume ]").performClick()
        assertTrue(resumed)
    }

    @Test
    fun `stop needs the two-tap confirm`() {
        var stopped = false
        setContent(state(), onStop = { stopped = true })
        val stop = compose.onNodeWithText("[ ^C stop ]")
        stop.performClick()
        assertFalse(stopped)
        compose.onNodeWithText("// tap ^C again to stop").assertIsDisplayed()
        stop.performClick()
        assertTrue(stopped)
    }

    @Test
    fun `no process still shows the tab and an honest comment`() {
        setContent(state = null)
        compose.onNodeWithText("$ tsteps track").assertIsDisplayed()
        compose.onNodeWithText("// no process running").assertIsDisplayed()
    }
}
