package com.callbackdev.tsteps.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Room
import com.callbackdev.tsteps.data.local.TstepsDatabase

/**
 * Hand-rolled DI, tweather's pattern: the app is small enough that a lazy
 * singleton graph beats a Hilt setup (decision recorded in PLANNING.md).
 * Workers resolve their dependencies from here so tests can swap them.
 */
object ServiceLocator {

    @Volatile
    private var database: TstepsDatabase? = null

    @Volatile
    private var settingsStore: SettingsStore? = null

    @Volatile
    private var trackerStateStore: TrackerStateStore? = null

    @Volatile
    private var stepRepository: StepRepository? = null

    @Volatile
    private var stepSensorReader: StepSensorReader? = null

    fun database(context: Context): TstepsDatabase =
        database ?: synchronized(this) {
            database ?: Room.databaseBuilder(
                context.applicationContext,
                TstepsDatabase::class.java,
                "tsteps.db"
            ).build().also { database = it }
        }

    fun settingsStore(context: Context): SettingsStore =
        settingsStore ?: synchronized(this) {
            settingsStore ?: SettingsStore.create(context.applicationContext)
                .also { settingsStore = it }
        }

    fun trackerStateStore(context: Context): TrackerStateStore =
        trackerStateStore ?: synchronized(this) {
            trackerStateStore ?: TrackerStateStore.create(context.applicationContext)
                .also { trackerStateStore = it }
        }

    fun stepRepository(context: Context): StepRepository =
        stepRepository ?: synchronized(this) {
            stepRepository ?: StepRepository(
                hourlyDao = database(context).hourlyStepsDao(),
                dayDao = database(context).daySummaryDao(),
                trackerStateStore = trackerStateStore(context),
                settingsStore = settingsStore(context)
            ).also { stepRepository = it }
        }

    fun stepSensorReader(context: Context): StepSensorReader =
        stepSensorReader ?: synchronized(this) {
            stepSensorReader ?: StepSensorReader(context.applicationContext)
                .also { stepSensorReader = it }
        }

    /**
     * Test-only: swap dependencies for worker tests. Calling with no arguments
     * resets to lazy real instances (do it in @After — the object outlives the
     * test).
     */
    @VisibleForTesting
    fun overrideForTests(
        stepRepository: StepRepository? = null,
        stepSensorReader: StepSensorReader? = null,
        settingsStore: SettingsStore? = null,
        trackerStateStore: TrackerStateStore? = null
    ) {
        this.stepRepository = stepRepository
        this.stepSensorReader = stepSensorReader
        this.settingsStore = settingsStore
        this.trackerStateStore = trackerStateStore
        this.database = null
    }
}
