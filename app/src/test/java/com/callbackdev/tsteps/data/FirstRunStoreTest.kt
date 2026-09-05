package com.callbackdev.tsteps.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fase 17: which installs get `$ tsteps init` and which inherit an answer. The whole
 * point is that an app already running must never be asked again — whatever it
 * answered the first time.
 */
class FirstRunStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun store() = FirstRunStore(
        PreferenceDataStoreFactory.create(scope = scope) {
            tmp.newFile("first-run-${System.nanoTime()}.preferences_pb")
        }
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `the shell draws nothing until the check has run`() = runBlocking {
        assertEquals(FirstRun.Unknown, store().state.first())
    }

    @Test
    fun `a fresh install is sent to init`() = runBlocking {
        val store = store()

        store.migrate(used = false)

        assertEquals(FirstRun.Pending, store.state.first())
    }

    @Test
    fun `an install already counting inherits the answer`() = runBlocking {
        val store = store()

        store.migrate(used = true)

        assertEquals(FirstRun.Done, store.state.first())
    }

    /** Once decided, never revisited: granting later must not re-run the decision. */
    @Test
    fun `the check runs exactly once`() = runBlocking {
        val store = store()
        store.migrate(used = false)

        store.migrate(used = true)

        assertEquals(FirstRun.Pending, store.state.first())
    }

    @Test
    fun `skipping init still counts as answering it`() = runBlocking {
        val store = store()
        store.migrate(used = false)

        store.markInitDone()

        assertEquals(FirstRun.Done, store.state.first())
    }

    /** Fase 22: the fact `shouldShowRequestPermissionRationale` cannot supply. */
    @Test
    fun `an install has not been asked for the sensor permission until it is`() =
        runBlocking {
            val store = store()
            assertEquals(false, store.sensorPermissionAsked.first())

            store.markSensorPermissionAsked()

            assertEquals(true, store.sensorPermissionAsked.first())
        }

    /** A grant resets the system's refusal, so it has to reset this too. */
    @Test
    fun `a grant forgets the ask`() = runBlocking {
        val store = store()
        store.markSensorPermissionAsked()

        store.clearSensorPermissionAsked()

        assertEquals(false, store.sensorPermissionAsked.first())
    }

    /** It is a fact of its own: neither answer touches the init decision. */
    @Test
    fun `the ask and the init answer are independent`() = runBlocking {
        val store = store()
        store.migrate(used = false)

        store.markSensorPermissionAsked()

        assertEquals(FirstRun.Pending, store.state.first())
    }
}
