package com.callbackdev.tsteps.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.notifications.GoalWatcher
import com.callbackdev.tsteps.notifications.StepsNotifications
import com.callbackdev.tsteps.notifications.StepsNotifier
import com.callbackdev.tsteps.widget.TstepsWidgetUpdater
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * The periodic sampler (`step-sync`, 15 min — WorkManager's floor). Reads one
 * counter value, ingests the delta, and commits any finished day as a safety net
 * for the midnight worker. All logic lives in the repository; the worker is glue,
 * mirroring tweather's worker-as-glue pattern.
 */
class StepSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val reader = ServiceLocator.stepSensorReader(applicationContext)
        val repository = ServiceLocator.stepRepository(applicationContext)
        val reading = reader.readCurrent()
        if (reading == null) {
            // Redraw even when the sample fails: the stale marker exists exactly
            // for this scenario, and without a repaint it would never appear
            // (tweather's lesson from the network-down case).
            TstepsWidgetUpdater.updateAll(applicationContext)
            return Result.success()
        }
        repository.ingest(reading)
        val committed = repository.commitDaysBefore(LocalDate.now(ZoneId.systemDefault()))
        notifyDailyCommit(committed)
        GoalWatcher.evaluate(applicationContext)
        // Fase 11: infer walks from the samples this very worker collects — no
        // extra sensing, just arithmetic on data already paid for. Before the
        // widget repaint, so a fresh walk shows up in `# last walk` right away.
        ServiceLocator.autoSessionDetector(applicationContext).run()
        // Fase 12: reconcile Health Connect after detection so a fresh auto walk
        // ships in the same pass. Inert (one in-memory read) while sync is off.
        ServiceLocator.healthConnectSync(applicationContext).sync()
        TstepsWidgetUpdater.updateAll(applicationContext)
        return Result.success()
    }

    /**
     * The safety net commits too, so it notifies too (PLANNING: "al rollover di
     * mezzanotte o alla prima apertura successiva"). Only the newest day of a
     * multi-day backlog: a phone off for a week deserves one summary, not seven.
     */
    private suspend fun notifyDailyCommit(
        committed: List<com.callbackdev.tsteps.data.local.DaySummaryEntity>
    ) {
        val newest = committed.maxByOrNull { it.date } ?: return
        val settings = ServiceLocator.settingsStore(applicationContext).read()
        if (!settings.notifications.dailyCommit) return
        StepsNotifier.postDailyCommit(
            applicationContext,
            StepsNotifications.dailyCommit(
                newest, settings.units, Locale.getDefault(), applicationContext.resources
            )
        )
    }
}
