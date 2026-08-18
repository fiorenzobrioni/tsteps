package com.callbackdev.tsteps.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.callbackdev.tsteps.domain.TrackerState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrackerStateStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun store(file: File) = TrackerStateStore(
        PreferenceDataStoreFactory.create(scope = scope) { file }
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `no anchor before the first sensor reading`() = runBlocking {
        assertNull(store(tmp.newFile("t.preferences_pb")).read())
    }

    @Test
    fun `anchor round-trips`() = runBlocking {
        val store = store(tmp.newFile("t.preferences_pb"))
        val state = TrackerState(bootCount = 12, lastCumulative = 987_654L, lastTimestampMillis = 1_755_500_000_000L)
        store.write(state)
        assertEquals(state, store.read())
    }

    @Test
    fun `writes overwrite the previous anchor`() = runBlocking {
        val store = store(tmp.newFile("t.preferences_pb"))
        store.write(TrackerState(1, 10L, 100L))
        store.write(TrackerState(2, 20L, 200L))
        assertEquals(TrackerState(2, 20L, 200L), store.read())
    }
}
