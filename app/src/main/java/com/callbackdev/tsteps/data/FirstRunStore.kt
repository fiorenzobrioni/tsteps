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
 * the install. Two booleans, written once each and then never again.
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

    companion object {
        private val Migrated = booleanPreferencesKey("first_run_migrated")
        private val InitDone = booleanPreferencesKey("init_done")

        fun create(context: Context) = FirstRunStore(context.firstRunDataStore)
    }
}
