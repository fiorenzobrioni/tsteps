package com.callbackdev.tsteps.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Room
import com.callbackdev.tsteps.data.local.TstepsDatabase
import com.callbackdev.tsteps.export.DataExporter
import com.callbackdev.tsteps.export.DownloadsExportSink
import com.callbackdev.tsteps.healthconnect.AndroidHealthConnectGateway
import com.callbackdev.tsteps.healthconnect.HcStateStore
import com.callbackdev.tsteps.healthconnect.HealthConnectSync

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

    // Typed to the interface, not the reader: the workers, the widget tap and the
    // tracking service all consume it as a source, and only that lets a test feed
    // synthetic readings (or a silent counter) to the code that samples.
    @Volatile
    private var stepSensorReader: StepSource? = null

    @Volatile
    private var trackingManager: TrackingManager? = null

    @Volatile
    private var workspaceStore: WorkspaceStore? = null

    @Volatile
    private var firstRunStore: FirstRunStore? = null

    @Volatile
    private var notificationStateStore: NotificationStateStore? = null

    @Volatile
    private var hcStateStore: HcStateStore? = null

    @Volatile
    private var healthConnectSync: HealthConnectSync? = null

    fun database(context: Context): TstepsDatabase =
        database ?: synchronized(this) {
            database ?: Room.databaseBuilder(
                context.applicationContext,
                TstepsDatabase::class.java,
                "tsteps.db"
            )
                .addMigrations(TstepsDatabase.MIGRATION_1_2, TstepsDatabase.MIGRATION_2_3)
                .build().also { database = it }
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
                sessionDao = database(context).sessionDao(),
                sampleDao = database(context).stepSampleDao(),
                trackerStateStore = trackerStateStore(context),
                settingsStore = settingsStore(context)
            ).also { stepRepository = it }
        }

    fun stepSensorReader(context: Context): StepSource =
        stepSensorReader ?: synchronized(this) {
            stepSensorReader ?: StepSensorReader(context.applicationContext)
                .also { stepSensorReader = it }
        }

    fun notificationStateStore(context: Context): NotificationStateStore =
        notificationStateStore ?: synchronized(this) {
            notificationStateStore ?: NotificationStateStore.create(context.applicationContext)
                .also { notificationStateStore = it }
        }

    fun workspaceStore(context: Context): WorkspaceStore =
        workspaceStore ?: synchronized(this) {
            workspaceStore ?: WorkspaceStore.create(context.applicationContext)
                .also { workspaceStore = it }
        }

    fun firstRunStore(context: Context): FirstRunStore =
        firstRunStore ?: synchronized(this) {
            firstRunStore ?: FirstRunStore.create(context.applicationContext)
                .also { firstRunStore = it }
        }

    fun trackingManager(context: Context): TrackingManager =
        trackingManager ?: synchronized(this) {
            trackingManager ?: TrackingManager(
                repository = stepRepository(context),
                settingsStore = settingsStore(context)
            ).also { trackingManager = it }
        }

    /**
     * Stateless orchestration, built fresh per call (the workers are its only
     * clients): all state lives in the DAOs and stores it reads.
     */
    fun autoSessionDetector(context: Context): AutoSessionDetector =
        AutoSessionDetector(
            sessionDao = database(context).sessionDao(),
            sampleDao = database(context).stepSampleDao(),
            settingsStore = settingsStore(context),
            trackingStartMillis = {
                trackingManager(context).state.value?.session?.startMillis
            }
        )

    fun hcStateStore(context: Context): HcStateStore =
        hcStateStore ?: synchronized(this) {
            hcStateStore ?: HcStateStore.create(context.applicationContext)
                .also { hcStateStore = it }
        }

    /** Singleton: its mutex serializes the overlapping reconcile callers. */
    fun healthConnectSync(context: Context): HealthConnectSync =
        healthConnectSync ?: synchronized(this) {
            healthConnectSync ?: HealthConnectSync(
                gateway = AndroidHealthConnectGateway(context.applicationContext),
                settingsStore = settingsStore(context),
                hourlyDao = database(context).hourlyStepsDao(),
                sessionDao = database(context).sessionDao(),
                hcStateStore = hcStateStore(context)
            ).also { healthConnectSync = it }
        }

    /**
     * Stateless like the detector, built per call: an export is one pass over
     * Room triggered by a tap, with nothing to keep between taps.
     */
    fun dataExporter(context: Context): DataExporter =
        DataExporter(
            hourlyDao = database(context).hourlyStepsDao(),
            dayDao = database(context).daySummaryDao(),
            sessionDao = database(context).sessionDao(),
            settingsStore = settingsStore(context),
            sink = DownloadsExportSink(context.applicationContext)
        )

    /**
     * Test-only: swap dependencies for worker tests. Calling with no arguments
     * resets to lazy real instances (do it in @After — the object outlives the
     * test).
     */
    @VisibleForTesting
    fun overrideForTests(
        stepRepository: StepRepository? = null,
        stepSensorReader: StepSource? = null,
        settingsStore: SettingsStore? = null,
        trackerStateStore: TrackerStateStore? = null,
        firstRunStore: FirstRunStore? = null
    ) {
        this.firstRunStore = firstRunStore
        this.stepRepository = stepRepository
        this.stepSensorReader = stepSensorReader
        this.settingsStore = settingsStore
        this.trackerStateStore = trackerStateStore
        this.database = null
    }
}
