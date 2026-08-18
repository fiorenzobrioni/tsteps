package com.callbackdev.tsteps.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
 * Speed and pace are the same fact in two shapes; sessions render one of them,
 * never both (VISION §5). `settings.config`: `units.session_metric`.
 */
enum class SessionMetric { SPEED, PACE }

/**
 * Editor behavior toggles from `settings.config`. Defaults follow tweather's
 * decisions: line numbers off (reclaims horizontal space on phones), word wrap
 * off (VS Code's default).
 */
data class EditorSettings(
    val lineNumbers: Boolean = false,
    val wordWrap: Boolean = false
)

/**
 * Notification toggles — both default ON but inert until the user grants the
 * permission (the grant is the real opt-in on minSdk 33). Never promotional,
 * never motivational: one notification per event, at most two a day.
 */
data class NotificationSettings(
    /** The closed day's commit message, posted by whoever commits it (silent). */
    val dailyCommit: Boolean = true,
    /** One notification when today's check passes; re-arms at midnight. */
    val goalCheck: Boolean = true
)

/**
 * Everything `settings.config` edits.
 *
 * Defaults are deliberate: `dailyGoalSteps = 0` means **no goal and no CI check**
 * until the user opts in (no guilt mechanics by default, VISION §3.3), and the
 * profile starts empty — kcal stays hidden and stride falls back to a labeled
 * average until the user provides a body to compute with. [themeProfileName]
 * stays a string here so the data layer doesn't depend on the UI's ThemeProfile
 * enum; the UI maps it safely.
 */
data class AppSettings(
    val editor: EditorSettings = EditorSettings(),
    val notifications: NotificationSettings = NotificationSettings(),
    val dailyGoalSteps: Int = 0,
    val weightKg: Double? = null,
    val heightCm: Int? = null,
    val units: UnitsSystem = UnitsSystem.METRIC,
    val sessionMetric: SessionMetric = SessionMetric.SPEED,
    val themeProfileName: String = "Obsidian",
    /** Epoch seconds of the last edit; null until the user changes something. */
    val lastModifiedEpochSeconds: Long? = null
)

/** Input ranges enforced by the settings file's terminal inputs. */
object SettingsRanges {
    val GOAL_STEPS = 0..100_000
    val WEIGHT_KG = 20.0..300.0
    val HEIGHT_CM = 100..250
}

class SettingsStore(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = dataStore.data
        .map { prefs ->
            AppSettings(
                editor = EditorSettings(
                    lineNumbers = prefs[LineNumbers] ?: false,
                    wordWrap = prefs[WordWrap] ?: false
                ),
                notifications = NotificationSettings(
                    dailyCommit = prefs[NotifDailyCommit] ?: true,
                    goalCheck = prefs[NotifGoalCheck] ?: true
                ),
                dailyGoalSteps = prefs[DailyGoalSteps] ?: 0,
                weightKg = prefs[WeightKg],
                heightCm = prefs[HeightCm],
                units = prefs[Units]
                    ?.let { name -> UnitsSystem.entries.firstOrNull { it.name == name } }
                    ?: UnitsSystem.METRIC,
                sessionMetric = prefs[SessionMetricKey]
                    ?.let { name -> SessionMetric.entries.firstOrNull { it.name == name } }
                    ?: SessionMetric.SPEED,
                themeProfileName = prefs[ThemeProfileName] ?: "Obsidian",
                lastModifiedEpochSeconds = prefs[LastModified]
            )
        }
        .distinctUntilChanged()

    suspend fun read(): AppSettings = settings.first()

    suspend fun setLineNumbers(enabled: Boolean) = set(LineNumbers, enabled)

    suspend fun setWordWrap(enabled: Boolean) = set(WordWrap, enabled)

    suspend fun setNotifDailyCommit(enabled: Boolean) = set(NotifDailyCommit, enabled)

    suspend fun setNotifGoalCheck(enabled: Boolean) = set(NotifGoalCheck, enabled)

    suspend fun setDailyGoalSteps(steps: Int) =
        set(DailyGoalSteps, steps.coerceIn(SettingsRanges.GOAL_STEPS))

    suspend fun setWeightKg(weightKg: Double?) = setOrRemove(WeightKg, weightKg)

    suspend fun setHeightCm(heightCm: Int?) = setOrRemove(HeightCm, heightCm)

    suspend fun setUnits(units: UnitsSystem) = set(Units, units.name)

    suspend fun setSessionMetric(metric: SessionMetric) = set(SessionMetricKey, metric.name)

    suspend fun setThemeProfileName(name: String) = set(ThemeProfileName, name)

    /**
     * `$ git restore settings.config` — clears every stored preference (including
     * the last-modified stamp), so the file reads pristine again. Deliberately
     * does NOT touch the tracker anchor store: resetting your config must never
     * lose step continuity.
     */
    suspend fun resetToDefaults() {
        dataStore.edit { it.clear() }
    }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        dataStore.edit {
            it[key] = value
            it[LastModified] = System.currentTimeMillis() / 1000
        }
    }

    private suspend fun <T> setOrRemove(key: Preferences.Key<T>, value: T?) {
        dataStore.edit {
            if (value == null) it.remove(key) else it[key] = value
            it[LastModified] = System.currentTimeMillis() / 1000
        }
    }

    companion object {
        private val LineNumbers = booleanPreferencesKey("editor_line_numbers")
        private val WordWrap = booleanPreferencesKey("editor_word_wrap")
        private val NotifDailyCommit = booleanPreferencesKey("notif_daily_commit")
        private val NotifGoalCheck = booleanPreferencesKey("notif_goal_check")
        private val DailyGoalSteps = intPreferencesKey("daily_goal_steps")
        private val WeightKg = doublePreferencesKey("weight_kg")
        private val HeightCm = intPreferencesKey("height_cm")
        private val Units = stringPreferencesKey("units")
        private val SessionMetricKey = stringPreferencesKey("session_metric")
        private val ThemeProfileName = stringPreferencesKey("theme_profile")
        private val LastModified = longPreferencesKey("last_modified_epoch")

        fun create(context: Context) = SettingsStore(context.settingsDataStore)
    }
}
