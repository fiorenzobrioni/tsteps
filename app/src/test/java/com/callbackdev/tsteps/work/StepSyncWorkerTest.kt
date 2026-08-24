package com.callbackdev.tsteps.work

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.data.SettingsStore
import com.callbackdev.tsteps.data.StepRepository
import com.callbackdev.tsteps.data.StepSource
import com.callbackdev.tsteps.data.TrackerStateStore
import com.callbackdev.tsteps.data.local.TstepsDatabase
import com.callbackdev.tsteps.domain.StepReading
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

/**
 * What the sampler owes the rest of the app when its pass goes wrong. A periodic
 * worker that returns `failure()` is terminal in WorkManager: one throw from the
 * tail — Health Connect's IPC is a real candidate — used to end the 15-minute
 * sampler until the next `reconcile` re-armed it, and to skip the widget repaint
 * on the way out.
 */
@RunWith(RobolectricTestRunner::class)
class StepSyncWorkerTest {

    private class SilentSource : StepSource {
        override val isAvailable = true
        override suspend fun readCurrent(timeoutMillis: Long): StepReading? = null
        override fun readings(): Flow<StepReading> = emptyFlow()
    }

    private class ThrowingSource : StepSource {
        override val isAvailable = true
        override suspend fun readCurrent(timeoutMillis: Long): StepReading =
            throw IllegalStateException("the sensor service went away")

        override fun readings(): Flow<StepReading> = emptyFlow()
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var database: TstepsDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, TstepsDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        ServiceLocator.overrideForTests()
        database.close()
        scope.cancel()
    }

    private fun install(source: StepSource) = ServiceLocator.overrideForTests(
        stepRepository = StepRepository(
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
            zone = { ZoneId.of("Europe/Rome") }
        ),
        stepSensorReader = source
    )

    private fun pass(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<StepSyncWorker>(context).build().doWork()
    }

    @Test
    fun aSilentCounterIsStillASuccessfulPass() {
        install(SilentSource())
        assertEquals(ListenableWorker.Result.success(), pass())
    }

    @Test
    fun aPassThatThrowsRetriesInsteadOfEndingTheSchedule() {
        install(ThrowingSource())
        assertEquals(ListenableWorker.Result.retry(), pass())
    }
}
