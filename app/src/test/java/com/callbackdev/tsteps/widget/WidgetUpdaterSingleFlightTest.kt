package com.callbackdev.tsteps.widget

import android.Manifest
import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.callbackdev.tsteps.R
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.data.SettingsStore
import com.callbackdev.tsteps.data.StepRepository
import com.callbackdev.tsteps.data.StepSource
import com.callbackdev.tsteps.data.TrackerStateStore
import com.callbackdev.tsteps.data.local.TstepsDatabase
import com.callbackdev.tsteps.domain.StepReading
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A repaint is gather-then-paint, and every caller of it is fire-and-forget: the
 * sync workers, the tracking service's minute tick, the settings collector, the
 * app leaving the foreground, the ↻ tap. Run two concurrently and the launcher
 * keeps whichever *finished* last, which is not the one that *started* last — a
 * slow pass could repaint the number from before a tap right over the number the
 * tap had just delivered. Hence one pass at a time, and at most one more queued.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetUpdaterSingleFlightTest {

    /** Present so the transcript renders, silent so no pass ever re-anchors. */
    private object SilentCounter : StepSource {
        override val isAvailable = true
        override suspend fun readCurrent(timeoutMillis: Long): StepReading? = null
        override fun readings(): Flow<StepReading> = emptyFlow()
    }

    /**
     * A counter that is not there. Placing a widget reconciles the background
     * jobs, and under a test WorkManager an armed schedule runs the sync worker
     * inline — a whole pass (commit, goal watcher, detector, Health Connect,
     * repaint) that none of these tests asked for, on threads the rest of the
     * suite is sharing. With no sensor, `reconcile` cancels instead of arming and
     * the placement stays what it should be: placing a widget.
     */
    private object NoCounter : StepSource {
        override val isAvailable = false
        override suspend fun readCurrent(timeoutMillis: Long): StepReading? = null
        override fun readings(): Flow<StepReading> = emptyFlow()
    }

    /**
     * Counts gathers where they start. Every pass reads the settings exactly once,
     * so one count is one gather — and it counts without opening up any production
     * type, because the stores take their DataStore as a constructor argument.
     */
    private class CountingDataStore(
        private val delegate: DataStore<Preferences>
    ) : DataStore<Preferences> {
        val reads = AtomicInteger(0)
        val failing = AtomicBoolean(false)

        override val data: Flow<Preferences>
            get() = delegate.data.onStart {
                reads.incrementAndGet()
                if (failing.get()) error("the settings store fell over")
            }

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences
        ): Preferences = delegate.updateData(transform)
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var database: TstepsDatabase
    private lateinit var settingsData: CountingDataStore
    private lateinit var settingsStore: SettingsStore
    private lateinit var anchorStore: TrackerStateStore
    private lateinit var repository: StepRepository

    @Before
    fun setUp() {
        shadowOf(context as Application)
            .grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        TstepsWidgetUpdater.resetForTests()
        database = Room.inMemoryDatabaseBuilder(context, TstepsDatabase::class.java)
            .allowMainThreadQueries().build()
        settingsData = CountingDataStore(
            PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("s.preferences_pb") }
        )
        val settings = SettingsStore(settingsData)
        val anchors = TrackerStateStore(
            PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("t.preferences_pb") }
        )
        repository = StepRepository(
            hourlyDao = database.hourlyStepsDao(),
            dayDao = database.daySummaryDao(),
            sessionDao = database.sessionDao(),
            sampleDao = database.stepSampleDao(),
            trackerStateStore = anchors,
            settingsStore = settings
        )
        anchorStore = anchors
        settingsStore = settings
        // Placement last, and fully drained. Robolectric queues APPWIDGET_UPDATE
        // on a paused looper and delivers it to the REAL provider, which answers a
        // broadcast with background work — and every test here counts the settings
        // reads that work would make. So: place it with no counter (reconcile then
        // cancels instead of arming, and no sync worker runs), idle the looper to
        // let the broadcast fire, join what it started, and only then hand the
        // graph the counter the tests actually want.
        runBlocking {
            installSensor(NoCounter)
            shadowOf(AppWidgetManager.getInstance(context))
                .createWidget(TstepsWidgetProvider::class.java, R.layout.widget_tsteps_medium)
            settleProvider()
            installSensor(SilentCounter)
        }
    }

    private fun installSensor(source: StepSource) = ServiceLocator.overrideForTests(
        stepRepository = repository,
        stepSensorReader = source,
        settingsStore = settingsStore,
        trackerStateStore = anchorStore
    )



    @After
    fun tearDown() {
        // Before the graph is torn out from under it: these tests drive the real
        // provider, which answers a broadcast with background work. Work still in
        // flight when the stores are swapped back is how one class's leftovers
        // fail the next class's tests.
        runBlocking { settleProvider() }
        ServiceLocator.overrideForTests()
        TstepsWidgetUpdater.resetForTests()
        database.close()
        scope.cancel()
    }

    /** Coalescing must never mean "painted nothing": a lone caller always paints. */
    @Test
    fun `a single repaint gathers and paints`() = runBlocking {
        settingsData.reads.set(0)

        TstepsWidgetUpdater.updateAllSafely(context)

        assertTrue("a lone caller must paint", settingsData.reads.get() >= 1)
    }

    /**
     * The invariant, stated as the comparison it actually is: eight callers *in
     * sequence* are eight gathers, because each one starts after the last has
     * finished reading. Eight callers *at once* are at most two — the pass that is
     * running and the one queued behind it, which by definition has not read
     * anything yet and therefore already covers every request that arrived while
     * it waited.
     *
     * Measured against each other rather than against a fixed number: this runs
     * the real provider on a Robolectric looper, and widget placement can leave a
     * stray pass in flight. A stray pass shifts both counts by one; it cannot
     * close a gap of eight to two.
     */
    @Test
    fun `concurrent repaints collapse the way sequential ones do not`() = runBlocking {
        settingsData.reads.set(0)
        repeat(CALLERS) { TstepsWidgetUpdater.updateAllSafely(context) }
        val sequential = settingsData.reads.get()

        settingsData.reads.set(0)
        (1..CALLERS).map { async(Dispatchers.IO) { TstepsWidgetUpdater.updateAllSafely(context) } }
            .awaitAll()
        val concurrent = settingsData.reads.get()

        assertTrue(
            "each sequential caller gathers for itself, got $sequential",
            sequential >= CALLERS
        )
        assertTrue(
            "$CALLERS concurrent callers should collapse: $concurrent vs $sequential",
            concurrent < sequential
        )
        assertTrue("a burst must still paint", concurrent >= 1)
    }

    /**
     * The queue slot is claimed before the lock is taken, so it has to be released
     * on every exit. A slot left claimed by a pass that died would silently
     * swallow every repaint after it, and the widget would freeze for good.
     */
    @Test
    fun `a pass that throws does not freeze every repaint after it`() = runBlocking {
        settingsData.failing.set(true)
        TstepsWidgetUpdater.updateAllSafely(context)

        settingsData.failing.set(false)
        settingsData.reads.set(0)
        TstepsWidgetUpdater.updateAllSafely(context)

        assertTrue(
            "the updater stopped painting after one bad pass",
            settingsData.reads.get() >= 1
        )
    }

    private companion object {
        /** Enough rounds to drain a broadcast that queues another. */
        const val SETTLE_ROUNDS = 3

        /** Enough callers that collapsing them is unmistakable. */
        const val CALLERS = 8
    }

    /**
     * Waits out every broadcast the provider is serving. Looped, not joined once:
     * placing a widget can queue more than one broadcast (`onEnabled` and
     * `onUpdate`), each of which launches its own work, and draining the first
     * lets the next one through. Idle the looper, join what it started, repeat
     * until nothing is left — work that outlives the test is work that fails the
     * next class.
     */
    private suspend fun settleProvider() {
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            TstepsWidgetProvider.inFlight?.join()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
}
