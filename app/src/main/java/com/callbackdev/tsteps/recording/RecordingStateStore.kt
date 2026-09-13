package com.callbackdev.tsteps.recording

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.recordingDataStore by preferencesDataStore(name = "recording_state")

/**
 * Where the background import stands. Its own DataStore for the same reason the
 * step anchor has one: this is machinery the user never edits, and
 * `$ git restore settings.config` must not be able to lose it.
 */
data class RecordingState(
    /** Play services has been asked to record for us. */
    val subscribed: Boolean = false,
    /**
     * Where the next read starts. Set at subscription time to the *next* whole
     * hour: Play services only records from the moment it is asked, so the hour
     * in progress is half-recorded and stays the sensor's.
     */
    val importFromMillis: Long = 0L,
    /**
     * How far the import has actually written. Deliberately **not** the same
     * field as [importFromMillis]: this one is what tells the sensor path which
     * hours it no longer owns, and between subscribing and the first successful
     * pass the import owns nothing at all — a single field would have the sensor
     * stand down for hours nobody had imported yet.
     */
    val importedUntilMillis: Long = 0L,
    /** Wall clock of the last successful pass; `0` until there is one. */
    val lastImportMillis: Long = 0L
)

/**
 * What the counter path needs to know about the import, in one read (Fase 24c-bis).
 * Two numbers, and the second one is the reason this is not just a `Long`: a
 * recorder that has stopped answering must not keep the counter standing down
 * for hours nobody is going to fill.
 */
data class ImportCoverage(
    /** Hours before this belong to the import. `0` when nothing records for us. */
    val importedUntilMillis: Long = 0L,
    /** Wall clock of the last successful pass; `0` when there has never been one. */
    val lastImportMillis: Long = 0L
) {
    /**
     * The recorder answered recently enough to be trusted with the past. Beyond
     * this the counter takes its history back, because a silent recorder and a
     * quiet day look the same from here and only one of them is safe to assume.
     */
    fun isLiveAt(nowMillis: Long): Boolean =
        lastImportMillis > 0L && nowMillis - lastImportMillis <= TRUST_WINDOW_MILLIS

    companion object {
        /** Six 15-minute passes' worth of slack, Doze included. */
        const val TRUST_WINDOW_MILLIS = 6L * 3_600_000L
    }
}

class RecordingStateStore(private val dataStore: DataStore<Preferences>) {

    /** The two numbers `StepRepository.ingest` asks for on every reading. */
    suspend fun coverage(): ImportCoverage = read().let {
        ImportCoverage(it.importedUntilMillis, it.lastImportMillis)
    }


    val state: Flow<RecordingState> = dataStore.data.map { it.toState() }

    suspend fun read(): RecordingState = dataStore.data.first().toState()

    suspend fun write(state: RecordingState) {
        dataStore.edit {
            it[Subscribed] = state.subscribed
            it[ImportFrom] = state.importFromMillis
            it[ImportedUntil] = state.importedUntilMillis
            it[LastImport] = state.lastImportMillis
        }
    }

    private fun Preferences.toState() = RecordingState(
        subscribed = this[Subscribed] ?: false,
        importFromMillis = this[ImportFrom] ?: 0L,
        importedUntilMillis = this[ImportedUntil] ?: 0L,
        lastImportMillis = this[LastImport] ?: 0L
    )

    companion object {
        private val Subscribed = booleanPreferencesKey("subscribed")
        private val ImportFrom = longPreferencesKey("import_from_millis")
        private val ImportedUntil = longPreferencesKey("imported_until_millis")
        private val LastImport = longPreferencesKey("last_import_millis")

        fun create(context: Context) =
            RecordingStateStore(context.applicationContext.recordingDataStore)
    }
}
