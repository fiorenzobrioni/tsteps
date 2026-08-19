package com.callbackdev.tsteps.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.callbackdev.tsteps.data.local.TstepsDatabase
import com.callbackdev.tsteps.domain.StepReading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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
class TrackingManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var database: TstepsDatabase
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: TrackingManager

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TstepsDatabase::class.java
        ).allowMainThreadQueries().build()
        settingsStore = SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("s.preferences_pb") }
        )
        manager = TrackingManager(
            repository = StepRepository(
                hourlyDao = database.hourlyStepsDao(),
                dayDao = database.daySummaryDao(),
                sessionDao = database.sessionDao(),
                sampleDao = database.stepSampleDao(),
                trackerStateStore = TrackerStateStore(
                    PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("t.preferences_pb") }
                ),
                settingsStore = settingsStore
            ),
            settingsStore = settingsStore
        )
    }

    @After
    fun tearDown() {
        database.close()
        scope.cancel()
    }

    private fun reading(cumulative: Long) = StepReading(cumulative, 3, 0L)

    @Test
    fun `start anchors with the profile's stride, stop persists the hunk`() = runBlocking {
        settingsStore.setHeightCm(175) // stride 0.72625 m
        manager.start("walk", nowMillis = 0L)
        assertTrue(manager.isActive)

        manager.onReading(reading(50_000L))
        manager.onReading(reading(52_431L))
        assertEquals(2_431L, manager.state.value?.session?.steps)

        val stored = manager.stop(nowMillis = 24 * 60_000L)!!
        assertFalse(manager.isActive)
        assertNull(manager.state.value)
        assertEquals(2_431L, stored.steps)
        assertEquals("walk", stored.type)
        assertEquals(24 * 60_000L, stored.activeMillis)
        assertEquals(2_431 * 0.72625, stored.distanceMeters!!, 1e-6)
        assertEquals(101, stored.avgCadenceSpm) // 2431 steps / 24 min

        val rows = database.sessionDao().observeAll().first()
        assertEquals(1, rows.size)
        assertEquals(2_431L, rows.single().steps)
    }

    @Test
    fun `pause excludes steps and freezes the active duration`() = runBlocking {
        manager.start("walk", 0L)
        manager.onReading(reading(1_000L))
        manager.onReading(reading(1_100L)) // +100
        manager.pause(5 * 60_000L)
        manager.onReading(reading(1_500L)) // +400 during ^Z: not the session's
        manager.resume(10 * 60_000L)
        manager.onReading(reading(1_600L)) // +100

        val stored = manager.stop(15 * 60_000L)!!
        assertEquals(200L, stored.steps)
        assertEquals(10 * 60_000L, stored.activeMillis) // 15 min wall - 5 paused
    }

    @Test
    fun `transcript collects minute marks and pause events`() = runBlocking {
        manager.start("walk", 0L)
        manager.onReading(reading(0L))
        manager.onReading(reading(150L))
        manager.onMinuteTick(60_000L)
        manager.pause(90_000L)
        manager.onMinuteTick(100_000L) // paused: no minute line
        manager.resume(120_000L)
        val transcript = manager.state.value!!.transcript
        assertEquals(3, transcript.size)
        assertTrue(transcript[0] is TranscriptEntry.Minute)
        assertEquals(TranscriptEntry.Paused, transcript[1])
        assertEquals(TranscriptEntry.Resumed, transcript[2])
        assertEquals(150L, (transcript[0] as TranscriptEntry.Minute).steps)
        manager.stop(180_000L)
        Unit
    }

    @Test
    fun `starting twice is a no-op, stopping without a session returns null`() = runBlocking {
        assertNull(manager.stop(0L))
        manager.start("walk", 0L)
        manager.start("other", 10L)
        assertEquals("walk", manager.state.value?.session?.type)
        manager.stop(20L)
        Unit
    }
}
