package com.callbackdev.tsteps.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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

    private fun raw(file: File): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { file }

    private fun store(file: File) = TrackerStateStore(raw(file))

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

    @Test
    fun `the read instant round-trips apart from the step instant`() = runBlocking {
        val store = store(tmp.newFile("t.preferences_pb"))
        val state = TrackerState(
            bootCount = 3,
            lastCumulative = 4_200L,
            lastTimestampMillis = 1_755_500_000_000L,
            lastReadMillis = 1_755_507_200_000L
        )
        store.write(state)
        assertEquals(state, store.read())
    }

    /**
     * An anchor written before `last_read_millis` existed. Falling back to the
     * step instant is the old behaviour exactly: the upgrade cannot make a widget
     * look fresher than the install can vouch for, and one sample replaces it.
     */
    @Test
    fun `an anchor from before the read instant existed still reads`() = runBlocking {
        val dataStore = raw(tmp.newFile("t.preferences_pb"))
        dataStore.edit {
            it[intPreferencesKey("boot_count")] = 5
            it[longPreferencesKey("last_cumulative")] = 900L
            it[longPreferencesKey("last_timestamp_millis")] = 1_700_000_000_000L
        }
        val restored = TrackerStateStore(dataStore).read()
        assertEquals(
            TrackerState(
                bootCount = 5,
                lastCumulative = 900L,
                lastTimestampMillis = 1_700_000_000_000L,
                lastReadMillis = 1_700_000_000_000L
            ),
            restored
        )
    }
}
