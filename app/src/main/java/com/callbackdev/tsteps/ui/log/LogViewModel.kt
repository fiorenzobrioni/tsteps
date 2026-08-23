@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.callbackdev.tsteps.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.callbackdev.tsteps.data.LogEditorFile
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.data.SettingsStore
import com.callbackdev.tsteps.data.StepRepository
import com.callbackdev.tsteps.data.UnitsSystem
import com.callbackdev.tsteps.data.WorkspaceStore
import com.callbackdev.tsteps.data.distanceMeters
import com.callbackdev.tsteps.data.local.DaySummaryEntity
import com.callbackdev.tsteps.data.toItem
import com.callbackdev.tsteps.domain.Estimates
import com.callbackdev.tsteps.domain.Records
import com.callbackdev.tsteps.domain.SessionItem
import com.callbackdev.tsteps.domain.WeekComparison
import com.callbackdev.tsteps.domain.WeekDay
import com.callbackdev.tsteps.domain.WeekDiff
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
import kotlinx.coroutines.launch

data class LogUiState(
    val today: UncommittedToday? = null,
    val days: List<CommitDay> = emptyList(),
    val expanded: Set<LocalDate> = emptySet(),
    val bestDay: LocalDate? = null,
    val units: UnitsSystem = UnitsSystem.METRIC,
    val todaySessions: List<SessionItem> = emptyList(),
    val sessionsByDate: Map<LocalDate, List<SessionItem>> = emptyMap(),
    val zone: ZoneId = ZoneId.systemDefault(),
    /** This ISO week against the one before — the `week.diff` tab (Fase 15). */
    val weekDiff: WeekComparison? = null,
    /** A pending stats.md jump: the screen scrolls this commit into view. */
    val focusDate: LocalDate? = null
)

class LogViewModel(
    private val repository: StepRepository,
    settingsStore: SettingsStore,
    private val workspaceStore: WorkspaceStore? = null,
    private val clock: Clock = Clock.systemDefaultZone()
) : ViewModel() {

    /**
     * Which of the Log's two files is open, persisted like the main screen's tab
     * (Fase 10's rule: an editor reopens on the file you left it on). Eagerly, so
     * a stored `week.diff` selection lands before the first frame.
     */
    val activeFile: StateFlow<LogEditorFile> =
        (workspaceStore?.logActiveFile ?: flow { emit(LogEditorFile.HISTORY) })
            .stateIn(viewModelScope, SharingStarted.Eagerly, LogEditorFile.HISTORY)

    fun selectFile(file: LogEditorFile) {
        viewModelScope.launch { workspaceStore?.setLogActiveFile(file) }
    }

    private val expanded = MutableStateFlow<Set<LocalDate>>(emptySet())

    fun toggle(date: LocalDate) {
        expanded.update { if (date in it) it - date else it + date }
    }

    init {
        // A stats.md tag jump lands here: the requested day arrives expanded.
        viewModelScope.launch {
            LogFocus.request.collect { date ->
                if (date != null) expanded.update { it + date }
            }
        }
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
        repository.observeAllSessions(),
        settingsStore.settings,
        combine(expanded, LogFocus.request) { e, f -> e to f }
    ) { (date, hourlyRows), history, sessionRows, settings, (expandedDates, focus) ->
        val todaySteps = hourlyRows.sumOf { it.steps }
        val days = history.map { it.toCommitDay() }
        val sessions = sessionRows.mapNotNull { it.toItem() }
            .groupBy { Instant.ofEpochMilli(it.startMillis).atZone(clock.zone).toLocalDate() }
        val todayEntry = UncommittedToday(
            date = date,
            steps = todaySteps,
            distanceMeters = settings.distanceMeters(todaySteps),
            activeMinutes = Estimates.activeMinutes(hourlyRows.map { it.steps })
        )
        LogUiState(
            today = todayEntry,
            days = days,
            expanded = expandedDates,
            bestDay = Records.bestDay(days.map { it.date to it.steps }),
            units = settings.units,
            todaySessions = sessions[date].orEmpty(),
            sessionsByDate = sessions,
            weekDiff = WeekDiff.of(weekDays(days, todayEntry, sessions), date),
            zone = clock.zone,
            focusDate = focus
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogUiState())

    /**
     * The days the week diff sums. Today rides in from the working tree like it
     * does in the heatmap and the totals — it is real movement, just not history
     * yet — but with a null check: the goal check runs at midnight, and claiming
     * a result before it has run would be the one lie this file could tell.
     */
    private fun weekDays(
        days: List<CommitDay>,
        today: UncommittedToday,
        sessions: Map<LocalDate, List<SessionItem>>
    ): List<WeekDay> = days.filter { it.date != today.date }.map { day ->
        WeekDay(
            date = day.date,
            steps = day.steps,
            distanceMeters = day.distanceMeters,
            activeMinutes = day.activeMinutes,
            walks = sessions[day.date]?.size ?: 0,
            goalMet = day.goalMet
        )
    } + WeekDay(
        date = today.date,
        steps = today.steps,
        distanceMeters = today.distanceMeters,
        activeMinutes = today.activeMinutes,
        walks = sessions[today.date]?.size ?: 0,
        goalMet = null
    )

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
                    settingsStore = ServiceLocator.settingsStore(app),
                    workspaceStore = ServiceLocator.workspaceStore(app)
                )
            }
        }
    }
}
