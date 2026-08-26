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
        return TrackerState(
            bootCount = bootCount,
            lastCumulative = lastCumulative,
            lastTimestampMillis = lastTimestamp,
            // Absent on an anchor written before the field existed. Falling back to
            // the step timestamp is the old behaviour exactly, so the upgrade
            // changes nothing until the next sample writes a real read time — and
            // it never invents a freshness the install cannot vouch for.
            lastReadMillis = prefs[LastReadMillis] ?: lastTimestamp
        )
    }

    suspend fun write(state: TrackerState) {
        dataStore.edit {
            it[BootCount] = state.bootCount
            it[LastCumulative] = state.lastCumulative
            it[LastTimestampMillis] = state.lastTimestampMillis
            it[LastReadMillis] = state.lastReadMillis
        }
    }

    companion object {
        private val BootCount = intPreferencesKey("boot_count")
        private val LastCumulative = longPreferencesKey("last_cumulative")
        private val LastTimestampMillis = longPreferencesKey("last_timestamp_millis")
        private val LastReadMillis = longPreferencesKey("last_read_millis")

        fun create(context: Context) = TrackerStateStore(context.trackerDataStore)
    }
}
