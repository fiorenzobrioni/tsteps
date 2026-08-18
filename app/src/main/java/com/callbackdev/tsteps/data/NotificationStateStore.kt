package com.callbackdev.tsteps.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.LocalDate
import kotlinx.coroutines.flow.first

private val Context.notificationStateDataStore by preferencesDataStore(name = "notif_state")

/**
 * Dedup fingerprints for the notifications (tweather's `alerts` store, same
 * idea): the goal-check notification is edge-triggered — it fires once when
 * today's count first crosses the goal and re-arms at midnight, because the
 * date changes. Its own store, not settings: this is machine state the user
 * never edits, and `git restore settings.config` must not re-fire anything.
 */
class NotificationStateStore(private val dataStore: DataStore<Preferences>) {

    suspend fun goalNotifiedDate(): LocalDate? =
        dataStore.data.first()[GoalNotifiedDate]?.let { LocalDate.parse(it) }

    suspend fun markGoalNotified(date: LocalDate) {
        dataStore.edit { it[GoalNotifiedDate] = date.toString() }
    }

    companion object {
        private val GoalNotifiedDate = stringPreferencesKey("goal_notified_date")

        fun create(context: Context) = NotificationStateStore(context.notificationStateDataStore)
    }
}
