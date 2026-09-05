@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.callbackdev.tsteps.ui.steps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.callbackdev.tsteps.data.AppSettings
import com.callbackdev.tsteps.data.FirstRunStore
import com.callbackdev.tsteps.data.SUGGESTED_DAILY_GOAL_STEPS
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.data.SessionMetric
import com.callbackdev.tsteps.data.SettingsStore
import com.callbackdev.tsteps.data.StepRepository
import com.callbackdev.tsteps.data.StepSource
import com.callbackdev.tsteps.data.TrackingManager
import com.callbackdev.tsteps.data.TrackingState
import com.callbackdev.tsteps.data.MainEditorFile
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.data.WorkspaceStore
import com.callbackdev.tsteps.data.distanceMeters
import com.callbackdev.tsteps.data.local.DaySummaryEntity
import com.callbackdev.tsteps.data.local.HourlyStepsEntity
import com.callbackdev.tsteps.data.toItem
import com.callbackdev.tsteps.domain.DayStats
import com.callbackdev.tsteps.domain.Estimates
import com.callbackdev.tsteps.domain.GoalCheckResult
import com.callbackdev.tsteps.domain.Records
import com.callbackdev.tsteps.domain.SessionItem
import com.callbackdev.tsteps.domain.Streaks
import com.callbackdev.tsteps.healthconnect.ExternalStepsState
import com.callbackdev.tsteps.healthconnect.HcStateStore
import com.callbackdev.tsteps.healthconnect.OriginSteps
import com.callbackdev.tsteps.work.SyncScheduler
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** State of the main screen — the working tree of the day. */
data class StepsUiState(
    val snapshot: TodaySnapshot? = null,
    val status: SensorStatus = SensorStatus.OK,
    val units: UnitsSystem = UnitsSystem.METRIC,
    val sessionMetric: SessionMetric = SessionMetric.SPEED,
    /** Today's completed walks — the `sessions` array of the JSON. */
    val sessions: List<SessionItem> = emptyList(),
    val expandedSessions: Set<Long> = emptySet(),
    /** Committed days, for the README's week table and footer. */
    val history: List<DayStats> = emptyList(),
    /** What other apps counted today (Fase 12) — shown, never added. */
    val externalSteps: List<OriginSteps> = emptyList(),
    /** All-time tags for the README's `## Records`; null until something ranks. */
    val records: DayRecords? = null,
    val zone: ZoneId = ZoneId.systemDefault(),
    /** Date of the newest committed day, for the status bar's `Last commit:`. */
    val lastCommitDate: LocalDate? = null
)

