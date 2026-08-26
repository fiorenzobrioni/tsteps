package com.callbackdev.tsteps.widget

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import android.content.Intent
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.data.SettingsStore
import com.callbackdev.tsteps.data.StepRepository
import com.callbackdev.tsteps.data.StepSource
import com.callbackdev.tsteps.data.TrackerStateStore
import com.callbackdev.tsteps.data.local.TstepsDatabase
import com.callbackdev.tsteps.domain.StepReading
import com.callbackdev.tsteps.work.StepSyncWorker
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
 * minutes. These tests pin the read to the tap, pin the guards that keep it
 * honest when there is nothing to read, and pin the two things a tap owes the
 * user afterwards: a glyph that settles, and a tail that is actually queued.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetRefreshTest {

    private class FakeStepSource(
        override val isAvailable: Boolean = true,
        private val reading: StepReading? = null,
        private val throws: Boolean = false,
        private val delayMillis: Long = 0
    ) : StepSource {
        var reads = 0
            private set

        override suspend fun readCurrent(timeoutMillis: Long): StepReading? {
            reads++
            if (delayMillis > 0) delay(delayMillis)
            if (throws) error("the sensor fell over")
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
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
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

    // ── The glyph ────────────────────────────────────────────────────────────

    /**
     * `…` is claimed for the whole tap and released by it. Anything else painting
     * meanwhile — the minute tick, a worker landing — must show `…` too, which is
     * why the flag is sticky state on the updater and not a repaint parameter.
     */
    @Test
    fun theGlyphIsHeldForTheWholeTapAndReleasedAfterIt() = runBlocking {
        val source = FakeStepSource(reading = StepReading(1_100, boot, millis("2026-08-18T10:05:00")))
        install(source)
        assertFalse(TstepsWidgetUpdater.isSyncing())

        TstepsWidgetUpdater.ackTap(context)
        assertTrue(TstepsWidgetUpdater.isSyncing())
        TstepsWidgetUpdater.endTap()

        assertFalse(TstepsWidgetUpdater.isSyncing())
    }

    /** A widget left wearing `…` is the one failure a user cannot clear. */
    @Test
    fun aThrowingSampleStillSettlesTheGlyph() = runBlocking {
        install(FakeStepSource(throws = true))

        TstepsWidgetProvider().sampleAndRepaint(context)

        assertFalse(TstepsWidgetUpdater.isSyncing())
    }

    /** Tap spam must not leave the counter above zero once the taps are done. */
    @Test
    fun overlappingTapsSettleTheGlyphExactlyOnce() = runBlocking {
        install(FakeStepSource(reading = null))

        TstepsWidgetUpdater.ackTap(context)
        TstepsWidgetUpdater.ackTap(context)
        TstepsWidgetUpdater.endTap()
        assertTrue(TstepsWidgetUpdater.isSyncing())
        TstepsWidgetUpdater.endTap()
        assertFalse(TstepsWidgetUpdater.isSyncing())

        // Never negative: an unmatched release would make the next tap's `…`
        // invisible.
        TstepsWidgetUpdater.endTap()
        TstepsWidgetUpdater.ackTap(context)
        assertTrue(TstepsWidgetUpdater.isSyncing())
        TstepsWidgetUpdater.endTap()
    }

    // ── The broadcast ────────────────────────────────────────────────────────

    private fun deliver(action: String) = runBlocking {
        TstepsWidgetProvider.workContext = Dispatchers.IO
        TstepsWidgetProvider().onReceive(context, Intent(action))
        TstepsWidgetProvider.inFlight?.join()
    }

    @Test
    fun theRefreshBroadcastSamples() {
        val source = FakeStepSource(reading = StepReading(1_320, boot, millis("2026-08-18T10:05:00")))
        install(source)
        runBlocking { anchorAt(1_000, "2026-08-18T10:00:00") }

        deliver(TstepsWidgetProvider.ACTION_REFRESH)

        assertEquals(1, source.reads)
        assertEquals(320L, runBlocking { repository.stepsOfDay(today) })
        assertFalse(TstepsWidgetUpdater.isSyncing())
    }

    /** A plain repaint is not a sample: only the ↻ glyph spends a sensor read. */
    @Test
    fun anOrdinaryUpdateBroadcastNeverSamples() {
        val source = FakeStepSource(reading = StepReading(1_320, boot, millis("2026-08-18T10:05:00")))
        install(source)

        deliver(AppWidgetUpdateAction)

        assertEquals(0, source.reads)
    }

    /** An action the provider does not serve must not spin up any work at all. */
    @Test
    fun anUnrelatedBroadcastIsIgnored() {
        val source = FakeStepSource(reading = StepReading(1_320, boot, millis("2026-08-18T10:05:00")))
        install(source)
        TstepsWidgetProvider.workContext = Dispatchers.IO

        TstepsWidgetProvider().onReceive(context, Intent(Intent.ACTION_BATTERY_LOW))

        assertEquals(0, source.reads)
    }

    // ── The tail ─────────────────────────────────────────────────────────────

    /**
     * The tap only serves the part the user watches; the day commit, the walk
     * detector and Health Connect ride a queued pass — which must carry the flag
     * that stops it reading a counter the tap read milliseconds ago.
     */
    @Test
    fun theTailSyncIsQueuedAndToldTheCounterWasAlreadyRead() {
        TstepsWidgetProvider.enqueueTailSync(context, alreadySampled = true)

        val work = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(TstepsWidgetProvider.MANUAL_SYNC_NAME)
            .get()
        assertEquals(1, work.size)
        assertTrue(work.single().state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING))
    }

    /**
     * REPLACE, not KEEP: [StepSyncWorker] answers a bad pass with `retry()`, and a
     * retrying unique work counts as pending — under KEEP every later tap was
     * handed to a job sitting out a backoff that tops out at five hours.
     */
    @Test
    fun aSecondTapReplacesThePendingTailInsteadOfBeingSwallowed() {
        val manager = WorkManager.getInstance(context)
        TstepsWidgetProvider.enqueueTailSync(context, alreadySampled = true)
        val first = manager
            .getWorkInfosForUniqueWork(TstepsWidgetProvider.MANUAL_SYNC_NAME).get().single().id

        TstepsWidgetProvider.enqueueTailSync(context, alreadySampled = true)
        val after = manager
            .getWorkInfosForUniqueWork(TstepsWidgetProvider.MANUAL_SYNC_NAME).get()

        assertEquals(1, after.size)
        assertFalse("the newest tap owns the tail", first == after.single().id)
    }

    private companion object {
        /** Enough rounds to drain a broadcast that queues another. */
        const val SETTLE_ROUNDS = 3

        /** Same boot for every reading: reboots are StepTrackerTest's subject. */
        const val boot = 7

        const val AppWidgetUpdateAction = "android.appwidget.action.APPWIDGET_UPDATE"
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
