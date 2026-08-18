package com.callbackdev.tsteps.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.callbackdev.tsteps.domain.TrackerState
import kotlinx.coroutines.flow.first

private val Context.trackerDataStore by preferencesDataStore(name = "tracker_state")

/**
 * Persistence for the step counter's continuity anchor ([TrackerState]). Its own
 * DataStore, deliberately not a settings key: this is sensor state the user never
 * sees or resets — `$ git restore settings.config` must not lose step continuity.
 * Null until the very first sensor reading.
 */
class TrackerStateStore(private val dataStore: DataStore<Preferences>) {

    suspend fun read(): TrackerState? {
        val prefs = dataStore.data.first()
        val bootCount = prefs[BootCount] ?: return null
        val lastCumulative = prefs[LastCumulative] ?: return null
        val lastTimestamp = prefs[LastTimestampMillis] ?: return null
        return TrackerState(bootCount, lastCumulative, lastTimestamp)
    }

    suspend fun write(state: TrackerState) {
        dataStore.edit {
            it[BootCount] = state.bootCount
            it[LastCumulative] = state.lastCumulative
            it[LastTimestampMillis] = state.lastTimestampMillis
        }
    }

    companion object {
        private val BootCount = intPreferencesKey("boot_count")
        private val LastCumulative = longPreferencesKey("last_cumulative")
        private val LastTimestampMillis = longPreferencesKey("last_timestamp_millis")

        fun create(context: Context) = TrackerStateStore(context.trackerDataStore)
    }
}
