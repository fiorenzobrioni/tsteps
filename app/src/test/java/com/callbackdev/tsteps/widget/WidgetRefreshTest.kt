package com.callbackdev.tsteps.widget

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.data.SettingsStore
import com.callbackdev.tsteps.data.StepRepository
import com.callbackdev.tsteps.data.StepSource
import com.callbackdev.tsteps.data.TrackerStateStore
import com.callbackdev.tsteps.data.local.TstepsDatabase
import com.callbackdev.tsteps.domain.StepReading
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The ↻ tap reads the counter inside the broadcast that carries it. It used to
 * enqueue the read instead, and an expedited request degrades to an ordinary job
 * once the quota is spent — on a phone whose owner never opens the app, that is
 * minutes. These tests pin the read to the tap, and pin the guards that keep it
 * honest when there is nothing to read.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetRefreshTest {

    private class FakeStepSource(
        override val isAvailable: Boolean = true,
        private val reading: StepReading? = null
    ) : StepSource {
        var reads = 0
            private set

        override suspend fun readCurrent(timeoutMillis: Long): StepReading? {
            reads++
            return reading
        }

        override fun readings(): Flow<StepReading> = emptyFlow()
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rome = ZoneId.of("Europe/Rome")
    private val today = LocalDate.parse("2026-08-18")
    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var database: TstepsDatabase
    private lateinit var repository: StepRepository

    @Before
    fun setUp() {
        shadowOf(context as Application)
            .grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        database = Room.inMemoryDatabaseBuilder(context, TstepsDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = StepRepository(
            hourlyDao = database.hourlyStepsDao(),
            dayDao = database.daySummaryDao(),
            sessionDao = database.sessionDao(),
            sampleDao = database.stepSampleDao(),
            trackerStateStore = TrackerStateStore(
                PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("t.preferences_pb") }
            ),
            settingsStore = SettingsStore(
                PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("s.preferences_pb") }
            ),
            zone = { rome }
        )
    }

    @After
    fun tearDown() {
        ServiceLocator.overrideForTests()
        database.close()
        scope.cancel()
    }

    private fun millis(dateTime: String): Long =
        LocalDateTime.parse(dateTime).atZone(rome).toInstant().toEpochMilli()

    private fun install(source: FakeStepSource) = ServiceLocator.overrideForTests(
        stepRepository = repository,
        stepSensorReader = source
    )

    /** The anchor the tap's reading is a delta from. */
    private suspend fun anchorAt(cumulative: Long, at: String) =
        repository.ingest(StepReading(cumulative, boot, millis(at)))

    @Test
    fun theTapReadsTheCounterItself() = runBlocking {
        val source = FakeStepSource(reading = StepReading(1_320, boot, millis("2026-08-18T10:05:00")))
        install(source)
        anchorAt(1_000, "2026-08-18T10:00:00")

        TstepsWidgetProvider().sampleAndRepaint(context)

        assertEquals(1, source.reads)
        assertEquals(320L, repository.stepsOfDay(today))
    }

    @Test
    fun aSilentCounterLeavesTheWorkingTreeAlone() = runBlocking {
        val source = FakeStepSource(reading = null)
        install(source)
        anchorAt(1_000, "2026-08-18T10:00:00")

        TstepsWidgetProvider().sampleAndRepaint(context)

        assertEquals(1, source.reads)
        assertEquals(0L, repository.stepsOfDay(today))
    }

    @Test
    fun withoutThePermissionTheTapNeverTouchesTheSensor() = runBlocking {
        shadowOf(context as Application)
            .denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        val source = FakeStepSource(reading = StepReading(1_320, boot, millis("2026-08-18T10:05:00")))
        install(source)
        anchorAt(1_000, "2026-08-18T10:00:00")

        TstepsWidgetProvider().sampleAndRepaint(context)

        assertEquals(0, source.reads)
        assertEquals(0L, repository.stepsOfDay(today))
    }

    @Test
    fun aMissingSensorIsNotReadEither() = runBlocking {
        val source = FakeStepSource(
            isAvailable = false,
            reading = StepReading(1_320, boot, millis("2026-08-18T10:05:00"))
        )
        install(source)

        TstepsWidgetProvider().sampleAndRepaint(context)

        assertEquals(0, source.reads)
    }

    private companion object {
        /** Same boot for every reading: reboots are StepTrackerTest's subject. */
        const val boot = 7
    }
}
