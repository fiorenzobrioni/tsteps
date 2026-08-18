package com.callbackdev.tsteps.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.callbackdev.tsteps.data.local.TstepsDatabase
import com.callbackdev.tsteps.domain.StepReading
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StepRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rome = ZoneId.of("Europe/Rome")

    private lateinit var database: TstepsDatabase
    private lateinit var settingsStore: SettingsStore
    private lateinit var repository: StepRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TstepsDatabase::class.java
        ).allowMainThreadQueries().build()
        settingsStore = SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("s.preferences_pb") }
        )
        repository = StepRepository(
            hourlyDao = database.hourlyStepsDao(),
            dayDao = database.daySummaryDao(),
            trackerStateStore = TrackerStateStore(
                PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("t.preferences_pb") }
            ),
            settingsStore = settingsStore,
            zone = { rome }
        )
    }

    @After
    fun tearDown() {
        database.close()
        scope.cancel()
    }

    private fun millis(dateTime: String): Long =
        LocalDateTime.parse(dateTime).atZone(rome).toInstant().toEpochMilli()

    private fun reading(cumulative: Long, at: String, boot: Int = 3) =
        StepReading(cumulative, boot, millis(at))

    @Test
    fun `first reading anchors silently, the second writes its delta`() = runBlocking {
        repository.ingest(reading(50_000L, "2026-08-18T09:00:00"))
        assertTrue(database.hourlyStepsDao().day("2026-08-18").isEmpty())

        repository.ingest(reading(50_600L, "2026-08-18T09:30:00"))
        val rows = database.hourlyStepsDao().day("2026-08-18")
        assertEquals(1, rows.size)
        assertEquals(9, rows.single().hour)
        assertEquals(600L, rows.single().steps)
    }

    @Test
    fun `deltas accumulate in their buckets across readings`() = runBlocking {
        repository.ingest(reading(1_000L, "2026-08-18T09:00:00"))
        repository.ingest(reading(1_300L, "2026-08-18T09:20:00"))
        repository.ingest(reading(1_800L, "2026-08-18T09:50:00"))
        assertEquals(800L, database.hourlyStepsDao().day("2026-08-18").sumOf { it.steps })
    }

    @Test
    fun `a span across midnight credits both dates`() = runBlocking {
        repository.ingest(reading(2_000L, "2026-08-18T23:40:00"))
        repository.ingest(reading(2_600L, "2026-08-19T00:20:00"))
        assertEquals(300L, database.hourlyStepsDao().day("2026-08-18").sumOf { it.steps })
        assertEquals(300L, database.hourlyStepsDao().day("2026-08-19").sumOf { it.steps })
    }

    @Test
    fun `commit freezes the day with goal snapshot and estimates`() = runBlocking {
        settingsStore.setDailyGoalSteps(500)
        settingsStore.setWeightKg(78.0)
        settingsStore.setHeightCm(175)
        repository.ingest(reading(0L, "2026-08-18T09:00:00"))
        repository.ingest(reading(3_000L, "2026-08-18T09:45:00"))

        repository.commitDaysBefore(LocalDate.parse("2026-08-19"))

        val day = database.daySummaryDao().byDate("2026-08-18")
        assertNotNull(day)
        assertEquals(3_000L, day!!.steps)
        assertEquals(30, day.activeMinutes) // 3000 steps at 100 spm
        assertEquals(3_000 * 0.72625, day.distanceMeters, 1e-6)
        assertEquals(3.3 * 78.0 * 0.5, day.activeKcal!!, 1e-6)
        assertEquals(500, day.goalSteps)
        assertEquals(true, day.goalMet)
    }

    @Test
    fun `no goal means the check is skipped, not failed`() = runBlocking {
        repository.ingest(reading(0L, "2026-08-18T09:00:00"))
        repository.ingest(reading(200L, "2026-08-18T09:30:00"))
        repository.commitDaysBefore(LocalDate.parse("2026-08-19"))
        assertNull(database.daySummaryDao().byDate("2026-08-18")!!.goalMet)
    }

    @Test
    fun `kcal stays null without a weight`() = runBlocking {
        repository.ingest(reading(0L, "2026-08-18T09:00:00"))
        repository.ingest(reading(400L, "2026-08-18T09:30:00"))
        repository.commitDaysBefore(LocalDate.parse("2026-08-19"))
        assertNull(database.daySummaryDao().byDate("2026-08-18")!!.activeKcal)
    }

    @Test
    fun `today is never committed`() = runBlocking {
        repository.ingest(reading(0L, "2026-08-18T09:00:00"))
        repository.ingest(reading(500L, "2026-08-18T09:30:00"))
        repository.commitDaysBefore(LocalDate.parse("2026-08-18"))
        assertNull(database.daySummaryDao().byDate("2026-08-18"))
    }

    @Test
    fun `commit is idempotent and never rewrites history`() = runBlocking {
        repository.ingest(reading(0L, "2026-08-18T09:00:00"))
        repository.ingest(reading(500L, "2026-08-18T09:30:00"))
        repository.commitDaysBefore(LocalDate.parse("2026-08-19"))

        // The profile changes later: a re-run must not touch the committed day.
        settingsStore.setWeightKg(90.0)
        repository.commitDaysBefore(LocalDate.parse("2026-08-19"))

        val day = database.daySummaryDao().byDate("2026-08-18")!!
        assertNull(day.activeKcal)
        assertEquals(1, database.daySummaryDao().all().size)
    }

    @Test
    fun `a reboot between readings does not double or drop steps`() = runBlocking {
        repository.ingest(reading(9_000L, "2026-08-18T09:00:00", boot = 3))
        repository.ingest(reading(9_500L, "2026-08-18T10:00:00", boot = 3))
        // Reboot: counter restarts; 250 steps walked after boot.
        repository.ingest(reading(250L, "2026-08-18T12:00:00", boot = 4))
        assertEquals(750L, database.hourlyStepsDao().day("2026-08-18").sumOf { it.steps })
    }
}
