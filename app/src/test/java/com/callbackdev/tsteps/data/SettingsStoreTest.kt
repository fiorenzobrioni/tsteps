package com.callbackdev.tsteps.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun store(file: File) = SettingsStore(
        PreferenceDataStoreFactory.create(scope = scope) { file }
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `defaults - no goal, empty profile, metric, Obsidian`() = runBlocking {
        val settings = store(tmp.newFile("s.preferences_pb")).read()
        assertEquals(AppSettings(), settings)
        assertEquals(0, settings.dailyGoalSteps)
        assertNull(settings.weightKg)
        assertNull(settings.heightCm)
        assertEquals(UnitsSystem.METRIC, settings.units)
        assertEquals("Obsidian", settings.themeProfileName)
    }

    @Test
    fun `goal and profile round-trip`() = runBlocking {
        val store = store(tmp.newFile("s.preferences_pb"))
        store.setDailyGoalSteps(8_000)
        store.setWeightKg(78.0)
        store.setHeightCm(175)
        store.setUnits(UnitsSystem.IMPERIAL)
        val settings = store.read()
        assertEquals(8_000, settings.dailyGoalSteps)
        assertEquals(78.0, settings.weightKg!!, 1e-9)
        assertEquals(175, settings.heightCm)
        assertEquals(UnitsSystem.IMPERIAL, settings.units)
    }

    @Test
    fun `clearing a profile value hides the metric again`() = runBlocking {
        val store = store(tmp.newFile("s.preferences_pb"))
        store.setWeightKg(78.0)
        store.setWeightKg(null)
        assertNull(store.read().weightKg)
    }

    @Test
    fun `negative goal is clamped to off`() = runBlocking {
        val store = store(tmp.newFile("s.preferences_pb"))
        store.setDailyGoalSteps(-100)
        assertEquals(0, store.read().dailyGoalSteps)
    }
}
