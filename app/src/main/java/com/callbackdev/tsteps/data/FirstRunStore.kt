package com.callbackdev.tsteps.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.firstRunDataStore by preferencesDataStore(name = "first_run")

/** What the shell must know before it can draw anything — see [FirstRunStore.state]. */
enum class FirstRun {
    /** The legacy check has not run yet in this process: draw nothing, not `init`. */
    Unknown,

    /** `$ tsteps init` still owes an answer. */
    Pending,

    /** Answered — with a grant or by skipping — or inherited by an upgrade. */
    Done
}

/**
 * Whether the first run still owes an answer (Fase 17, tweather's Fase 14c ported).
 *
 * Its own DataStore rather than a corner of [SettingsStore] or [WorkspaceStore]: this
 * is neither a line of `settings.config` nor editor session state, it is a fact about
 * the install: the first two are written once each and then never again, the third
 * follows a state the system itself resets.
 */
class FirstRunStore(private val dataStore: DataStore<Preferences>) {

    val state: Flow<FirstRun> = dataStore.data
        .map { prefs ->
            when {
                prefs[Migrated] != true -> FirstRun.Unknown
                prefs[InitDone] == true -> FirstRun.Done
                else -> FirstRun.Pending
            }
        }
        .distinctUntilChanged()

    /**
     * Decides once per install whether it predates `$ tsteps init`, and never runs
     * again. [used] is what tells an upgrade from a fresh install: an app that has
     * the sensor permission, or that has ever anchored a counter reading, has been
     * answering this question by itself for as long as it has been installed, and
     * must not be asked it again by an update — whatever it answered.
     */
    suspend fun migrate(used: Boolean) {
        dataStore.edit { prefs ->
            if (prefs[Migrated] == true) return@edit
            prefs[Migrated] = true
            if (used) prefs[InitDone] = true
        }
    }

    /** The init screen has been answered — skipping it counts as an answer. */
    suspend fun markInitDone() {
        dataStore.edit { it[InitDone] = true }
    }

    /**
     * Whether this install has ever put the `ACTIVITY_RECOGNITION` dialog on screen
     * (Fase 22) — the fact that tells "never asked" from "asked and refused for
     * good", which the system itself does not: `shouldShowRequestPermissionRationale`
     * answers `false` to both. Without it the file's `$ tsteps grant` command has no
     * way to know that tapping it will do literally nothing, which is what a reader
     * who denied the permission twice sees.
     *
     * Its home is here and not [WorkspaceStore] for the same reason as the other two:
     * this is not editor session state, it is something that happened to the install.
     * Deliberately only about the sensor permission: `POST_NOTIFICATIONS` is asked
     * from inside the very screen that draws its line, so `settings.config` already
     * knows within the session what happened, and nothing has to survive the process.
     */
    val sensorPermissionAsked: Flow<Boolean> = dataStore.data
        .map { it[SensorAsked] == true }
        .distinctUntilChanged()

    /** Written the moment the dialog is launched, not when it answers. */
    suspend fun markSensorPermissionAsked() {
        dataStore.edit { it[SensorAsked] = true }
    }

    /**
     * Forgotten again on every grant — the one thing that makes this an honest answer
     * rather than a tally. The system resets its own refusal when a permission is
     * granted and then revoked from the settings app, and an install still holding
     * "already asked" would send that reader to a settings page for a dialog Android
     * is perfectly willing to put up.
     */
    suspend fun clearSensorPermissionAsked() {
        dataStore.edit { prefs ->
            if (prefs[SensorAsked] == true) prefs.remove(SensorAsked)
        }
    }

    companion object {
        private val Migrated = booleanPreferencesKey("first_run_migrated")
        private val InitDone = booleanPreferencesKey("init_done")
        private val SensorAsked = booleanPreferencesKey("sensor_permission_asked")

        fun create(context: Context) = FirstRunStore(context.firstRunDataStore)
    }
}
