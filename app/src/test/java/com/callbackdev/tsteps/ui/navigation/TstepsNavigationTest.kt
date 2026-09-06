package com.callbackdev.tsteps.ui.navigation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.ui.init.SetupFile
import com.callbackdev.tsteps.ui.theme.TstepsTheme
import com.callbackdev.tsteps.ui.track.TrackOpenRequest
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.callbackdev.tsteps.data.FirstRunStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tab navigation over the real app shell (real NavHost, ViewModels and stores; on
 * the JVM there is no step sensor, so the editor renders its error document — the
 * assertions only touch each screen's "file name", which renders either way).
 *
 * Since Fase 17 the shell also decides between `$ tsteps init` and the workspace, so
 * the first-run store is injected per test: the decision has to be this test's input,
 * not whatever a previous test left on disk.
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

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** [used] = an install that predates `$ tsteps init`, i.e. one already running. */
    private fun firstRunStore(used: Boolean): FirstRunStore {
        val store = FirstRunStore(
            PreferenceDataStoreFactory.create(scope = scope) {
                tmp.newFile("first-run-${System.nanoTime()}.preferences_pb")
            }
        )
        // What MainActivity does at startup, before the shell can draw anything
        runBlocking { store.migrate(used = used) }
        ServiceLocator.overrideForTests(firstRunStore = store)
        return store
    }

    private fun setApp(used: Boolean = true) {
        firstRunStore(used)
        compose.setContent {
            TstepsTheme {
                TstepsApp()
            }
        }
    }

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() {
        TrackOpenRequest.consume()
        ServiceLocator.overrideForTests()
        scope.cancel()
        runBlocking {
            ServiceLocator.trackingManager(
                ApplicationProvider.getApplicationContext()
            ).stop(0L)
        }
    }

    /**
     * Fase 17: a fresh install is asked for the permission before it gets a
     * workspace.
     *
     * The session is identified by its tab and not by the `$ tsteps init` line:
     * since Fase 23 the transcript prints itself and keeps its last line in sight,
     * so on a screen too short for it — which Robolectric's default device is — the
     * command has honestly scrolled off the top. The tab is the file, and the file
     * is the fact this test is about.
     */
    @Test
    fun `a fresh install lands on tsteps init`() {
        setApp(used = false)

        compose.onNodeWithText(SetupFile).assertExists()
        compose.onNodeWithText("steps_data.json").assertDoesNotExist()
    }

    /** Skipping is an answer: the workspace opens on the document that says so. */
    @Test
    fun `skipping init opens the workspace anyway`() {
        firstRunStore(used = false)
        compose.setContent { TstepsTheme { TstepsApp() } }

        compose.onNodeWithText("> skip").performClick()

        // The answer is a DataStore write, so the swap lands a beat after the tap
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("steps_data.json").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("tsteps init", substring = true).assertDoesNotExist()
    }

    /** Fase 17: the hint reaches HELP.md, which lives behind the Settings tab. */
    @Test
    fun `the help hint opens the help file`() {
        setApp()

        compose.onNodeWithText("// new here? open HELP.md").performClick()

        compose.onNodeWithText("# tsteps").assertExists()
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
