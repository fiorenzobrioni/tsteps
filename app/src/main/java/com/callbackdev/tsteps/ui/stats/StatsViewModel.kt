@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.callbackdev.tsteps.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.data.SettingsStore
import com.callbackdev.tsteps.data.StepRepository
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.data.toItem
import com.callbackdev.tsteps.domain.Averages
import com.callbackdev.tsteps.domain.DayStats
import com.callbackdev.tsteps.domain.GoalCheckResult
import com.callbackdev.tsteps.domain.Heatmap
import com.callbackdev.tsteps.domain.HeatmapGrid
import com.callbackdev.tsteps.domain.Records
import com.callbackdev.tsteps.domain.SessionItem
import com.callbackdev.tsteps.domain.Streaks
import com.callbackdev.tsteps.domain.WindowAverages
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class StatsUiState(
    val grid: HeatmapGrid? = null,
    /** Null when no goal is set — the section vanishes, not the number. */
    val streak: StreakInfo? = null,
    val averages: List<WindowAverages> = emptyList(),
    val bestDay: Pair<LocalDate, Long>? = null,
    val longestWalk: SessionItem? = null,
    val bestWeek: Records.BestWeek? = null,
    val committedDays: Int = 0,
    val units: UnitsSystem = UnitsSystem.METRIC,
    val zone: ZoneId = ZoneId.systemDefault()
)

class StatsViewModel(
    private val repository: StepRepository,
    settingsStore: SettingsStore,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val locale: () -> Locale = { Locale.getDefault() }
) : ViewModel() {

    private val today: Flow<LocalDate> = flow {
        while (true) {
            emit(LocalDate.now(clock))
            delay(30_000L)
        }
    }.distinctUntilChanged()

    val uiState: StateFlow<StatsUiState> = combine(
        today.flatMapLatest { date -> repository.observeDay(date).map { date to it } },
        repository.observeHistory(),
        repository.observeAllSessions(),
        settingsStore.settings
    ) { (date, hourlyRows), history, sessionRows, settings ->
        val days = history.map { LocalDate.parse(it.date) to it.steps }
        val checks = history.map { day ->
            LocalDate.parse(day.date) to when (day.goalMet) {
                null -> GoalCheckResult.SKIPPED
                true -> GoalCheckResult.PASSED
                false -> GoalCheckResult.FAILED
            }
        }
        val dayStats = history.map {
            DayStats(LocalDate.parse(it.date), it.steps, it.distanceMeters, it.activeMinutes)
        }
        val sessions = sessionRows.mapNotNull { it.toItem() }
        // Today rides the grid live as the working tree's cell.
        val stepsByDate = days.toMap() + (date to hourlyRows.sumOf { it.steps })
        StatsUiState(
            grid = Heatmap.build(stepsByDate, date, locale = locale()),
            streak = if (settings.dailyGoalSteps > 0) {
                StreakInfo(
                    current = Streaks.current(checks, date),
                    longest = Streaks.longest(checks)
                )
            } else {
                null
            },
            averages = listOfNotNull(
                Averages.over(dayStats, date, 7),
                Averages.over(dayStats, date, 30)
            ),
            bestDay = Records.bestDay(days)?.let { best ->
                best to (days.toMap().getValue(best))
            },
            longestWalk = Records.longestWalk(sessions),
            bestWeek = Records.bestWeek(days),
            committedDays = history.size,
            units = settings.units,
            zone = clock.zone
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[AndroidViewModelFactory.APPLICATION_KEY])
                StatsViewModel(
                    repository = ServiceLocator.stepRepository(app),
                    settingsStore = ServiceLocator.settingsStore(app)
                )
            }
        }
    }
}
