package com.callbackdev.tsteps.ui.stats

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.callbackdev.tsteps.data.SettingsStore
import com.callbackdev.tsteps.data.StepRepository
import com.callbackdev.tsteps.data.TrackerStateStore
import com.callbackdev.tsteps.data.local.DaySummaryEntity
import com.callbackdev.tsteps.data.local.HourlyStepsEntity
import com.callbackdev.tsteps.data.local.SessionEntity
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StatsViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val storeScope = CoroutineScope(Dispatchers.IO)
    private val rome = ZoneId.of("Europe/Rome")
    private val clock = Clock.fixed(
        LocalDateTime.parse("2026-08-18T10:00:00").atZone(rome).toInstant(), rome
    )

    private lateinit var database: TstepsDatabase
    private lateinit var settingsStore: SettingsStore
    private lateinit var viewModel: StatsViewModel

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(mainDispatcher)
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TstepsDatabase::class.java
        ).allowMainThreadQueries().build()
        settingsStore = SettingsStore(
            PreferenceDataStoreFactory.create(scope = storeScope) { tmp.newFile("s.preferences_pb") }
        )
        viewModel = StatsViewModel(
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
        withTimeout(5_000) { while (!predicate()) delay(25) }
    }

    private fun day(date: String, steps: Long, goalMet: Boolean? = true) = DaySummaryEntity(
        date = date,
        steps = steps,
        activeMinutes = 80,
        distanceMeters = steps * 0.7,
        activeKcal = null,
        goalSteps = if (goalMet == null) 0 else 8_000,
        goalMet = goalMet
    )

    @Test
    fun `state carries grid with live today, records and averages`() = runBlocking {
        database.daySummaryDao().insertIfAbsent(day("2026-08-16", 14_823))
        database.daySummaryDao().insertIfAbsent(day("2026-08-17", 11_204))
        database.hourlyStepsDao().upsert(HourlyStepsEntity("2026-08-18", 9, 2_500))
        database.sessionDao().insert(
            SessionEntity(
                startMillis = LocalDateTime.parse("2026-08-17T09:00:00")
                    .atZone(rome).toInstant().toEpochMilli(),
                endMillis = LocalDateTime.parse("2026-08-17T10:32:00")
                    .atZone(rome).toInstant().toEpochMilli(),
                type = "walk",
                steps = 9_120,
                distanceMeters = 6_600.0,
                avgCadenceSpm = 99,
                activeMillis = 92 * 60_000L
            )
        )
        settingsStore.setDailyGoalSteps(8_000)

        val subscription = launch(mainDispatcher) { viewModel.uiState.collect {} }
        try {
            waitFor { viewModel.uiState.value.committedDays == 2 && viewModel.uiState.value.streak != null }
            val state = viewModel.uiState.value
            // Today's live cell rides the grid even before its commit.
            val todayCell = state.grid!!.weeks.last().cells[1] // Tuesday
            assertEquals(2_500L, todayCell.steps)
            assertEquals(LocalDate.parse("2026-08-16"), state.bestDay?.first)
            assertEquals(14_823L, state.bestDay?.second)
            assertEquals(9_120L, state.longestWalk?.steps)
            assertEquals(2, state.streak?.current) // 16th + 17th, today uncommitted
            assertEquals(2, state.averages.size)
            assertEquals(13_014L, state.averages.first().avgSteps) // (14823+11204)/2 rounded
        } finally {
            subscription.cancel()
        }
    }

    @Test
    fun `no goal means no streak section`() = runBlocking {
        database.daySummaryDao().insertIfAbsent(day("2026-08-17", 9_000, goalMet = null))
        val subscription = launch(mainDispatcher) { viewModel.uiState.collect {} }
        try {
            waitFor { viewModel.uiState.value.committedDays == 1 }
            assertNull(viewModel.uiState.value.streak)
        } finally {
            subscription.cancel()
        }
    }
}
