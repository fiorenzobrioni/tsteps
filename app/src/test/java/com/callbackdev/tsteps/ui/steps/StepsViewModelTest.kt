package com.callbackdev.tsteps.ui.steps

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.callbackdev.tsteps.data.SettingsStore
import com.callbackdev.tsteps.data.StepRepository
import com.callbackdev.tsteps.data.StepSource
import com.callbackdev.tsteps.data.TrackerStateStore
import com.callbackdev.tsteps.data.local.DaySummaryEntity
import com.callbackdev.tsteps.data.local.TstepsDatabase
import com.callbackdev.tsteps.domain.StepReading
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
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
class StepsViewModelTest {

    private class FakeStepSource(
        override var isAvailable: Boolean = true
    ) : StepSource {
        val stream = MutableSharedFlow<StepReading>(extraBufferCapacity = 16)
        override suspend fun readCurrent(timeoutMillis: Long): StepReading? = null
        override fun readings(): Flow<StepReading> = stream
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rome = ZoneId.of("Europe/Rome")

    // Frozen mid-day so "today" is stable for the whole test.
    private val clock = Clock.fixed(
        LocalDateTime.parse("2026-08-18T10:00:00").atZone(rome).toInstant(), rome
    )

    private lateinit var database: TstepsDatabase
    private lateinit var settingsStore: SettingsStore
    private lateinit var trackerStore: TrackerStateStore
    private lateinit var repository: StepRepository
    private lateinit var source: FakeStepSource

    @Volatile
    private var permissionGranted = true

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
        trackerStore = TrackerStateStore(
            PreferenceDataStoreFactory.create(scope = storeScope) { tmp.newFile("t.preferences_pb") }
        )
        repository = StepRepository(
            hourlyDao = database.hourlyStepsDao(),
            dayDao = database.daySummaryDao(),
            trackerStateStore = trackerStore,
            settingsStore = settingsStore,
            zone = { rome }
        )
        source = FakeStepSource()
        permissionGranted = true
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
        database.close()
        storeScope.cancel()
    }

    private fun viewModel() = StepsViewModel(
        repository = repository,
        settingsStore = settingsStore,
        source = source,
        hasPermission = { permissionGranted },
        clock = clock
    )

    private fun millis(dateTime: String): Long =
        LocalDateTime.parse(dateTime).atZone(rome).toInstant().toEpochMilli()

    /** Pumps the test dispatcher's virtual time until [predicate] holds. */
    private suspend fun waitFor(timeoutMs: Long = 5_000, predicate: () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!predicate()) {
                mainDispatcher.scheduler.advanceTimeBy(2_500)
                mainDispatcher.scheduler.runCurrent()
                delay(25)
            }
        }
    }

    private fun CoroutineScope.subscribe(vm: StepsViewModel): Job =
        launch(mainDispatcher) { vm.uiState.collect {} }

    @Test
    fun `live readings tick into the working tree while subscribed`() = runBlocking {
        val vm = viewModel()
        val subscription = subscribe(vm)
        try {
            waitFor { vm.uiState.value.status == SensorStatus.OK }

            source.stream.emit(StepReading(50_000L, 3, millis("2026-08-18T09:00:00")))
            waitFor { runBlocking { trackerStore.read() } != null } // anchored

            source.stream.emit(StepReading(50_600L, 3, millis("2026-08-18T09:30:00")))
            waitFor { vm.uiState.value.snapshot?.steps == 600L }

            val snapshot = vm.uiState.value.snapshot!!
            assertEquals(LocalDate.parse("2026-08-18"), snapshot.date)
            assertEquals(600L, snapshot.hourlySteps[9])
        } finally {
            subscription.cancel()
        }
    }

    @Test
    fun `permission flow - denied shows the error state, granting recovers`() = runBlocking {
        permissionGranted = false
        val vm = viewModel()
        val subscription = subscribe(vm)
        try {
            waitFor { vm.uiState.value.status == SensorStatus.NO_PERMISSION }
            permissionGranted = true
            vm.refreshPermission()
            waitFor { vm.uiState.value.status == SensorStatus.OK }
        } finally {
            subscription.cancel()
        }
    }

    @Test
    fun `a device without the sensor reports NO_SENSOR over the permission`() = runBlocking {
        permissionGranted = false
        source.isAvailable = false
        val vm = viewModel()
        val subscription = subscribe(vm)
        try {
            waitFor { vm.uiState.value.status == SensorStatus.NO_SENSOR }
        } finally {
            subscription.cancel()
        }
    }

    @Test
    fun `history feeds streak and last commit`() = runBlocking {
        database.daySummaryDao().insertIfAbsent(day("2026-08-16", goalMet = true))
        database.daySummaryDao().insertIfAbsent(day("2026-08-17", goalMet = true))
        val vm = viewModel()
        val subscription = subscribe(vm)
        try {
            waitFor { vm.uiState.value.lastCommitDate != null }
            assertEquals(LocalDate.parse("2026-08-17"), vm.uiState.value.lastCommitDate)
            assertEquals(2, vm.uiState.value.snapshot?.streakDays)
        } finally {
            subscription.cancel()
        }
    }

    @Test
    fun `no history means no last commit`() = runBlocking {
        val vm = viewModel()
        val subscription = subscribe(vm)
        try {
            waitFor { vm.uiState.value.snapshot != null }
            assertNull(vm.uiState.value.lastCommitDate)
            assertEquals(0, vm.uiState.value.snapshot?.streakDays)
        } finally {
            subscription.cancel()
        }
    }

    private fun day(date: String, goalMet: Boolean) = DaySummaryEntity(
        date = date,
        steps = 9_000,
        activeMinutes = 80,
        distanceMeters = 6_000.0,
        activeKcal = null,
        goalSteps = 8_000,
        goalMet = goalMet
    )
}
