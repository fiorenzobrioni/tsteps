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
import com.callbackdev.tsteps.domain.Estimates
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
 * Health Connect interop (Fase 12). One switch, default OFF: a fresh install
 * never talks to Health Connect — no samples written, no reads, no client
 * created — until the user flips `health_connect.sync` AND grants the HC
 * permissions. What actually happens then is further scoped by which of the
 * three permissions were granted.
 */
data class HealthConnectSettings(
    val sync: Boolean = false
)

/**
 * Everything `settings.config` edits.
 *
 * Defaults are deliberate: `dailyGoalSteps = 0` means **no goal and no CI check**
 * until the user opts in (no guilt mechanics by default, VISION §3.3), and the
 * profile starts empty — kcal stays hidden and stride falls back to a labeled
 * average until the user provides a body to compute with — what the file offers
 * instead of a silent default is [SUGGESTED_DAILY_GOAL_STEPS], one tap away.
 * [themeProfileName] stays a string here so the data layer doesn't depend on the
 * UI's ThemeProfile enum; the UI maps it safely.
 */
data class AppSettings(
    val editor: EditorSettings = EditorSettings(),
    val notifications: NotificationSettings = NotificationSettings(),
    val healthConnect: HealthConnectSettings = HealthConnectSettings(),
    val dailyGoalSteps: Int = 0,
    val weightKg: Double? = null,
    val heightCm: Int? = null,
    /**
     * `profile.stride_cm` — a measured stride that OVERRIDES the height rule of
     * thumb (VISION §5). Null is the normal state: the estimate then falls back
     * to height, and to the labeled 0.72 m average without one.
     */
    val strideCm: Int? = null,
    val units: UnitsSystem = UnitsSystem.METRIC,
    val sessionMetric: SessionMetric = SessionMetric.SPEED,
    /**
     * `sessions.auto_detect` — walks inferred from the sampled counter (Fase
     * 11). Default OFF: a feature that invents entries in your log must be
     * opted into, and off means genuinely off — no samples recorded, no
     * detection ran, zero anything.
     */
    val autoDetectSessions: Boolean = false,
    val themeProfileName: String = "Obsidian",
    val widgetOpacityPct: Int = 100,
    /** Epoch seconds of the last edit; null until the user changes something. */
    val lastModifiedEpochSeconds: Long? = null
)

/** Home-widget background opacity: alpha on the card fill only, border stays crisp. */
val WidgetOpacities = listOf(100, 85, 70, 50)

/** Input ranges enforced by the settings file's terminal inputs. */
object SettingsRanges {
    val GOAL_STEPS = 0..100_000
    val WEIGHT_KG = 20.0..300.0
    val HEIGHT_CM = 100..250
    val STRIDE_CM = 30..120
}

/**
 * The goal `steps_data.json` OFFERS when none is set — never the goal it
 * imposes. VISION §3.3.5 is explicit that a fresh install runs no check, and a
 * goal the user never chose would be exactly the guilt machine the principle
 * guards against; but a setting nobody finds hides the best half of the
 * metaphor, so the file asks for one tap instead of staying silent.
 *
 * 8,000 rather than the folkloric 10,000 (a 1965 Japanese pedometer's brand
 * name): the mortality-benefit plateau in the large step-count cohorts sits
 * around 6,000–8,000 steps/day for older adults and 8,000–10,000 for younger
 * ones, so 8,000 is the one number inside the evidence for the whole adult
 * range — and it is not a round marketing figure, which suits a file that
 * doesn't lie.
 */
const val SUGGESTED_DAILY_GOAL_STEPS = 8_000

/**
 * The stride these settings imply, and the distance they turn steps into.
 * Every screen and worker asks the profile rather than [Estimates] directly,
 * so the override can never be honored in one place and forgotten in another.
 */
fun AppSettings.strideMeters(): Double = Estimates.strideMeters(heightCm, strideCm)

fun AppSettings.distanceMeters(steps: Long): Double = steps * strideMeters()

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
                healthConnect = HealthConnectSettings(
                    sync = prefs[HcSync] ?: false
                ),
                dailyGoalSteps = prefs[DailyGoalSteps] ?: 0,
                weightKg = prefs[WeightKg],
                heightCm = prefs[HeightCm],
                strideCm = prefs[StrideCm],
                units = prefs[Units]
                    ?.let { name -> UnitsSystem.entries.firstOrNull { it.name == name } }
                    ?: UnitsSystem.METRIC,
                sessionMetric = prefs[SessionMetricKey]
                    ?.let { name -> SessionMetric.entries.firstOrNull { it.name == name } }
                    ?: SessionMetric.SPEED,
                autoDetectSessions = prefs[AutoDetectSessions] ?: false,
                themeProfileName = prefs[ThemeProfileName] ?: "Obsidian",
                widgetOpacityPct = (prefs[WidgetOpacity] ?: 100)
                    .takeIf { it in WidgetOpacities } ?: 100,
                lastModifiedEpochSeconds = prefs[LastModified]
            )
        }
        .distinctUntilChanged()

    suspend fun read(): AppSettings = settings.first()

    suspend fun setLineNumbers(enabled: Boolean) = set(LineNumbers, enabled)

    suspend fun setWordWrap(enabled: Boolean) = set(WordWrap, enabled)

    suspend fun setNotifDailyCommit(enabled: Boolean) = set(NotifDailyCommit, enabled)

    suspend fun setNotifGoalCheck(enabled: Boolean) = set(NotifGoalCheck, enabled)

    suspend fun setHealthConnectSync(enabled: Boolean) = set(HcSync, enabled)

    suspend fun setDailyGoalSteps(steps: Int) =
        set(DailyGoalSteps, steps.coerceIn(SettingsRanges.GOAL_STEPS))

    suspend fun setWeightKg(weightKg: Double?) = setOrRemove(WeightKg, weightKg)

    suspend fun setHeightCm(heightCm: Int?) = setOrRemove(HeightCm, heightCm)

    suspend fun setStrideCm(strideCm: Int?) = setOrRemove(StrideCm, strideCm)

    suspend fun setUnits(units: UnitsSystem) = set(Units, units.name)

    suspend fun setSessionMetric(metric: SessionMetric) = set(SessionMetricKey, metric.name)

    suspend fun setAutoDetectSessions(enabled: Boolean) = set(AutoDetectSessions, enabled)

    suspend fun setThemeProfileName(name: String) = set(ThemeProfileName, name)

    suspend fun setWidgetOpacity(pct: Int) = set(WidgetOpacity, pct)

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
        private val HcSync = booleanPreferencesKey("hc_sync")
        private val DailyGoalSteps = intPreferencesKey("daily_goal_steps")
        private val WeightKg = doublePreferencesKey("weight_kg")
        private val HeightCm = intPreferencesKey("height_cm")
        private val StrideCm = intPreferencesKey("stride_cm")
        private val Units = stringPreferencesKey("units")
        private val SessionMetricKey = stringPreferencesKey("session_metric")
        private val AutoDetectSessions = booleanPreferencesKey("auto_detect_sessions")
        private val ThemeProfileName = stringPreferencesKey("theme_profile")
        private val WidgetOpacity = intPreferencesKey("widget_bg_opacity_pct")
        private val LastModified = longPreferencesKey("last_modified_epoch")

        fun create(context: Context) = SettingsStore(context.settingsDataStore)
    }
}
