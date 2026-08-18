package com.callbackdev.tsteps.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** Presentation units; the domain stores metric and converts at render time. */
enum class UnitsSystem { METRIC, IMPERIAL }

/**
 * The `settings.config` model — Fase 2 ships the keys the domain needs (goal,
 * profile, units, theme); Fase 4 grows the file's UI and the remaining sections.
 *
 * Defaults are deliberate: `dailyGoalSteps = 0` means **no goal and no CI check**
 * until the user opts in (no guilt mechanics by default, VISION §3.3), and the
 * profile starts empty — kcal stays hidden and stride falls back to a labeled
 * average until the user provides a body to compute with.
 */
data class AppSettings(
    val dailyGoalSteps: Int = 0,
    val weightKg: Double? = null,
    val heightCm: Int? = null,
    val units: UnitsSystem = UnitsSystem.METRIC,
    val themeProfileName: String = "Obsidian"
)

class SettingsStore(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = dataStore.data
        .map { prefs ->
            AppSettings(
                dailyGoalSteps = prefs[DailyGoalSteps] ?: 0,
                weightKg = prefs[WeightKg],
                heightCm = prefs[HeightCm],
                units = prefs[Units]
                    ?.let { name -> UnitsSystem.entries.firstOrNull { it.name == name } }
                    ?: UnitsSystem.METRIC,
                themeProfileName = prefs[ThemeProfileName] ?: "Obsidian"
            )
        }
        .distinctUntilChanged()

    suspend fun read(): AppSettings = settings.first()

    suspend fun setDailyGoalSteps(steps: Int) {
        dataStore.edit { it[DailyGoalSteps] = steps.coerceAtLeast(0) }
    }

    suspend fun setWeightKg(weightKg: Double?) {
        dataStore.edit { prefs ->
            if (weightKg == null) prefs.remove(WeightKg) else prefs[WeightKg] = weightKg
        }
    }

    suspend fun setHeightCm(heightCm: Int?) {
        dataStore.edit { prefs ->
            if (heightCm == null) prefs.remove(HeightCm) else prefs[HeightCm] = heightCm
        }
    }

    suspend fun setUnits(units: UnitsSystem) {
        dataStore.edit { it[Units] = units.name }
    }

    suspend fun setThemeProfileName(name: String) {
        dataStore.edit { it[ThemeProfileName] = name }
    }

    companion object {
        private val DailyGoalSteps = intPreferencesKey("daily_goal_steps")
        private val WeightKg = doublePreferencesKey("weight_kg")
        private val HeightCm = intPreferencesKey("height_cm")
        private val Units = stringPreferencesKey("units")
        private val ThemeProfileName = stringPreferencesKey("theme_profile")

        fun create(context: Context) = SettingsStore(context.settingsDataStore)
    }
}