class StepsViewModel(
    private val repository: StepRepository,
    private val settingsStore: SettingsStore,
    private val source: StepSource,
    private val hasPermission: () -> Boolean,
    private val workspaceStore: WorkspaceStore? = null,
    private val firstRunStore: FirstRunStore? = null,
    trackingManager: TrackingManager? = null,
    private val hcStateStore: HcStateStore? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
    /** Fase 12: ships an [rm]/resize to Health Connect right away (inert when off). */
    private val onSessionsMutated: suspend () -> Unit = {}
) : ViewModel() {

    /** The live session, for the FAB's running-state and the status bar chip. */
    val tracking: StateFlow<TrackingState?> =
        trackingManager?.state ?: MutableStateFlow(null)

    /**
     * The main tab bar's active file, persisted as editor workspace state
     * (tweather Fase 10): like a real editor, the app reopens on the file you
     * left it on. Eagerly so a persisted README selection lands before the
     * first frame.
     */
    val activeFile: StateFlow<MainEditorFile> =
        (workspaceStore?.mainActiveFile ?: flow { emit(MainEditorFile.JSON) })
            .stateIn(viewModelScope, SharingStarted.Eagerly, MainEditorFile.JSON)

    fun selectFile(file: MainEditorFile) {
        viewModelScope.launch { workspaceStore?.setMainActiveFile(file) }
    }

    /** Fase 17: the `HELP.md` pointer, until it is used or the file has been opened. */
    val showHelpHint: StateFlow<Boolean> =
        // Same nullable-store pattern as activeFile: no store, no hint
        (workspaceStore?.helpHintDismissed ?: flow { emit(true) })
            .map { !it }
            // Eagerly like activeFile: a hint one frame late reads as a glitch
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun dismissHelpHint() {
        viewModelScope.launch { workspaceStore?.dismissHelpHint() }
    }

    /**
     * Whether the `ACTIVITY_RECOGNITION` dialog has ever been on screen (Fase 22).
     * The screen needs it to tell "the system will ask" from "the system has stopped
     * asking", which is the difference between a tap that opens a dialog and a tap
     * that does nothing at all.
     */
    val permissionAsked: StateFlow<Boolean> =
        (firstRunStore?.sensorPermissionAsked ?: flow { emit(false) })
            // Eagerly, like the other two: the grant line is drawn on the first
            // frame and must already know where its tap goes.
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Recorded when the dialog is launched, not when it answers. */
    fun markPermissionAsked() {
        viewModelScope.launch { firstRunStore?.markSensorPermissionAsked() }
    }

    private val permissionGranted = MutableStateFlow(hasPermission())

    private val expandedSessions = MutableStateFlow<Set<Long>>(emptySet())

    /** Expands/collapses one session's in-file detail object. */
    fun toggleSession(id: Long) {
        expandedSessions.update { if (id in it) it - id else it + id }
    }

    /**
     * The JSON's `"goal": null` line, tapped: the suggested goal becomes the
     * real one. Writing a setting from the working tree is deliberate — this is
     * the one opt-in the file itself offers, and hiding it in settings.config is
     * what kept the check invisible on a fresh install.
     */
    fun acceptSuggestedGoal() {
        viewModelScope.launch {
            settingsStore.setDailyGoalSteps(SUGGESTED_DAILY_GOAL_STEPS)
        }
    }

    /** `[rm]` (confirmed): a tombstone, so the detector never re-creates it. */
    fun removeSession(id: Long) {
        viewModelScope.launch {
            repository.dismissSession(id, clock.millis())
            onSessionsMutated()
        }
    }

    /** Boundary edit (auto sessions): steps and metrics follow the new range. */
    fun resizeSession(id: Long, startMillis: Long, endMillis: Long) {
        viewModelScope.launch {
            repository.resizeSession(id, startMillis, endMillis)
            onSessionsMutated()
        }
    }

    /**
     * Re-checked on every resume (the user may grant or revoke from system
     * settings) and after the in-file grant command returns.
     */
    fun refreshPermission() {
        val granted = hasPermission()
        permissionGranted.value = granted
        // A grant wipes the record of the ask, because the system wipes its own
        // refusal along with it: whatever happens after a revoke starts from zero,
        // dialog included. Idempotent — DataStore skips a write that changes nothing.
        if (granted) viewModelScope.launch { firstRunStore?.clearSensorPermissionAsked() }
    }

    /**
     * Today's date as a flow: re-evaluated every half minute so an app left open
     * across midnight rolls its working tree over without a restart.
     */
    private val today: Flow<LocalDate> = flow {
        while (true) {
            emit(LocalDate.now(clock))
            delay(30_000L)
        }
    }.distinctUntilChanged()

    /**
     * The live listener rides the state's subscription (`channelFlow` +
     * `WhileSubscribed`): screen visible = sensor streaming and each conflated
     * reading ingested; screen gone = listener unregistered within seconds. The
     * spacing keeps ingestion (a DataStore write + Room upserts) at a sane rate
     * while the count on screen still ticks stride by stride.
     */
    val uiState: StateFlow<StepsUiState> = channelFlow {
        launch {
            permissionGranted.collectLatest { granted ->
                if (granted && source.isAvailable) {
                    source.readings().conflate().collect { reading ->
                        repository.ingest(reading)
                        delay(LIVE_INGEST_SPACING_MS)
                    }
                }
            }
        }
        dataFlow().collect { send(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StepsUiState())

    private fun dataFlow(): Flow<StepsUiState> = combine(
        today.flatMapLatest { date ->
            combine(
                repository.observeDay(date),
                repository.observeSessionsOfDay(date)
            ) { rows, sessions -> Triple(date, rows, sessions) }
        },
        repository.observeHistory(),
        // Bundled upstream: combine tops out at five flows. The external block
        // only exists while sync is on, and the longest walk is a single row the
        // README's records need (never the whole session table on this screen).
        combine(
            settingsStore.settings,
            hcStateStore?.external ?: flowOf<ExternalStepsState?>(null),
            repository.observeLongestSession()
        ) { settings, external, longest -> Triple(settings, external, longest) },
        permissionGranted,
        expandedSessions
    ) { (date, hourlyRows, sessionRows), history, upstream, granted, expandedIds ->
        val (settings, hcExternal, longestRow) = upstream
        StepsUiState(
            snapshot = snapshot(date, hourlyRows, history, settings),
            status = when {
                !source.isAvailable -> SensorStatus.NO_SENSOR
                !granted -> SensorStatus.NO_PERMISSION
                else -> SensorStatus.OK
            },
            units = settings.units,
            sessionMetric = settings.sessionMetric,
            sessions = sessionRows.mapNotNull { it.toItem() },
            expandedSessions = expandedIds,
            externalSteps = hcExternal
                ?.takeIf { settings.healthConnect.sync && it.date == date }
                ?.origins.orEmpty(),
            history = history.map {
                DayStats(LocalDate.parse(it.date), it.steps, it.distanceMeters, it.activeMinutes)
            },
            records = records(history, longestRow?.toItem()),
            zone = clock.zone,
            lastCommitDate = history.firstOrNull()?.let { LocalDate.parse(it.date) }
        )
    }

    /**
     * The all-time tags, from the committed days alone — today is the working
     * tree, and a record is a tag on a commit: a personal best set this morning
     * shows up tomorrow, exactly as it does in `stats.md`. Null when nothing has
     * been committed yet: an empty records section is worse than none.
     */
    private fun records(
        history: List<DaySummaryEntity>,
        longestWalk: SessionItem?
    ): DayRecords? {
        val days = history.map { LocalDate.parse(it.date) to it.steps }.filter { it.second > 0 }
        val bestDate = Records.bestDay(days)
        val bestDay = bestDate?.let { date -> date to days.first { it.first == date }.second }
        if (bestDay == null && longestWalk == null) return null
        return DayRecords(
            bestDay = bestDay,
            longestWalk = longestWalk,
            bestWeek = Records.bestWeek(days)
        )
    }

    private fun snapshot(
        date: LocalDate,
        hourlyRows: List<HourlyStepsEntity>,
        history: List<DaySummaryEntity>,
        settings: AppSettings
    ): TodaySnapshot {
        val hourly = LongArray(24)
        hourlyRows.forEach { row -> hourly[row.hour] = row.steps }
        val steps = hourly.sum()
        val activeMinutes = Estimates.activeMinutes(hourly.toList())
        return TodaySnapshot(
            date = date,
            steps = steps,
            goalSteps = settings.dailyGoalSteps,
            distanceMeters = settings.distanceMeters(steps),
            activeMinutes = activeMinutes,
            activeKcal = Estimates.activeKcal(settings.weightKg, activeMinutes),
            hourlySteps = hourly.toList(),
            streakDays = Streaks.current(
                history.map { day ->
                    LocalDate.parse(day.date) to when (day.goalMet) {
                        null -> GoalCheckResult.SKIPPED
                        true -> GoalCheckResult.PASSED
                        false -> GoalCheckResult.FAILED
                    }
                },
                today = date
            )
        )
    }

    companion object {
        private const val LIVE_INGEST_SPACING_MS = 2_000L

        val Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[AndroidViewModelFactory.APPLICATION_KEY])
                StepsViewModel(
                    repository = ServiceLocator.stepRepository(app),
                    settingsStore = ServiceLocator.settingsStore(app),
                    source = ServiceLocator.stepSensorReader(app),
                    hasPermission = { SyncScheduler.hasPermission(app) },
                    workspaceStore = ServiceLocator.workspaceStore(app),
                    firstRunStore = ServiceLocator.firstRunStore(app),
                    trackingManager = ServiceLocator.trackingManager(app),
                    hcStateStore = ServiceLocator.hcStateStore(app),
                    onSessionsMutated = { ServiceLocator.healthConnectSync(app).sync() }
                )
            }
        }
    }
}
