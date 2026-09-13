package com.callbackdev.tsteps.widget

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
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
import com.callbackdev.tsteps.recording.RecordedHour
import com.callbackdev.tsteps.recording.RecordingAvailability
import com.callbackdev.tsteps.recording.StepRecordingGateway
import com.callbackdev.tsteps.work.SyncScheduler
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The widget refreshed when the screen comes on (Fase 25).
 *
 * Four things are worth pinning, and they are the four that decide whether this
 * is a feature or a battery leak: that nothing is registered until there is a
 * widget to refresh and a permission to refresh it with, that a screen-on over
 * the lock screen is not a wake-up (the widget is not on show, so nothing is
 * owed), that a burst of pickups is one pass, and that the debounce still lets
 * through the one wake that can carry a new number — the first one after an hour
 * has ended, which is the unit the recorder writes in.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenWakeRefreshTest {

    /** Present so the transcript renders, silent so no pass ever re-anchors. */
    private object SilentCounter : StepSource {
        override val isAvailable = true
        override suspend fun readCurrent(timeoutMillis: Long): StepReading? = null
        override fun readings(): Flow<StepReading> = emptyFlow()
    }

    /**
     * A counter that is not there. Placing a widget reconciles the background
     * jobs, and under a test WorkManager an armed schedule runs the sync worker
     * inline — a whole pass none of these tests asked for. With no sensor,
     * `reconcile` cancels instead of arming and the placement stays what it
     * should be: placing a widget.
     */
    private object NoCounter : StepSource {
        override val isAvailable = false
        override suspend fun readCurrent(timeoutMillis: Long): StepReading? = null
        override fun readings(): Flow<StepReading> = emptyFlow()
    }

    /**
     * A device with no Play services, which is every device Robolectric runs:
     * the importer then answers `Unsupported` without building a client, and
     * these tests are about the wake, not about the import.
     */
    private object NoRecorder : StepRecordingGateway {
        override fun availability() = RecordingAvailability.UNAVAILABLE
        override suspend fun subscribe(): Boolean = false
        override suspend fun unsubscribe(): Boolean = false
        override suspend fun readHourlySteps(
            fromMillis: Long,
            toMillis: Long
        ): List<RecordedHour> = emptyList()
    }

    /**
     * Counts gathers where they start: every repaint reads the settings exactly
     * once, so one count is one pass — and it counts without opening up any
     * production type, because the stores take their DataStore as an argument.
     */
    private class CountingDataStore(
        private val delegate: DataStore<Preferences>
    ) : DataStore<Preferences> {
        val reads = AtomicInteger(0)

        override val data: Flow<Preferences>
            get() = delegate.data.onStart { reads.incrementAndGet() }

        override suspend fun updateData(
            transform: suspend (Preferences) -> Preferences
        ): Preferences = delegate.updateData(transform)
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rome = ZoneId.of("Europe/Rome")
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
        ScreenWakeRefresh.resetForTests(context)
        database = Room.inMemoryDatabaseBuilder(context, TstepsDatabase::class.java)
            .allowMainThreadQueries().build()
        settingsData = CountingDataStore(
            PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("s.preferences_pb") }
        )
        settingsStore = SettingsStore(settingsData)
        anchorStore = TrackerStateStore(
            PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("t.preferences_pb") }
        )
        repository = StepRepository(
            hourlyDao = database.hourlyStepsDao(),
            dayDao = database.daySummaryDao(),
            sessionDao = database.sessionDao(),
            sampleDao = database.stepSampleDao(),
            trackerStateStore = anchorStore,
            settingsStore = settingsStore,
            zone = { rome }
        )
        installSensor(NoCounter)
    }

    @After
    fun tearDown() {
        // Before the graph is torn out from under it: these tests drive the real
        // provider and the real receiver, both of which answer with background
        // work. Work still in flight when the stores are swapped back is how one
        // class's leftovers fail the next class's tests.
        runBlocking { settle() }
        ScreenWakeRefresh.resetForTests(context)
        ServiceLocator.overrideForTests()
        TstepsWidgetUpdater.resetForTests()
        database.close()
        scope.cancel()
    }

    private fun installSensor(source: StepSource) = ServiceLocator.overrideForTests(
        stepRepository = repository,
        stepSensorReader = source,
        settingsStore = settingsStore,
        trackerStateStore = anchorStore,
        stepRecordingGateway = NoRecorder
    )

    /**
     * Robolectric queues the placement's `APPWIDGET_UPDATE` on a paused looper and
     * delivers it to the REAL provider, which answers a broadcast with background
     * work — and every test here counts the settings reads that work would make.
     * So: place with no counter (reconcile then cancels instead of arming, and no
     * sync worker runs inline), drain, and only then hand the graph a counter.
     *
     * **Armed through [ScreenWakeRefresh] and not through `SyncScheduler`, and
     * that is not a shortcut.** Arming the sampler under the test WorkManager runs
     * a whole sync pass *inline*, and that pass builds the Health Connect sync —
     * a `ServiceLocator` singleton that captures the settings store it is handed
     * and outlives this class, while the store this class builds dies with the
     * scope cancelled in `tearDown`. The bill lands on whichever class runs next
     * (measured: `StepSyncWorkerTest`, cancelled mid-pass), which is Fase 18's
     * lesson about widget tests told a second time. The wiring that does go
     * through the single owner is pinned below, where revoking the permission
     * takes the receiver down.
     */
    private fun placeWidget() {
        shadowOf(AppWidgetManager.getInstance(context))
            .createWidget(TstepsWidgetProvider::class.java, R.layout.widget_tsteps_medium)
        runBlocking { settle() }
        installSensor(SilentCounter)
        ScreenWakeRefresh.reconcile(context)
        runBlocking { settle() }
    }

    /** A broadcast can start work that starts more: drain until it is quiet. */
    private suspend fun settle() {
        repeat(4) {
            shadowOf(Looper.getMainLooper()).idle()
            TstepsWidgetProvider.inFlight?.join()
            ScreenWakeRefresh.inFlight?.join()
        }
    }

    private fun wake(action: String) {
        context.sendBroadcast(Intent(action))
        runBlocking { settle() }
    }

    private fun lockScreen(locked: Boolean) =
        shadowOf(context.getSystemService(KeyguardManager::class.java))
            .setKeyguardLocked(locked)

    private fun millis(dateTime: String): Long =
        LocalDateTime.parse(dateTime).atZone(rome).toInstant().toEpochMilli()

    /** Freezes both clocks so a test decides what "since the last pass" means. */
    private fun clocks(elapsed: Long, wall: String) {
        ScreenWakeRefresh.elapsedRealtime = { elapsed }
        ScreenWakeRefresh.wallClock = { millis(wall) }
    }

    private fun passes(block: () -> Unit): Int {
        settingsData.reads.set(0)
        block()
        return settingsData.reads.get()
    }

    // ── What gets registered, and when ───────────────────────────────────────

    @Test
    fun `an unlock repaints the widget`() {
        placeWidget()

        val passes = passes { wake(Intent.ACTION_USER_PRESENT) }

        assertTrue("the unlock owed the widget a repaint, got $passes", passes >= 1)
    }

    /**
     * The whole battery argument rests on this one: a screen-on must not even
     * reach this process when there is no widget to refresh. Nothing is
     * registered, so nothing is unfrozen, so nothing is spent.
     */
    @Test
    fun `no widget on the home screen, no receiver`() {
        installSensor(SilentCounter)
        ScreenWakeRefresh.reconcile(context)

        val passes = passes { wake(Intent.ACTION_USER_PRESENT) }

        assertEquals("a widget nobody placed is a broadcast nobody needs", 0, passes)
    }

    /**
     * The same gate the sampling jobs live behind: revoked means off, here too —
     * and it says so through `SyncScheduler.reconcile`, the single owner that
     * hears about every change of mind, rather than by calling the receiver's own
     * reconcile. That call is the whole wiring.
     */
    @Test
    fun `a revoked permission takes the receiver down`() {
        placeWidget()
        shadowOf(context as Application)
            .denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        SyncScheduler.reconcile(context)

        val passes = passes { wake(Intent.ACTION_USER_PRESENT) }

        assertEquals("nothing to refresh without the permission", 0, passes)
    }

    // ── Which wake-ups count ─────────────────────────────────────────────────

    /**
     * The screen also comes on for a notification glance, and the home screen is
     * behind the keyguard then: there is no widget on show to refresh, and the
     * unlock — if it comes at all — is the event worth spending on.
     */
    @Test
    fun `a screen-on over the lock screen is not a wake-up, the unlock is`() {
        placeWidget()
        lockScreen(true)

        val glance = passes { wake(Intent.ACTION_SCREEN_ON) }
        val unlock = passes { wake(Intent.ACTION_USER_PRESENT) }

        assertEquals("the widget was behind the keyguard", 0, glance)
        assertTrue("the unlock is the moment it goes on show, got $unlock", unlock >= 1)
    }

    /** With no lock configured there is no unlock to wait for. */
    @Test
    fun `with no keyguard the screen-on is the wake-up`() {
        placeWidget()
        lockScreen(false)

        val passes = passes { wake(Intent.ACTION_SCREEN_ON) }

        assertTrue("nothing stood between the screen and the widget", passes >= 1)
    }

    // ── What it costs ────────────────────────────────────────────────────────

    /**
     * Unlocking a phone is something people do dozens of times a day, and with
     * the app closed almost none of those glances land on a picture that could
     * have changed: the only writer is the hourly import.
     */
    @Test
    fun `a burst of pickups is one pass`() {
        placeWidget()
        clocks(elapsed = 10_000, wall = "2026-09-13T09:10:00")

        val first = passes { wake(Intent.ACTION_USER_PRESENT) }
        ScreenWakeRefresh.elapsedRealtime = { 10_000 + ScreenWakeRefresh.DEBOUNCE_MILLIS - 1 }
        val second = passes { wake(Intent.ACTION_USER_PRESENT) }

        assertTrue("the first pickup must paint, got $first", first >= 1)
        assertEquals("nothing can have changed in between", 0, second)
    }

    /**
     * The one boundary the debounce must not swallow. Play services records by
     * the hour, so the first wake after an hour ends is the only wake that can
     * carry a number nobody has seen yet — even when it lands seconds after the
     * one before it.
     */
    @Test
    fun `the debounce never swallows the wake that crosses an hour`() {
        placeWidget()
        clocks(elapsed = 10_000, wall = "2026-09-13T09:59:40")
        val before = passes { wake(Intent.ACTION_USER_PRESENT) }

        clocks(elapsed = 30_000, wall = "2026-09-13T10:00:20")
        val across = passes { wake(Intent.ACTION_USER_PRESENT) }

        assertTrue("the first pickup must paint, got $before", before >= 1)
        assertTrue("the hour had ended: the recorder had something new", across >= 1)
    }

    /**
     * The debounce is an interval, not a schedule: past it, the next pickup pays
     * for itself again.
     */
    @Test
    fun `past the debounce the next pickup paints`() {
        placeWidget()
        clocks(elapsed = 10_000, wall = "2026-09-13T09:10:00")
        passes { wake(Intent.ACTION_USER_PRESENT) }

        ScreenWakeRefresh.elapsedRealtime = { 10_000 + ScreenWakeRefresh.DEBOUNCE_MILLIS }
        val later = passes { wake(Intent.ACTION_USER_PRESENT) }

        assertTrue("the interval had passed, got $later", later >= 1)
    }
}
