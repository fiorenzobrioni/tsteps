package com.callbackdev.tsteps.recording

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.callbackdev.tsteps.data.local.TstepsDatabase
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StepImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rome = ZoneId.of("Europe/Rome")

    private lateinit var database: TstepsDatabase
    private lateinit var store: RecordingStateStore
    private lateinit var gateway: FakeRecordingGateway
    private lateinit var importer: StepImporter

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TstepsDatabase::class.java
        ).allowMainThreadQueries().build()
        store = RecordingStateStore(
            PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("r.preferences_pb") }
        )
        gateway = FakeRecordingGateway()
        importer = StepImporter(gateway, store, database.hourlyStepsDao()) { rome }
    }

    @After
    fun tearDown() {
        database.close()
        scope.cancel()
    }

    private fun millis(dateTime: String): Long =
        LocalDateTime.parse(dateTime).atZone(rome).toInstant().toEpochMilli()

    private fun hour(at: String, steps: Long) =
        RecordedHour(millis(at), millis(at) + 3_600_000L, steps)

    private suspend fun stepsOn(date: String) =
        database.hourlyStepsDao().day(date).sumOf { it.steps }

    @Test
    fun `the first pass subscribes and imports nothing yet`() = runBlocking {
        val outcome = importer.run(millis("2026-09-12T10:20:00"))

        assertEquals(StepImportOutcome.Arming, outcome)
        assertEquals(1, gateway.subscribeCalls)
        val state = store.read()
        assertTrue(state.subscribed)
        // Play services starts recording now, so the hour in progress is half
        // recorded and stays the sensor's: the import begins at 11:00.
        assertEquals(millis("2026-09-12T11:00:00"), state.importFromMillis)
        // Nothing imported yet, so the counter must not stand down anywhere.
        assertEquals(0L, state.importedUntilMillis)
    }

    @Test
    fun `a pass writes the finished hours and leaves the one in progress alone`() = runBlocking {
        importer.run(millis("2026-09-12T10:20:00"))
        gateway.hours = listOf(
            hour("2026-09-12T11:00:00", 500L),
            hour("2026-09-12T12:00:00", 700L),
            // 13:00 is the hour in progress at the time of the pass below.
            hour("2026-09-12T13:00:00", 90L)
        )

        val outcome = importer.run(millis("2026-09-12T13:30:00"))

        assertEquals(StepImportOutcome.Imported(hours = 2, steps = 1_200L), outcome)
        assertEquals(1_200L, stepsOn("2026-09-12"))
        assertEquals(millis("2026-09-12T13:00:00"), gateway.lastRange?.second)
        // The watermark stops an hour short: the recorder writes with a lag, so
        // the hour that just ended is read once more before it is frozen.
        assertEquals(millis("2026-09-12T12:00:00"), store.read().importedUntilMillis)
    }

    @Test
    fun `rereading an hour replaces it instead of adding to it`() = runBlocking {
        importer.run(millis("2026-09-12T10:20:00"))
        gateway.hours = listOf(hour("2026-09-12T11:00:00", 500L))
        importer.run(millis("2026-09-12T12:30:00"))
        // The recorder caught up with the rest of that hour.
        gateway.hours = listOf(hour("2026-09-12T11:00:00", 620L))
        importer.run(millis("2026-09-12T12:45:00"))

        assertEquals(620L, stepsOn("2026-09-12"))
    }

    @Test
    fun `an hour the recorder has nothing for is left as it is, never zeroed`() = runBlocking {
        database.hourlyStepsDao().increment("2026-09-12", 11, 300L)
        importer.run(millis("2026-09-12T10:20:00"))
        gateway.hours = listOf(hour("2026-09-12T11:00:00", 0L))

        importer.run(millis("2026-09-12T13:30:00"))

        assertEquals(300L, stepsOn("2026-09-12"))
    }

    @Test
    fun `a failed read moves nothing and re-arms the subscription`() = runBlocking {
        importer.run(millis("2026-09-12T10:20:00"))
        gateway.hours = listOf(hour("2026-09-12T11:00:00", 500L))
        importer.run(millis("2026-09-12T12:30:00"))
        val before = store.read()
        gateway.failWith = IllegalStateException("play services said no")

        val outcome = importer.run(millis("2026-09-12T14:30:00"))

        assertTrue(outcome is StepImportOutcome.Failed)
        assertEquals(before.importedUntilMillis, store.read().importedUntilMillis)
        assertEquals(before.importFromMillis, store.read().importFromMillis)
        // A revoked-then-granted permission drops the subscription; re-arming on
        // the next pass is how it comes back without the user doing anything.
        assertFalse(store.read().subscribed)
    }

    @Test
    fun `a second pass inside the same hour has nothing to do`() = runBlocking {
        importer.run(millis("2026-09-12T10:20:00"))
        importer.run(millis("2026-09-12T11:30:00"))
        gateway.lastRange = null

        assertEquals(StepImportOutcome.UpToDate, importer.run(millis("2026-09-12T11:45:00")))
        assertNull(gateway.lastRange)
    }

    @Test
    fun `without Play services nothing is asked and nothing is written`() = runBlocking {
        gateway.availabilityValue = RecordingAvailability.UNAVAILABLE

        val outcome = importer.run(millis("2026-09-12T10:20:00"))

        assertEquals(
            StepImportOutcome.Unsupported(RecordingAvailability.UNAVAILABLE),
            outcome
        )
        assertEquals(0, gateway.subscribeCalls)
        assertFalse(store.read().subscribed)
    }

    @Test
    fun `a refused subscription reads as not recording, and is retried`() = runBlocking {
        gateway.subscribeResult = false

        assertEquals(StepImportOutcome.NotRecording, importer.run(millis("2026-09-12T10:20:00")))
        assertFalse(store.read().subscribed)

        gateway.subscribeResult = true
        assertEquals(StepImportOutcome.Arming, importer.run(millis("2026-09-12T10:40:00")))
        assertTrue(store.read().subscribed)
    }

    /** Ten days is what the recorder keeps; asking for a fortnight asks for nothing. */
    @Test
    fun `a long silence asks only for the window the recorder still holds`() = runBlocking {
        importer.run(millis("2026-09-01T10:20:00"))
        importer.run(millis("2026-09-20T09:30:00"))

        val (from, to) = gateway.lastRange!!
        assertEquals(millis("2026-09-20T09:00:00"), to)
        assertEquals(StepImporter.MAX_BACKFILL_MILLIS, to - from)
    }
}

private class FakeRecordingGateway : StepRecordingGateway {

    var availabilityValue: RecordingAvailability = RecordingAvailability.AVAILABLE
    var subscribeResult: Boolean = true
    var hours: List<RecordedHour> = emptyList()
    var failWith: Exception? = null
    var subscribeCalls: Int = 0
    var lastRange: Pair<Long, Long>? = null

    override fun availability(): RecordingAvailability = availabilityValue

    override suspend fun subscribe(): Boolean {
        subscribeCalls++
        return subscribeResult
    }

    override suspend fun unsubscribe(): Boolean = true

    override suspend fun readHourlySteps(fromMillis: Long, toMillis: Long): List<RecordedHour> {
        lastRange = fromMillis to toMillis
        failWith?.let { throw it }
        return hours.filter { it.startMillis >= fromMillis && it.endMillis <= toMillis }
    }
}
