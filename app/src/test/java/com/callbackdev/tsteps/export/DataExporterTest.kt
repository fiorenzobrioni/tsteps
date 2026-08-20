package com.callbackdev.tsteps.export

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.callbackdev.tsteps.data.SettingsStore
import com.callbackdev.tsteps.data.local.DaySummaryEntity
import com.callbackdev.tsteps.data.local.HourlyStepsEntity
import com.callbackdev.tsteps.data.local.SessionEntity
import com.callbackdev.tsteps.data.local.TstepsDatabase
import java.io.IOException
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The gathering pass: what lands in the archive, and what is deliberately left out. */
@RunWith(RobolectricTestRunner::class)
class DataExporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rome = ZoneId.of("Europe/Rome")
    private val now = millis("2026-08-20T18:32:05")

    private lateinit var database: TstepsDatabase
    private lateinit var settingsStore: SettingsStore
    private lateinit var sink: RecordingSink
    private lateinit var exporter: DataExporter

    /** Records what was handed over, and can rename or fail like the real store. */
    private class RecordingSink(
        val failure: Throwable? = null,
        val rename: ((String) -> String)? = null
    ) : ExportSink {
        val files = mutableListOf<ExportFile>()

        override suspend fun write(file: ExportFile): String {
            failure?.let { throw it }
            files += file
            return rename?.invoke(file.name) ?: file.name
        }
    }

    private fun millis(dateTime: String): Long =
        LocalDateTime.parse(dateTime).atZone(rome).toInstant().toEpochMilli()

    private fun exporterWith(sink: RecordingSink): DataExporter = DataExporter(
        hourlyDao = database.hourlyStepsDao(),
        dayDao = database.daySummaryDao(),
        sessionDao = database.sessionDao(),
        settingsStore = settingsStore,
        sink = sink,
        zone = { rome }
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TstepsDatabase::class.java
        ).allowMainThreadQueries().build()
        settingsStore = SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("s.preferences_pb") }
        )
        sink = RecordingSink()
        exporter = exporterWith(sink)
    }

    @After
    fun tearDown() {
        database.close()
        scope.cancel()
    }

    private suspend fun seedCommittedDay() {
        database.hourlyStepsDao().upsert(HourlyStepsEntity("2026-08-19", 9, 11_204))
        database.daySummaryDao().insertIfAbsent(
            DaySummaryEntity(
                date = "2026-08-19",
                steps = 11_204,
                activeMinutes = 96,
                distanceMeters = 8_310.24,
                activeKcal = 312.4,
                goalSteps = 10_000,
                goalMet = true
            )
        )
    }

    private suspend fun seedToday() {
        database.hourlyStepsDao().upsert(HourlyStepsEntity("2026-08-20", 7, 3_000))
        database.hourlyStepsDao().upsert(HourlyStepsEntity("2026-08-20", 8, 1_120))
    }

    @Test
    fun `history and the working tree both ship, each labelled for what it is`() = runBlocking {
        seedCommittedDay()
        seedToday()
        settingsStore.setHeightCm(175)
        settingsStore.setDailyGoalSteps(10_000)

        val result = exporter.export(ExportFormat.JSON, now) as ExportResult.Written

        assertEquals(2, result.days)
        assertEquals(listOf("tsteps-export-2026-08-20.json"), result.files)
        val json = sink.files.single().content
        // The commit keeps the numbers frozen at commit time.
        assertTrue(json.contains(""""date": "2026-08-19", "commit": """"))
        assertTrue(json.contains(""""distance_m": 8310.2"""))
        assertTrue(json.contains(""""committed": true"""))
        // Today is computed live with the current profile, and says it's open.
        assertTrue(json.contains(""""date": "2026-08-20""""))
        assertTrue(json.contains(""""steps": 4120"""))
        assertTrue(json.contains(""""committed": false"""))
        // No weight in the profile: kcal is missing, not invented.
        assertTrue(json.contains(""""active_kcal": null"""))
        // The goal is a real check on the open day too.
        assertTrue(json.contains(""""goal_met": false"""))
    }

    @Test
    fun `an uncommitted past day is exported as the open day it still is`() = runBlocking {
        // The safety net hasn't run yet: buckets without a commit row.
        database.hourlyStepsDao().upsert(HourlyStepsEntity("2026-08-18", 10, 5_000))

        exporter.export(ExportFormat.JSON, now)

        val json = sink.files.single().content
        assertTrue(json.contains(""""date": "2026-08-18""""))
        assertTrue(json.contains(""""committed": false"""))
    }

    @Test
    fun `a day whose buckets are all zero is not a day`() = runBlocking {
        database.hourlyStepsDao().upsert(HourlyStepsEntity("2026-08-18", 10, 0))
        seedToday()

        val result = exporter.export(ExportFormat.JSON, now) as ExportResult.Written

        assertEquals(1, result.days)
        assertFalse(sink.files.single().content.contains("2026-08-18"))
    }

    @Test
    fun `removed and running sessions stay out of the archive`() = runBlocking {
        seedToday()
        val dao = database.sessionDao()
        dao.insert(
            SessionEntity(
                startMillis = millis("2026-08-20T07:05:00"),
                endMillis = millis("2026-08-20T07:40:00"),
                type = "walk",
                steps = 3_010,
                distanceMeters = 2_200.0,
                avgCadenceSpm = 86,
                activeMillis = 35 * 60_000L
            )
        )
        // `[rm]`d: a tombstone is the detector's memory, not the user's data.
        dao.insert(
            SessionEntity(
                startMillis = millis("2026-08-20T12:00:00"),
                endMillis = millis("2026-08-20T12:30:00"),
                type = "walk",
                steps = 900,
                distanceMeters = 700.0,
                avgCadenceSpm = 60,
                activeMillis = 30 * 60_000L,
                dismissedMillis = now
            )
        )
        // Still running: no end, no record.
        dao.insert(
            SessionEntity(
                startMillis = millis("2026-08-20T18:00:00"),
                endMillis = null,
                type = "walk",
                steps = 200,
                distanceMeters = 150.0,
                avgCadenceSpm = null,
                activeMillis = 0
            )
        )

        val result = exporter.export(ExportFormat.CSV, now) as ExportResult.Written

        assertEquals(1, result.sessions)
        val sessions = sink.files.first { it.name.contains("sessions") }.content.trim().lines()
        assertEquals(2, sessions.size) // header + the one real session
        assertTrue(sessions[1].startsWith("2026-08-20,2026-08-20T07:05:00+02:00"))
    }

    @Test
    fun `csv writes both tables and reports the names the store gave them`() = runBlocking {
        seedToday()
        val renaming = RecordingSink(rename = { it.replace(".csv", " (1).csv") })

        val result = exporterWith(renaming).export(ExportFormat.CSV, now) as ExportResult.Written

        assertEquals(
            listOf("tsteps-days-2026-08-20 (1).csv", "tsteps-sessions-2026-08-20 (1).csv"),
            result.files
        )
    }

    @Test
    fun `an empty history says so instead of writing an empty file`() = runBlocking {
        val result = exporter.export(ExportFormat.JSON, now)

        assertEquals(ExportResult.Empty, result)
        assertTrue(sink.files.isEmpty())
    }

    @Test
    fun `a storage failure comes back as the error line, not a crash`() = runBlocking {
        seedToday()
        val failing = RecordingSink(failure = IOException("Downloads is not writable"))

        val result = exporterWith(failing).export(ExportFormat.JSON, now)

        assertEquals(ExportResult.Failed("Downloads is not writable"), result)
    }
}
