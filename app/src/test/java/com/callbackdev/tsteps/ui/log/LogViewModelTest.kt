package com.callbackdev.tsteps.ui.log

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.callbackdev.tsteps.data.SettingsStore
import com.callbackdev.tsteps.data.StepRepository
import com.callbackdev.tsteps.data.TrackerStateStore
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
                trackerStateStore = TrackerStateStore(
                    PreferenceDataStoreFactory.create(scope = storeScope) { tmp.newFile("t.preferences_pb") }
                ),
                settingsStore = settingsStore,
                zone = { rome }
            ),
            settingsStore = settingsStore,
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
