package com.callbackdev.tsteps.widget

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
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
import com.callbackdev.tsteps.tracking.SampleService
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The ↻ tap, from the finger to the working tree.
 *
 * It has been in three places. It was a queued expedited job, and an expedited
 * request degrades to an ordinary one once the quota is spent — minutes, on a
 * phone whose owner never opens the app. Then it was read inline in the
 * broadcast, which was prompt and returned nothing: `TYPE_STEP_COUNTER` reports
 * on change, and Android delivers no on-change event to an app that is not in the
 * foreground. Now the broadcast only *routes* — it starts [SampleService], which
 * is in the foreground and therefore gets an answer.
 *
 * So these tests pin two things that used to be one: that the broadcast hands the
 * tap on (and to nothing else), and that the service does the work — the read,
 * the guards that keep it honest when there is nothing to read, and the two
 * things a tap owes the user afterwards: a glyph that settles, and a tail that is
 * actually queued.
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
            // Like the real reader: no hardware, no reading, whoever asks.
            return reading.takeIf { isAvailable }
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
        // provider and the real service, both of which answer with background
        // work. Work still in flight when the stores are swapped back is how one
        // class's leftovers fail the next class's tests.
        runBlocking { settle() }
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

    /**
     * One start of the real service, run to completion. Robolectric never lets the
     * launcher's `startForegroundService` actually reach it, so the two halves of
     * a tap are driven separately: [deliver] proves the broadcast asks, this
     * proves the service delivers.
     */
    private fun runSample(startId: Int = 1): SampleService = runBlocking {
        val controller = Robolectric.buildService(
            SampleService::class.java,
            Intent(context, SampleService::class.java)
        ).create()
        controller.startCommand(0, startId)
        settle()
        val service = controller.get()
        controller.destroy()
        service
    }

    // ── The service: the sample itself ───────────────────────────────────────

    @Test
    fun theServiceReadsTheCounterItself() {
        val source = FakeStepSource(reading = StepReading(1_320, boot, millis("2026-08-18T10:05:00")))
        install(source)
        runBlocking { anchorAt(1_000, "2026-08-18T10:00:00") }

        runSample()

        assertEquals(1, source.reads)
        assertEquals(320L, runBlocking { repository.stepsOfDay(today) })
    }

    @Test
    fun aSilentCounterLeavesTheWorkingTreeAlone() {
        val source = FakeStepSource(reading = null)
        install(source)
        runBlocking { anchorAt(1_000, "2026-08-18T10:00:00") }

        runSample()

        assertTrue("the counter was never asked", source.reads >= 1)
        assertEquals(0L, runBlocking { repository.stepsOfDay(today) })
    }

    /**
     * A PendingIntent outlives the grant that justified it, and a `health`
     * foreground service without `ACTIVITY_RECOGNITION` is a SecurityException:
     * the service must stop before it ever goes foreground.
     */
    @Test
    fun withoutThePermissionTheServiceNeverTouchesTheSensor() {
        shadowOf(context as Application)
            .denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        val source = FakeStepSource(reading = StepReading(1_320, boot, millis("2026-08-18T10:05:00")))
        install(source)
        runBlocking { anchorAt(1_000, "2026-08-18T10:00:00") }

        runSample()

        assertEquals(0, source.reads)
        assertEquals(0L, runBlocking { repository.stepsOfDay(today) })
    }

    /**
     * The service asks `isAvailable` before it spends anything. The queued tail
     * behind it asks the counter regardless — harmless, because a reader without
     * hardware answers null — so what is pinned here is the outcome: a device with
     * no counter grows no rows.
     */
    @Test
    fun aMissingSensorIsNotReadEither() {
        val source = FakeStepSource(
            isAvailable = false,
            reading = StepReading(1_320, boot, millis("2026-08-18T10:05:00"))
        )
        install(source)
        runBlocking { anchorAt(1_000, "2026-08-18T10:00:00") }

        runSample()

        assertEquals(0L, runBlocking { repository.stepsOfDay(today) })
        assertFalse(TstepsWidgetUpdater.isSyncing())
    }

    /**
     * The reason this service exists at all. `TYPE_STEP_COUNTER` reports on
     * change, and Android delivers no on-change event to an app that is not in the
     * foreground — which is why the same read, taken in the broadcast, came back
     * silent. Going foreground is not an implementation detail here, it IS the
     * fix, so it is pinned.
     */
    @Test
    fun theSampleIsTakenInTheForeground() {
        install(FakeStepSource(reading = StepReading(1_320, boot, millis("2026-08-18T10:05:00"))))

        val service = runSample()

        // Asserted through the stop, not the notification: the service removes
        // its own notification on the way out, and the shadow forgets it with it.
        assertTrue("the sample never went foreground", shadowOf(service).isForegroundStopped)
    }

    /** And a start it cannot serve never goes foreground in the first place. */
    @Test
    fun aRefusedSampleNeverGoesForeground() {
        shadowOf(context as Application)
            .denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        install(FakeStepSource(reading = null))

        val service = runSample()

        assertFalse(shadowOf(service).isForegroundStopped)
    }

    // ── The glyph ────────────────────────────────────────────────────────────

    /**
     * `…` is claimed for the whole tap and released by it. Anything else painting
     * meanwhile — the minute tick, a worker landing — must show `…` too, which is
     * why the flag is sticky state on the updater and not a repaint parameter.
     */
    @Test
    fun theGlyphIsHeldForTheWholeTapAndReleasedAfterIt() {
        install(FakeStepSource(reading = StepReading(1_100, boot, millis("2026-08-18T10:05:00"))))
        assertFalse(TstepsWidgetUpdater.isSyncing())

        TstepsWidgetUpdater.ackTap(context)
        assertTrue(TstepsWidgetUpdater.isSyncing())
        TstepsWidgetUpdater.endTap()

        assertFalse(TstepsWidgetUpdater.isSyncing())
    }

    /** A widget left wearing `…` is the one failure a user cannot clear. */
    @Test
    fun aThrowingSampleStillSettlesTheGlyph() {
        install(FakeStepSource(throws = true))

        runSample()

        assertFalse(TstepsWidgetUpdater.isSyncing())
    }

    /** And so is one the permission stopped before it started. */
    @Test
    fun aRefusedSampleLeavesNoGlyphBehind() {
        shadowOf(context as Application)
            .denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        install(FakeStepSource(reading = null))

        runSample()

        assertFalse(TstepsWidgetUpdater.isSyncing())
    }

    /** Tap spam must not leave the counter above zero once the taps are done. */
    @Test
    fun overlappingTapsSettleTheGlyphExactlyOnce() {
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

    // ── The broadcast: routing only ──────────────────────────────────────────

    private fun deliver(action: String) = runBlocking {
        TstepsWidgetProvider.workContext = Dispatchers.IO
        TstepsWidgetProvider().onReceive(context, Intent(action))
        settle()
    }

    /**
     * The whole job of the tap's broadcast: start the service, synchronously, so
     * the start stays inside the widget-interaction window that permits it.
     */
    @Test
    fun theRefreshBroadcastStartsTheSampleService() {
        install(FakeStepSource(reading = StepReading(1_320, boot, millis("2026-08-18T10:05:00"))))

        deliver(TstepsWidgetProvider.ACTION_REFRESH)

        assertEquals(SampleServiceName, startedServiceName())
    }

    /** A plain repaint is not a sample: only the ↻ glyph spends a sensor read. */
    @Test
    fun anOrdinaryUpdateBroadcastNeverSamples() {
        val source = FakeStepSource(reading = StepReading(1_320, boot, millis("2026-08-18T10:05:00")))
        install(source)

        deliver(AppWidgetUpdateAction)

        assertNull(startedServiceName())
        assertEquals(0, source.reads)
    }

    /** An action the provider does not serve must not spin up any work at all. */
    @Test
    fun anUnrelatedBroadcastIsIgnored() {
        val source = FakeStepSource(reading = StepReading(1_320, boot, millis("2026-08-18T10:05:00")))
        install(source)
        TstepsWidgetProvider.workContext = Dispatchers.IO

        TstepsWidgetProvider().onReceive(context, Intent(Intent.ACTION_BATTERY_LOW))

        assertNull(startedServiceName())
        assertEquals(0, source.reads)
    }

    /**
     * Nothing to ask for: no service, and no `…` left on the widget either —
     * but the tail still runs, because a revoked permission is exactly what the
     * repaint behind it has to reach the glass with.
     */
    @Test
    fun withoutThePermissionTheTapStartsNothingAndStillRepaints() {
        shadowOf(context as Application)
            .denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        install(FakeStepSource(reading = StepReading(1_320, boot, millis("2026-08-18T10:05:00"))))

        deliver(TstepsWidgetProvider.ACTION_REFRESH)

        assertNull(startedServiceName())
        assertFalse(TstepsWidgetUpdater.isSyncing())
        assertNotNull(tailWork())
    }

    // ── The tail ─────────────────────────────────────────────────────────────

    /**
     * The tap only serves the part the user watches; the day commit, the walk
     * detector and Health Connect ride a queued pass — which must carry the flag
     * that stops it reading a counter the service read milliseconds ago.
     */
    @Test
    fun aGoodSampleQueuesATailThatDoesNotReadAgain() {
        val source = FakeStepSource(reading = StepReading(1_320, boot, millis("2026-08-18T10:05:00")))
        install(source)
        runBlocking { anchorAt(1_000, "2026-08-18T10:00:00") }

        runSample()

        assertNotNull(tailWork())
        // One read, by the service. The queue was told not to spend a second one.
        assertEquals(1, source.reads)
    }

    /**
     * And a sample that came back empty hands the tail its second chance instead
     * of telling it the counter is already read.
     */
    @Test
    fun aSilentSampleQueuesATailThatWillReadForItself() {
        val source = FakeStepSource(reading = null)
        install(source)

        runSample()

        val work = tailWork()
        assertNotNull(work)
        // Under a test WorkManager the queued pass runs, and it reads: that second
        // read is the whole point of clearing the flag.
        assertEquals(2, source.reads)
    }

    /**
     * REPLACE, not KEEP: [StepSyncWorker] answers a bad pass with `retry()`, and a
     * retrying unique work counts as pending — under KEEP every later tap was
     * handed to a job sitting out a backoff that tops out at five hours.
     */
    @Test
    fun aSecondTapReplacesThePendingTailInsteadOfBeingSwallowed() {
        val manager = WorkManager.getInstance(context)
        SampleService.enqueueTailSync(context, alreadySampled = true)
        val first = manager
            .getWorkInfosForUniqueWork(SampleService.MANUAL_SYNC_NAME).get().single().id

        SampleService.enqueueTailSync(context, alreadySampled = true)
        val after = manager
            .getWorkInfosForUniqueWork(SampleService.MANUAL_SYNC_NAME).get()

        assertEquals(1, after.size)
        assertFalse("the newest tap owns the tail", first == after.single().id)
    }

    private fun tailWork(): WorkInfo? = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork(SampleService.MANUAL_SYNC_NAME).get().firstOrNull()

    private fun startedServiceName(): String? =
        shadowOf(context as Application).nextStartedService?.component?.className

    private companion object {
        /** Enough rounds to drain a broadcast or a service that queues another. */
        const val SETTLE_ROUNDS = 3

        /** Same boot for every reading: reboots are StepTrackerTest's subject. */
        const val boot = 7

        const val AppWidgetUpdateAction = "android.appwidget.action.APPWIDGET_UPDATE"

        val SampleServiceName: String = SampleService::class.java.name
    }

    /**
     * Waits out every broadcast and every service start in flight. Looped, not
     * joined once: placing a widget can queue more than one broadcast (`onEnabled`
     * and `onUpdate`), each of which launches its own work, and draining the first
     * lets the next one through. Idle the looper, join what it started, repeat
     * until nothing is left — work that outlives the test is work that fails the
     * next class.
     */
    private suspend fun settle() {
        repeat(SETTLE_ROUNDS) {
            shadowOf(Looper.getMainLooper()).idle()
            TstepsWidgetProvider.inFlight?.join()
            SampleService.inFlight?.join()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
}
