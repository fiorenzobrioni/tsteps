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
import kotlinx.coroutines.CancellationException

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
        val failure = try {
            pass()
            null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            e
        }
        // Redraw whatever happened: the stale marker exists exactly for a failed
        // pass, and without a repaint it would never appear (tweather's lesson
        // from the network-down case). The tail can throw for real — Health
        // Connect's IPC surfaces a revoked permission as an exception — and it
        // used to take the repaint down with it.
        TstepsWidgetUpdater.updateAllSafely(applicationContext)
        // Never `failure()`: for periodic work that state is terminal in
        // WorkManager, so one bad pass would stop the 15-minute sampler until the
        // next `reconcile` re-armed it. Retry keeps the schedule alive.
        return if (failure == null) Result.success() else Result.retry()
    }

    private suspend fun pass() {
        val reader = ServiceLocator.stepSensorReader(applicationContext)
        val repository = ServiceLocator.stepRepository(applicationContext)
        val reading = reader.readCurrent() ?: return
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
