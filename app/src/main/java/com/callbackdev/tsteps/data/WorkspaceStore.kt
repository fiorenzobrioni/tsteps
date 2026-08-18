package com.callbackdev.tsteps.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.workspaceDataStore by preferencesDataStore(name = "workspace")

/** The two files open in the main editor's tab bar (Fase 10). */
enum class MainEditorFile { JSON, README }

/**
 * Editor workspace state — what a real editor keeps in its session, not in its
 * settings: the last active file of the main screen survives app restarts like
 * VS Code reopening yesterday's tab. Deliberately its own DataStore instead of a
 * [SettingsStore] key: `$ git restore settings.config` must not close your tab,
 * and workspace state has no line in the settings.config file.
 */
class WorkspaceStore(private val dataStore: DataStore<Preferences>) {

    val mainActiveFile: Flow<MainEditorFile> = dataStore.data
        .map { prefs ->
            prefs[MainActiveFile]
                ?.let { name -> MainEditorFile.entries.firstOrNull { it.name == name } }
                ?: MainEditorFile.JSON
        }
        .distinctUntilChanged()

    suspend fun setMainActiveFile(file: MainEditorFile) {
        dataStore.edit { it[MainActiveFile] = file.name }
    }

    companion object {
        private val MainActiveFile = stringPreferencesKey("main_active_file")

        fun create(context: Context) = WorkspaceStore(context.workspaceDataStore)
    }
}
