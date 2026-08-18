package com.callbackdev.tsteps.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NotificationStateStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO)

    private fun store(file: File) = NotificationStateStore(
        PreferenceDataStoreFactory.create(scope = scope) { file }
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `armed until the first goal notification, then remembers its day`() = runBlocking {
        val store = store(tmp.newFile("n.preferences_pb"))
        assertNull(store.goalNotifiedDate())
        store.markGoalNotified(LocalDate.parse("2026-08-18"))
        assertEquals(LocalDate.parse("2026-08-18"), store.goalNotifiedDate())
    }
}
