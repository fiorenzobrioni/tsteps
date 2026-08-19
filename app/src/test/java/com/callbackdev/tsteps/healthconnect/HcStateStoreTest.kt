package com.callbackdev.tsteps.healthconnect

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HcStateStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun store() = HcStateStore(
        PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("hc.preferences_pb") }
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a multi-origin state round-trips exactly`() = runBlocking {
        val store = store()
        val state = ExternalStepsState(
            date = LocalDate.parse("2026-08-19"),
            origins = listOf(
                OriginSteps("com.sec.android.app.shealth", "shealth", 5_102),
                OriginSteps("com.fitbit.FitbitMobile", "fitbitmobile", 4_988)
            ),
            readAtMillis = 1_787_000_000_000L
        )
        store.write(state)
        assertEquals(state, store.external.first())
    }

    @Test
    fun `empty until written, empty again after clear`() = runBlocking {
        val store = store()
        assertNull(store.external.first())
        store.write(
            ExternalStepsState(LocalDate.parse("2026-08-19"), emptyList(), 1L)
        )
        store.clear()
        assertNull(store.external.first())
    }
}
