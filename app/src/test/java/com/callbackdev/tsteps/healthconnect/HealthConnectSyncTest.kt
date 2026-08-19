package com.callbackdev.tsteps.healthconnect

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.callbackdev.tsteps.data.SettingsStore
import com.callbackdev.tsteps.data.local.SessionEntity
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Fase 12 reconcile pass over real Room and stores, with the Health Connect
 * client replaced by a recording fake — the JVM has no HC module, and the seam
 * exists exactly for this.
 */
@RunWith(RobolectricTestRunner::class)
class HealthConnectSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rome = ZoneId.of("Europe/Rome")

    private lateinit var database: TstepsDatabase
    private lateinit var settingsStore: SettingsStore
    private lateinit var hcStateStore: HcStateStore
    private lateinit var gateway: FakeGateway
    private lateinit var sync: HealthConnectSync

    private class FakeGateway : HealthConnectGateway {
        override val ownPackageName = "com.callbackdev.tsteps"
        var availabilityValue = HcAvailability.AVAILABLE
        var granted: Set<HcPermission> = HcPermission.entries.toSet()
        var externalRecords: List<HcExternalSteps> = emptyList()
        val upsertedSteps = mutableListOf<HcHourSteps>()
        val upsertedSessions = mutableListOf<HcSessionRecord>()
        val deletedSessionIds = mutableListOf<String>()
        var touched = false

        override fun availability(): HcAvailability {
            touched = true
            return availabilityValue
        }

        override suspend fun grantedPermissions(): Set<HcPermission> = granted

        override suspend fun upsertSteps(hours: List<HcHourSteps>) {
            upsertedSteps += hours
        }

        override suspend fun upsertSessions(sessions: List<HcSessionRecord>) {
            upsertedSessions += sessions
        }

        override suspend fun deleteSessions(clientIds: List<String>) {
            deletedSessionIds += clientIds
        }

        override suspend fun readSteps(fromMillis: Long, toMillis: Long): List<HcExternalSteps> =
            externalRecords
    }

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TstepsDatabase::class.java
        ).allowMainThreadQueries().build()
        settingsStore = SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("s.preferences_pb") }
        )
        hcStateStore = HcStateStore(
            PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("hc.preferences_pb") }
        )
        gateway = FakeGateway()
        sync = HealthConnectSync(
            gateway = gateway,
            settingsStore = settingsStore,
            hourlyDao = database.hourlyStepsDao(),
            sessionDao = database.sessionDao(),
            hcStateStore = hcStateStore,
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

    private val noon = millis("2026-08-19T12:00:00")

    private fun session(
        id: Long = 0,
        start: String = "2026-08-19T09:00:00",
        end: String? = "2026-08-19T09:30:00",
        auto: Boolean = false,
        dismissedAt: String? = null
    ) = SessionEntity(
        id = id,
        startMillis = millis(start),
        endMillis = end?.let { millis(it) },
        type = "walk",
        steps = 3_000,
        distanceMeters = 2_160.0,
        avgCadenceSpm = 100,
        auto = auto,
        activeMillis = 1_800_000L,
        dismissedMillis = dismissedAt?.let { millis(it) }
    )

    @Test
    fun `off by default - the gateway is never even consulted`() = runBlocking {
        database.hourlyStepsDao().increment("2026-08-19", 9, 1_200)
        sync.sync(noon)
        assertTrue(!gateway.touched)
        assertTrue(gateway.upsertedSteps.isEmpty())
        assertNull(hcStateStore.external.first())
    }

    @Test
    fun `an enabled pass rewrites today and yesterday idempotently`() = runBlocking {
        settingsStore.setHealthConnectSync(true)
        database.hourlyStepsDao().increment("2026-08-18", 18, 2_500) // yesterday
        database.hourlyStepsDao().increment("2026-08-19", 9, 1_200)  // today
        database.hourlyStepsDao().increment("2026-08-19", 10, 0)     // empty: skipped
        database.sessionDao().insert(session(auto = true))
        database.sessionDao().insert(
            session(start = "2026-08-19T10:00:00", end = "2026-08-19T10:20:00")
        )

        sync.sync(noon)

        assertEquals(
            listOf("tsteps-steps-2026-08-18-18", "tsteps-steps-2026-08-19-09"),
            gateway.upsertedSteps.map { it.clientId }
        )
        assertEquals(listOf("walk (auto)", "walk"), gateway.upsertedSessions.map { it.title })
        assertTrue(gateway.deletedSessionIds.isEmpty())
    }

    @Test
    fun `a dismissed session is deleted, a running one is not written`() = runBlocking {
        settingsStore.setHealthConnectSync(true)
        val dismissedId = database.sessionDao().insert(
            session(auto = true, dismissedAt = "2026-08-19T10:00:00")
        )
        database.sessionDao().insert(session(start = "2026-08-19T11:00:00", end = null))

        sync.sync(noon)

        assertEquals(listOf("tsteps-session-$dismissedId"), gateway.deletedSessionIds)
        assertTrue(gateway.upsertedSessions.isEmpty()) // the running one has no end yet
    }

    @Test
    fun `the external read lands grouped in the state store`() = runBlocking {
        settingsStore.setHealthConnectSync(true)
        gateway.externalRecords = listOf(
            HcExternalSteps("com.sec.android.app.shealth", 3_000),
            HcExternalSteps("com.sec.android.app.shealth", 2_102),
            HcExternalSteps("com.callbackdev.tsteps", 9_999) // ours: excluded
        )

        sync.sync(noon)

        val state = hcStateStore.external.first()
        assertNotNull(state)
        assertEquals("2026-08-19", state!!.date.toString())
        assertEquals(listOf(OriginSteps("com.sec.android.app.shealth", "shealth", 5_102)), state.origins)
        assertEquals(noon, state.readAtMillis)
    }

    @Test
    fun `permissions scope the pass - write-only touches no reads`() = runBlocking {
        settingsStore.setHealthConnectSync(true)
        gateway.granted = setOf(HcPermission.WRITE_STEPS)
        gateway.externalRecords = listOf(HcExternalSteps("com.other.app", 500))
        database.hourlyStepsDao().increment("2026-08-19", 9, 1_200)
        database.sessionDao().insert(session())

        sync.sync(noon)

        assertEquals(1, gateway.upsertedSteps.size)
        assertTrue(gateway.upsertedSessions.isEmpty())
        assertNull(hcStateStore.external.first())
    }

    @Test
    fun `no Health Connect on the device means a silent pass`() = runBlocking {
        settingsStore.setHealthConnectSync(true)
        gateway.availabilityValue = HcAvailability.UNAVAILABLE
        database.hourlyStepsDao().increment("2026-08-19", 9, 1_200)

        sync.sync(noon)

        assertTrue(gateway.upsertedSteps.isEmpty())
    }

    @Test
    fun `turning sync off clears the cached external read`() = runBlocking {
        settingsStore.setHealthConnectSync(true)
        gateway.externalRecords = listOf(HcExternalSteps("com.other.app", 500))
        sync.sync(noon)
        assertNotNull(hcStateStore.external.first())

        settingsStore.setHealthConnectSync(false)
        sync.sync(noon)
        assertNull(hcStateStore.external.first())
    }
}
