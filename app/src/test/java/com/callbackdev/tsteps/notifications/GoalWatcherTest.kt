package com.callbackdev.tsteps.notifications

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.callbackdev.tsteps.data.NotificationStateStore
import com.callbackdev.tsteps.data.SettingsStore
import com.callbackdev.tsteps.data.StepRepository
import com.callbackdev.tsteps.data.TrackerStateStore
import com.callbackdev.tsteps.data.local.HourlyStepsEntity
import com.callbackdev.tsteps.data.local.TstepsDatabase
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
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

@RunWith(RobolectricTestRunner::class)
class GoalWatcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO)
    private val rome = ZoneId.of("Europe/Rome")
    private val clock = Clock.fixed(
        LocalDateTime.parse("2026-08-18T10:00:00").atZone(rome).toInstant(), rome
    )
    private val resources =
        ApplicationProvider.getApplicationContext<android.content.Context>().resources

    private lateinit var database: TstepsDatabase
    private lateinit var settingsStore: SettingsStore
    private lateinit var stateStore: NotificationStateStore
    private lateinit var repository: StepRepository
    private val posted = mutableListOf<StepsNotifications.Content>()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TstepsDatabase::class.java
        ).allowMainThreadQueries().build()
        settingsStore = SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("s.preferences_pb") }
        )
        stateStore = NotificationStateStore(
            PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("n.preferences_pb") }
        )
        repository = StepRepository(
            hourlyDao = database.hourlyStepsDao(),
            dayDao = database.daySummaryDao(),
            sessionDao = database.sessionDao(),
            trackerStateStore = TrackerStateStore(
                PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("t.preferences_pb") }
            ),
            settingsStore = settingsStore,
            zone = { rome }
        )
        posted.clear()
    }

    @After
    fun tearDown() {
        database.close()
        scope.cancel()
    }

    private suspend fun evaluate() = GoalWatcher.evaluate(
        repository = repository,
        settingsStore = settingsStore,
        stateStore = stateStore,
        post = { posted += it },
        resources = { resources },
        clock = clock,
        locale = Locale.ENGLISH
    )

    @Test
    fun `crossing the goal posts once, edge-triggered per day`() = runBlocking {
        settingsStore.setDailyGoalSteps(1_000)
        database.hourlyStepsDao().upsert(HourlyStepsEntity("2026-08-18", 9, 1_200))

        evaluate()
        assertEquals(1, posted.size)
        assertTrue(posted.single().title.contains("1,200"))

        evaluate() // same day, more steps later: silence
        assertEquals(1, posted.size)
    }

    @Test
    fun `below the goal nothing posts and the state stays armed`() = runBlocking {
        settingsStore.setDailyGoalSteps(5_000)
        database.hourlyStepsDao().upsert(HourlyStepsEntity("2026-08-18", 9, 1_200))
        evaluate()
        assertTrue(posted.isEmpty())
        // Later the goal is crossed: still armed.
        database.hourlyStepsDao().increment("2026-08-18", 10, 4_000)
        evaluate()
        assertEquals(1, posted.size)
    }

    @Test
    fun `no goal or toggle off means no watcher at all`() = runBlocking {
        database.hourlyStepsDao().upsert(HourlyStepsEntity("2026-08-18", 9, 9_999))
        evaluate() // goal 0
        settingsStore.setDailyGoalSteps(1_000)
        settingsStore.setNotifGoalCheck(false)
        evaluate() // toggle off
        assertTrue(posted.isEmpty())
    }
}
