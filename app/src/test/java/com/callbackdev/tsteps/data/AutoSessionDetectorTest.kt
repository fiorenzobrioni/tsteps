package com.callbackdev.tsteps.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.callbackdev.tsteps.data.local.SessionEntity
import com.callbackdev.tsteps.data.local.StepSampleEntity
import com.callbackdev.tsteps.data.local.TstepsDatabase
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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

/**
 * The Fase 11 orchestration over real Room: samples in, `(auto)` sessions out —
 * and, just as important, all the ways it must stay silent: disabled, already
 * detected, dismissed by the user, claimed by manual tracking.
 */
@RunWith(RobolectricTestRunner::class)
class AutoSessionDetectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rome = ZoneId.of("Europe/Rome")

    private lateinit var database: TstepsDatabase
    private lateinit var settingsStore: SettingsStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TstepsDatabase::class.java
        ).allowMainThreadQueries().build()
        settingsStore = SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("s.preferences_pb") }
        )
    }

    @After
    fun tearDown() {
        database.close()
        scope.cancel()
    }

    private fun detector(trackingStart: Long? = null) = AutoSessionDetector(
        sessionDao = database.sessionDao(),
        sampleDao = database.stepSampleDao(),
        settingsStore = settingsStore,
        trackingStartMillis = { trackingStart },
        zone = { rome }
    )

    private fun millis(dateTime: String): Long =
        LocalDateTime.parse(dateTime).atZone(rome).toInstant().toEpochMilli()

    private val noon = millis("2026-08-18T12:00:00")

    /** A clean 09:00..09:30 walk at 100 spm, framed by explicit stillness. */
    private fun insertWalkSamples() = runBlocking {
        val dao = database.stepSampleDao()
        dao.insert(sample("2026-08-18T08:45:00", "2026-08-18T09:00:00", 0))
        dao.insert(sample("2026-08-18T09:00:00", "2026-08-18T09:15:00", 1_500))
        dao.insert(sample("2026-08-18T09:15:00", "2026-08-18T09:30:00", 1_500))
        dao.insert(sample("2026-08-18T09:30:00", "2026-08-18T09:45:00", 0))
    }

    private fun sample(from: String, to: String, steps: Long) =
        StepSampleEntity(fromMillis = millis(from), toMillis = millis(to), steps = steps)

    @Test
    fun `disabled by default - detects nothing and wipes any leftover samples`() = runBlocking {
        insertWalkSamples()
        val created = detector().run(noon)
        assertTrue(created.isEmpty())
        assertEquals(0L, database.stepSampleDao().count())
    }

    @Test
    fun `an enabled run turns the walk samples into one auto session`() = runBlocking {
        settingsStore.setAutoDetectSessions(true)
        insertWalkSamples()
        val created = detector().run(noon)

        val session = created.single()
        assertEquals(millis("2026-08-18T09:00:00"), session.startMillis)
        assertEquals(millis("2026-08-18T09:30:00"), session.endMillis)
        assertEquals("walk", session.type)
        assertEquals(3_000L, session.steps)
        assertTrue(session.auto)
        assertEquals(30 * 60_000L, session.activeMillis)
        // No profile height set: the labeled 0.72 m default stride.
        assertEquals(2_160.0, session.distanceMeters!!, 1e-6)
        assertEquals(100, session.avgCadenceSpm)
        // The detected window is stamped for dedup, equal to the boundaries.
        assertEquals(session.startMillis, session.detectedStartMillis)
        assertEquals(session.endMillis, session.detectedEndMillis)
    }

    @Test
    fun `a second pass over the same samples inserts nothing`() = runBlocking {
        settingsStore.setAutoDetectSessions(true)
        insertWalkSamples()
        assertEquals(1, detector().run(noon).size)
        assertTrue(detector().run(noon).isEmpty())
        assertEquals(1, database.sessionDao().observeAll().first().size)
    }

    @Test
    fun `a dismissed auto session is never resurrected`() = runBlocking {
        settingsStore.setAutoDetectSessions(true)
        insertWalkSamples()
        val session = detector().run(noon).single()

        database.sessionDao().dismiss(session.id, noon)
        assertTrue(detector().run(noon).isEmpty())
        // Gone from every screen, alive as the detector's exclusion.
        assertTrue(database.sessionDao().observeAll().first().isEmpty())
        assertEquals(
            1,
            database.sessionDao()
                .overlappingIncludingDismissed(millis("2026-08-18T00:00:00"), noon).size
        )
    }

    @Test
    fun `a manual session already claiming the window blocks detection`() = runBlocking {
        settingsStore.setAutoDetectSessions(true)
        insertWalkSamples()
        database.sessionDao().insert(
            SessionEntity(
                startMillis = millis("2026-08-18T09:05:00"),
                endMillis = millis("2026-08-18T09:20:00"),
                type = "walk",
                steps = 1_400,
                distanceMeters = 1_000.0,
                avgCadenceSpm = 95,
                auto = false,
                activeMillis = 15 * 60_000L
            )
        )
        assertTrue(detector().run(noon).isEmpty())
    }

    @Test
    fun `the live tracking window is off limits`() = runBlocking {
        settingsStore.setAutoDetectSessions(true)
        insertWalkSamples()
        val created = detector(trackingStart = millis("2026-08-18T08:50:00")).run(noon)
        assertTrue(created.isEmpty())
    }

    @Test
    fun `samples beyond retention are pruned on an enabled run`() = runBlocking {
        settingsStore.setAutoDetectSessions(true)
        database.stepSampleDao().insert(
            sample("2026-08-14T09:00:00", "2026-08-14T09:15:00", 1_500)
        )
        detector().run(noon)
        assertEquals(0L, database.stepSampleDao().count())
    }
}
