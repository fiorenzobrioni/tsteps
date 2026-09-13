package com.callbackdev.tsteps.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.callbackdev.tsteps.data.local.TstepsDatabase
import com.callbackdev.tsteps.export.DataExporter
import com.callbackdev.tsteps.export.DownloadsExportSink
import com.callbackdev.tsteps.healthconnect.AndroidHealthConnectGateway
import com.callbackdev.tsteps.healthconnect.HcStateStore
import com.callbackdev.tsteps.healthconnect.HealthConnectSync
import com.callbackdev.tsteps.recording.GmsStepRecordingGateway
import com.callbackdev.tsteps.recording.RecordingStateStore
import com.callbackdev.tsteps.recording.StepImporter
import com.callbackdev.tsteps.recording.StepRecordingGateway

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

    // Typed to the interface so a test can hand the import pass a fake recorder;
    // the real one is the only thing in the app that touches Play services.
    @Volatile
    private var stepRecordingGateway: StepRecordingGateway? = null

    /** Outlives the activity that starts an import (see [importSteps]). */
    private val importScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var recordingStateStore: RecordingStateStore? = null

    @Volatile
    private var stepImporter: StepImporter? = null

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
                settingsStore = settingsStore(context),
                // Read per ingest rather than captured: the import moves this
                // watermark forward from a worker while the screen is streaming
                // readings, and a stale copy would let the counter add to an hour
                // the import had just taken over.
                importedUntilMillis = { recordingStateStore(context).read().importedUntilMillis }
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
     * Play services' local step recorder (Fase 24). A singleton because the
     * client it wraps is one per process, and cheap to hold: building it opens
     * nothing — [StepRecordingGateway.availability] is a version check, and the
     * client itself is created lazily behind it.
     */
    fun stepRecordingGateway(context: Context): StepRecordingGateway =
        stepRecordingGateway ?: synchronized(this) {
            stepRecordingGateway ?: GmsStepRecordingGateway(context.applicationContext)
                .also { stepRecordingGateway = it }
        }

    fun recordingStateStore(context: Context): RecordingStateStore =
        recordingStateStore ?: synchronized(this) {
            recordingStateStore ?: RecordingStateStore.create(context)
                .also { recordingStateStore = it }
        }

    /**
     * Fire-and-forget import for callers with nothing to await — the activity
     * coming to the front. Its own scope, like the widget updater's: an import
     * interrupted between the buckets and the watermark would leave the two
     * disagreeing, and a swipe away must not be able to do that.
     */
    fun importSteps(context: Context) {
        val appContext = context.applicationContext
        importScope.launch { runCatching { stepImporter(appContext).run() } }
    }

    /** Singleton: its mutex serializes the overlapping passes, like the HC sync. */
    fun stepImporter(context: Context): StepImporter =
        stepImporter ?: synchronized(this) {
            stepImporter ?: StepImporter(
                gateway = stepRecordingGateway(context),
                store = recordingStateStore(context),
                hourlyDao = database(context).hourlyStepsDao()
            ).also { stepImporter = it }
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
        firstRunStore: FirstRunStore? = null,
        stepRecordingGateway: StepRecordingGateway? = null,
        recordingStateStore: RecordingStateStore? = null
    ) {
        this.stepRecordingGateway = stepRecordingGateway
        this.recordingStateStore = recordingStateStore
        this.stepImporter = null
        this.firstRunStore = firstRunStore
        this.stepRepository = stepRepository
        this.stepSensorReader = stepSensorReader
        this.settingsStore = settingsStore
        this.trackerStateStore = trackerStateStore
        this.database = null
    }
}
