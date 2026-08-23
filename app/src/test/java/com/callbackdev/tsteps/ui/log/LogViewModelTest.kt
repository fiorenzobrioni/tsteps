package com.callbackdev.tsteps.ui.log

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.callbackdev.tsteps.data.LogEditorFile
import com.callbackdev.tsteps.data.SettingsStore
import com.callbackdev.tsteps.data.StepRepository
import com.callbackdev.tsteps.data.TrackerStateStore
import com.callbackdev.tsteps.data.WorkspaceStore
import com.callbackdev.tsteps.data.local.DaySummaryEntity
import com.callbackdev.tsteps.data.local.HourlyStepsEntity
import com.callbackdev.tsteps.data.local.TstepsDatabase
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LogViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val storeScope = CoroutineScope(Dispatchers.IO)
    private val rome = ZoneId.of("Europe/Rome")
    private val clock = Clock.fixed(
        LocalDateTime.parse("2026-08-18T10:00:00").atZone(rome).toInstant(), rome
    )

    private lateinit var database: TstepsDatabase
    private lateinit var viewModel: LogViewModel

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(mainDispatcher)
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TstepsDatabase::class.java
        ).allowMainThreadQueries().build()
        val settingsStore = SettingsStore(
            PreferenceDataStoreFactory.create(scope = storeScope) { tmp.newFile("s.preferences_pb") }
        )
        viewModel = LogViewModel(
            repository = StepRepository(
                hourlyDao = database.hourlyStepsDao(),
                dayDao = database.daySummaryDao(),
                sessionDao = database.sessionDao(),
                sampleDao = database.stepSampleDao(),
                trackerStateStore = TrackerStateStore(
                    PreferenceDataStoreFactory.create(scope = storeScope) { tmp.newFile("t.preferences_pb") }
                ),
                settingsStore = settingsStore,
                zone = { rome }
            ),
            settingsStore = settingsStore,
            workspaceStore = WorkspaceStore(
                PreferenceDataStoreFactory.create(scope = storeScope) { tmp.newFile("w.preferences_pb") }
            ),
            clock = clock
        )
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
        database.close()
        storeScope.cancel()
    }

    private suspend fun waitFor(predicate: () -> Boolean) {
        withTimeout(5_000) {
            while (!predicate()) delay(25)
        }
    }

    @Test
    fun `state carries uncommitted today, commits and the best-day record`() = runBlocking {
        database.daySummaryDao().insertIfAbsent(day("2026-08-16", steps = 14_823))
        database.daySummaryDao().insertIfAbsent(day("2026-08-17", steps = 11_204))
        database.hourlyStepsDao().upsert(HourlyStepsEntity("2026-08-18", 9, 2_500))

        val subscription = launch(mainDispatcher) { viewModel.uiState.collect {} }
        try {
            waitFor { viewModel.uiState.value.days.size == 2 }
            val state = viewModel.uiState.value
            assertEquals(LocalDate.parse("2026-08-17"), state.days.first().date) // newest first
            assertEquals(LocalDate.parse("2026-08-16"), state.bestDay)
            assertEquals(2_500L, state.today?.steps)
            assertEquals(LocalDate.parse("2026-08-18"), state.today?.date)
        } finally {
            subscription.cancel()
        }
    }

    @Test
    fun `a stats tag jump arrives expanded with the focus date set`() = runBlocking {
        database.daySummaryDao().insertIfAbsent(day("2026-08-16"))
        val subscription = launch(mainDispatcher) { viewModel.uiState.collect {} }
        try {
            waitFor { viewModel.uiState.value.days.size == 1 }
            val date = LocalDate.parse("2026-08-16")
            LogFocus.request(date)
            waitFor {
                date in viewModel.uiState.value.expanded &&
                    viewModel.uiState.value.focusDate == date
            }
            LogFocus.consume()
            waitFor { viewModel.uiState.value.focusDate == null }
        } finally {
            subscription.cancel()
        }
    }

    @Test
    fun `toggle expands and collapses a day`() = runBlocking {
        database.daySummaryDao().insertIfAbsent(day("2026-08-17"))
        val subscription = launch(mainDispatcher) { viewModel.uiState.collect {} }
        try {
            waitFor { viewModel.uiState.value.days.size == 1 }
            val date = LocalDate.parse("2026-08-17")
            viewModel.toggle(date)
            waitFor { date in viewModel.uiState.value.expanded }
            viewModel.toggle(date)
            waitFor { date !in viewModel.uiState.value.expanded }
        } finally {
            subscription.cancel()
        }
    }

    @Test
    fun `the week diff pairs the two ISO weeks and lets today in with no check`() = runBlocking {
        // Week 33 (mon 10 .. sun 16) complete; week 34 has monday committed and
        // today (tue 18) still open in the working tree.
        (10..16).forEach { d -> database.daySummaryDao().insertIfAbsent(day("2026-08-%02d".format(d))) }
        database.daySummaryDao().insertIfAbsent(day("2026-08-17"))
        database.hourlyStepsDao().upsert(HourlyStepsEntity("2026-08-18", 9, 2_500))

        val subscription = launch(mainDispatcher) { viewModel.uiState.collect {} }
        try {
            waitFor { viewModel.uiState.value.days.size == 8 }
            val diff = viewModel.uiState.value.weekDiff!!
            assertEquals(33, diff.previous.week)
            assertEquals(7, diff.previous.daysWithData)
            assertEquals(63_000L, diff.previous.steps)
            assertEquals(34, diff.current.week)
            // Monday's commit plus today's working tree.
            assertEquals(2, diff.current.daysWithData)
            assertEquals(11_500L, diff.current.steps)
            // Today's check has not run: skipped, so only monday's counts.
            assertEquals(1, diff.current.checksRun)
            assertEquals(1, diff.current.checksPassed)
        } finally {
            subscription.cancel()
        }
    }

    @Test
    fun `the active log file defaults to the history and follows a selection`() = runBlocking {
        assertEquals(LogEditorFile.HISTORY, viewModel.activeFile.value)
        viewModel.selectFile(LogEditorFile.WEEK)
        waitFor { viewModel.activeFile.value == LogEditorFile.WEEK }
    }

    private fun day(date: String, steps: Long = 9_000) = DaySummaryEntity(
        date = date,
        steps = steps,
        activeMinutes = 80,
        distanceMeters = 6_000.0,
        activeKcal = null,
        goalSteps = 8_000,
        goalMet = steps >= 8_000
    )
}
