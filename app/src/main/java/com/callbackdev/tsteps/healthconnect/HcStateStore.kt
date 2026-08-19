package com.callbackdev.tsteps.healthconnect

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.hcStateDataStore by preferencesDataStore(name = "hc_state")

/** What other apps counted today, as last read from Health Connect. */
data class ExternalStepsState(
    val date: LocalDate,
    val origins: List<OriginSteps>,
    val readAtMillis: Long
)

/**
 * Cache of the last Health Connect read, so the UI renders external steps from
 * disk instead of an IPC per frame — the sync worker refreshes it every pass.
 * Machine state, not settings: `git restore settings.config` leaves it alone
 * (though turning `health_connect.sync` off clears it — off means off).
 */
class HcStateStore(private val dataStore: DataStore<Preferences>) {

    /** Null until the first successful read, or after [clear]. */
    val external: Flow<ExternalStepsState?> = dataStore.data
        .map { prefs -> prefs[External]?.let(::decode) }
        .distinctUntilChanged()

    suspend fun write(state: ExternalStepsState) {
        dataStore.edit { it[External] = encode(state) }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(External) }
    }

    // Hand-rolled compact encoding (labels are [a-z0-9_], packages never carry
    // the separators): "date|pkg=label=steps;pkg=label=steps|readAtMillis".
    private fun encode(state: ExternalStepsState): String {
        val origins = state.origins.joinToString(";") {
            "${it.packageName}=${it.label}=${it.steps}"
        }
        return "${state.date}|$origins|${state.readAtMillis}"
    }

    private fun decode(raw: String): ExternalStepsState? {
        val parts = raw.split('|')
        if (parts.size != 3) return null
        val date = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: return null
        val readAt = parts[2].toLongOrNull() ?: return null
        val origins = parts[1].split(';').filter { it.isNotBlank() }.mapNotNull { entry ->
            val fields = entry.split('=')
            if (fields.size != 3) return@mapNotNull null
            val steps = fields[2].toLongOrNull() ?: return@mapNotNull null
            OriginSteps(packageName = fields[0], label = fields[1], steps = steps)
        }
        return ExternalStepsState(date, origins, readAt)
    }

    companion object {
        private val External = stringPreferencesKey("external_steps")

        fun create(context: Context) = HcStateStore(context.hcStateDataStore)
    }
}
