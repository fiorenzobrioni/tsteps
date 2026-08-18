@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.callbackdev.tsteps.ui.steps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.callbackdev.tsteps.data.AppSettings
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.data.SettingsStore
import com.callbackdev.tsteps.data.StepRepository
import com.callbackdev.tsteps.data.StepSource
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.data.local.DaySummaryEntity
import com.callbackdev.tsteps.data.local.HourlyStepsEntity
import com.callbackdev.tsteps.domain.Estimates
import com.callbackdev.tsteps.domain.GoalCheckResult
import com.callbackdev.tsteps.domain.Streaks
import com.callbackdev.tsteps.work.SyncScheduler
import java.time.Clock
import java.time.LocalDate
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** State of the main screen — the working tree of the day. */
data class StepsUiState(
    val snapshot: TodaySnapshot? = null,
    val status: SensorStatus = SensorStatus.OK,
    val units: UnitsSystem = UnitsSystem.METRIC,
    /** Date of the newest committed day, for the status bar's `Last commit:`. */
    val lastCommitDate: LocalDate? = null
)

class StepsViewModel(
    private val repository: StepRepository,
    private val settingsStore: SettingsStore,
    private val source: StepSource,
    private val hasPermission: () -> Boolean,
    private val clock: Clock = Clock.systemDefaultZone()
) : ViewModel() {

    private val permissionGranted = MutableStateFlow(hasPermission())

    /**
     * Re-checked on every resume (the user may grant or revoke from system
     * settings) and after the in-file grant command returns.
     */
    fun refreshPermission() {
        permissionGranted.value = hasPermission()
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
        today.flatMapLatest { date -> repository.observeDay(date).map { date to it } },
        repository.observeHistory(),
        settingsStore.settings,
        permissionGranted
    ) { (date, hourlyRows), history, settings, granted ->
        StepsUiState(
            snapshot = snapshot(date, hourlyRows, history, settings),
            status = when {
                !source.isAvailable -> SensorStatus.NO_SENSOR
                !granted -> SensorStatus.NO_PERMISSION
                else -> SensorStatus.OK
            },
            units = settings.units,
            lastCommitDate = history.firstOrNull()?.let { LocalDate.parse(it.date) }
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
            distanceMeters = Estimates.distanceMeters(steps, settings.heightCm),
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
                    hasPermission = { SyncScheduler.hasPermission(app) }
                )
            }
        }
    }
}
