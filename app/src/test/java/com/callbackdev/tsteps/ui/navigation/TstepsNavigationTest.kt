package com.callbackdev.tsteps.ui.navigation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import com.callbackdev.tsteps.ui.track.TrackOpenRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tab navigation over the real app shell (real NavHost, ViewModels and stores; on
 * the JVM there is no step sensor, so the editor renders its error document — the
 * assertions only touch each screen's "file name", which renders either way).
 */
@RunWith(RobolectricTestRunner::class)
class TstepsNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        // The editor screen reconciles background jobs on resume; give the JVM a
        // test WorkManager or the real one throws on first getInstance().
        WorkManagerTestInitHelper.initializeTestWorkManager(
            ApplicationProvider.getApplicationContext()
        )
    }

    private fun setApp() {
        compose.setContent {
            TstepsTheme {
                TstepsApp()
            }
        }
    }

    @After
    fun tearDown() {
        TrackOpenRequest.consume()
        runBlocking {
            ServiceLocator.trackingManager(
                ApplicationProvider.getApplicationContext()
            ).stop(0L)
        }
    }

    @Test
    fun `the tracking notification deep-links into the running process`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        runBlocking { ServiceLocator.trackingManager(context).start("walk", 0L) }
        TrackOpenRequest.request()
        setApp()
        compose.onNodeWithText("$ tsteps track").assertExists()
    }

    @Test
    fun `a stale deep-link after ^C lands on the editor`() {
        TrackOpenRequest.request() // no session running
        setApp()
        compose.onNodeWithText("steps_data.json").assertExists()
    }

    @Test
    fun `start destination is the steps editor`() {
        setApp()
        compose.onNodeWithText("steps_data.json").assertExists()
    }

    @Test
    fun `bottom bar switches between the four files`() {
        setApp()

        compose.onNodeWithText("Log").performClick()
        compose.onNodeWithText("steps_history.diff").assertExists()

        compose.onNodeWithText("Stats").performClick()
        compose.onNodeWithText("stats.md").assertExists()

        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("settings.config").assertExists()

        compose.onNodeWithText("Editor").performClick()
        compose.onNodeWithText("steps_data.json").assertExists()
    }
}
