package com.callbackdev.tsteps.widget

import android.Manifest
import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import com.callbackdev.tsteps.tracking.SampleService
import java.time.Instant
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
import org.junit.Assert.assertFalse
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
 * `# last_sync` and `# stale` are about the health of the sampling, and they used
 * to be about how long the user had been sitting still.
 *
 * `TYPE_STEP_COUNTER` is an on-change sensor: registering re-delivers the event
 * from the last step taken, with its ORIGINAL timestamp. The anchor kept that one
 * number for both jobs, so a phone at rest since breakfast wore a red `# stale`
 * at lunch with nothing whatsoever wrong — and the ↻ tap could not clear it,
 * because the next read handed back the same old event. These tests hold the two
 * instants apart, from the reading all the way to the pixels.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetFreshnessTest {

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

    private class FakeCounter(private val reading: StepReading?) : StepSource {
        override val isAvailable = true
        override suspend fun readCurrent(timeoutMillis: Long): StepReading? = reading
        override fun readings(): Flow<StepReading> = emptyFlow()
    }

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rome = ZoneId.of("Europe/Rome")
    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var database: TstepsDatabase
    private lateinit var repository: StepRepository
    private lateinit var anchorStore: TrackerStateStore

    @Before
    fun setUp() {
        shadowOf(context as Application)
            .grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        database = Room.inMemoryDatabaseBuilder(context, TstepsDatabase::class.java)
            .allowMainThreadQueries().build()
        anchorStore = TrackerStateStore(
            PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("t.preferences_pb") }
        )
        val settings = SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("s.preferences_pb") }
        )
        repository = StepRepository(
            hourlyDao = database.hourlyStepsDao(),
            dayDao = database.daySummaryDao(),
            sessionDao = database.sessionDao(),
            sampleDao = database.stepSampleDao(),
            trackerStateStore = anchorStore,
            settingsStore = settings,
            zone = { rome }
        )
        installSensor(FakeCounter(null))
    }

    /**
     * Present but silent by default. Present, because without a counter the
     * builder short-circuits to "# sensor off" and there is no transcript to
     * inspect. Silent, because placing a widget reconciles the background jobs and
     * a test WorkManager runs the sync worker inline — a fake that answers would
     * re-anchor from itself, right on top of the anchor under test.
     */
    private fun installSensor(source: StepSource) = ServiceLocator.overrideForTests(
        stepRepository = repository,
        stepSensorReader = source,
        settingsStore = ServiceLocator.settingsStore(context),
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

    private fun millis(dateTime: String): Long =
        LocalDateTime.parse(dateTime).atZone(rome).toInstant().toEpochMilli()

    /**
     * One ↻ tap, taken where the platform actually answers one: the service the
     * broadcast starts. Robolectric never lets a `startForegroundService` reach
     * it, so the test drives the service directly.
     */
    private suspend fun runSample() {
        val controller = Robolectric.buildService(
            SampleService::class.java,
            Intent(context, SampleService::class.java)
        ).create()
        controller.startCommand(0, 1)
        settleProvider()
        controller.destroy()
    }

    /**
     * The same clock the renderer stamps its frames with. Under Robolectric this
     * is not always `System.currentTimeMillis()`, and mixing the two would make a
     * reading taken "now" look decades stale.
     */
    private fun nowMillis(): Long = Instant.now().toEpochMilli()

    @Test
    fun `a tap on a still device advances the read instant, not the step instant`() = runBlocking {
        // No steps in two hours: the sensor re-delivers the 08:00 event at 10:00.
        installSensor(
            FakeCounter(
                StepReading(
                    cumulativeSteps = 4_000L,
                    bootCount = 7,
                    timestampMillis = millis("2026-08-18T08:00:00"),
                    readAtMillis = millis("2026-08-18T10:00:00")
                )
            )
        )
        repository.ingest(
            StepReading(3_800L, 7, millis("2026-08-18T07:30:00"), millis("2026-08-18T07:30:00"))
        )

        runSample()

        val anchor = anchorStore.read()!!
        // The steps belong to the hour they were walked...
        assertEquals(millis("2026-08-18T08:00:00"), anchor.lastTimestampMillis)
        // ...and the sampling is as fresh as the tap that asked for it.
        assertEquals(millis("2026-08-18T10:00:00"), anchor.lastReadMillis)
    }

    /**
     * The whole point, end to end: a widget on a home screen, an owner who has not
     * moved for two hours, and no `# stale` anywhere on it.
     */
    @Test
    fun `sitting still for hours never marks the widget stale`() = runBlocking {
        val widgetId = bindWidget()
        repository.ingest(
            StepReading(
                cumulativeSteps = 4_000L,
                bootCount = 7,
                // Walked two hours ago — far past the 45-minute stale threshold.
                timestampMillis = millis("2026-08-18T08:00:00"),
                // Read a moment ago, which is what freshness actually means.
                readAtMillis = nowMillis()
            )
        )

        TstepsWidgetUpdater.updateAllSafely(context)

        val painted = textsOf(widgetId)
        assertTrue("nothing was painted", painted.any { it.isNotBlank() })
        assertFalse(
            "a still device is not a broken one: $painted",
            painted.any { it.contains("# stale") }
        )
    }

    /** And the marker still fires for the thing it is actually for. */
    @Test
    fun `a sampling that really has stalled is still flagged`() = runBlocking {
        val widgetId = bindWidget()
        val threeHoursAgo = nowMillis() - THREE_HOURS_MS
        repository.ingest(
            StepReading(
                cumulativeSteps = 4_000L,
                bootCount = 7,
                timestampMillis = threeHoursAgo,
                // Nothing has read the counter in three hours: jobs are throttled.
                readAtMillis = threeHoursAgo
            )
        )

        TstepsWidgetUpdater.updateAllSafely(context)

        val painted = textsOf(widgetId)
        assertTrue("nothing flagged in $painted", painted.any { it.contains("# stale") })
    }

    /**
     * Puts a widget on the (shadow) home screen and waits for the placement to
     * settle. Robolectric delivers `APPWIDGET_UPDATE` to the real provider, which
     * answers a broadcast by launching background work — join it, or that work
     * repaints on top of the test's own pass from another thread.
     */
    private suspend fun bindWidget(): Int {
        val installed = ServiceLocator.stepSensorReader(context)
        installSensor(NoCounter)
        val id = shadowOf(AppWidgetManager.getInstance(context))
            .createWidget(TstepsWidgetProvider::class.java, R.layout.widget_tsteps_medium)
        settleProvider()
        installSensor(installed)
        return id
    }

    /**
     * Every string the launcher would show. Read from the tree rather than from
     * named line ids: what reaches the host is the sizes map, and which tier it
     * unpacks is the host's business — the marker rides the steps line on the
     * terminal tiers and the value on the small one.
     */
    private fun textsOf(widgetId: Int): List<String> {
        val root = shadowOf(AppWidgetManager.getInstance(context)).getViewFor(widgetId)
        val found = mutableListOf<String>()
        fun walk(view: View) {
            if (view is TextView) found += view.text?.toString().orEmpty()
            if (view is ViewGroup) (0 until view.childCount).forEach { walk(view.getChildAt(it)) }
        }
        walk(root)
        return found
    }

    private companion object {
        /** Enough rounds to drain a broadcast that queues another. */
        const val SETTLE_ROUNDS = 3

        const val THREE_HOURS_MS = 3 * 60 * 60 * 1_000L
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
            SampleService.inFlight?.join()
        }
        shadowOf(Looper.getMainLooper()).idle()
    }
}
