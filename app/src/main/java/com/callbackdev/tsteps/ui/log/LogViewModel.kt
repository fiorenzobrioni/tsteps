@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.callbackdev.tsteps.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.data.SettingsStore
import com.callbackdev.tsteps.data.StepRepository
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.data.local.DaySummaryEntity
import com.callbackdev.tsteps.domain.Estimates
import com.callbackdev.tsteps.domain.Records
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class LogUiState(
    val today: UncommittedToday? = null,
    val days: List<CommitDay> = emptyList(),
    val expanded: Set<LocalDate> = emptySet(),
    val bestDay: LocalDate? = null,
    val units: UnitsSystem = UnitsSystem.METRIC
)

class LogViewModel(
    private val repository: StepRepository,
    settingsStore: SettingsStore,
    private val clock: Clock = Clock.systemDefaultZone()
) : ViewModel() {

    private val expanded = MutableStateFlow<Set<LocalDate>>(emptySet())

    fun toggle(date: LocalDate) {
        expanded.update { if (date in it) it - date else it + date }
    }

    private val today: Flow<LocalDate> = flow {
        while (true) {
            emit(LocalDate.now(clock))
            delay(30_000L)
        }
    }.distinctUntilChanged()

    val uiState: StateFlow<LogUiState> = combine(
        today.flatMapLatest { date -> repository.observeDay(date).map { date to it } },
        repository.observeHistory(),
        settingsStore.settings,
        expanded
    ) { (date, hourlyRows), history, settings, expandedDates ->
        val todaySteps = hourlyRows.sumOf { it.steps }
        val days = history.map { it.toCommitDay() }
        LogUiState(
            today = UncommittedToday(
                date = date,
                steps = todaySteps,
                distanceMeters = Estimates.distanceMeters(todaySteps, settings.heightCm),
                activeMinutes = Estimates.activeMinutes(hourlyRows.map { it.steps })
            ),
            days = days,
            expanded = expandedDates,
            bestDay = Records.bestDay(days.map { it.date to it.steps }),
            units = settings.units
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogUiState())

    private fun DaySummaryEntity.toCommitDay() = CommitDay(
        date = LocalDate.parse(date),
        steps = steps,
        activeMinutes = activeMinutes,
        distanceMeters = distanceMeters,
        activeKcal = activeKcal,
        goalSteps = goalSteps,
        goalMet = goalMet
    )

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[AndroidViewModelFactory.APPLICATION_KEY])
                LogViewModel(
                    repository = ServiceLocator.stepRepository(app),
                    settingsStore = ServiceLocator.settingsStore(app)
                )
            }
        }
    }
}
