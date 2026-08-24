package com.callbackdev.tsteps.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.notifications.StepsNotifications
import com.callbackdev.tsteps.notifications.StepsNotifier
import com.callbackdev.tsteps.widget.TstepsWidgetUpdater
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * The midnight commit (`midnight-rollover`): samples the counter one last time so
 * the closing day gets its final steps, then commits it and re-schedules itself
 * for the next local midnight. If the device sleeps through the appointment,
 * [StepSyncWorker]'s safety net commits the day at the first morning sample —
 * this worker's job is punctuality (the Fase 9 `daily_commit` notification fires
 * from here), not exclusivity.
 */
class RolloverWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        try {
            commitPass()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            // Swallowed on purpose: the two things that must still happen below
            // are exactly the ones a half-failed pass would otherwise take with
            // it. The sampler's safety net commits the day at the next pass.
        }
        TstepsWidgetUpdater.updateAllSafely(applicationContext)
        // Punctuality is this worker's whole job, so the next appointment is made
        // even when the pass that just ran fell over — otherwise the self-
        // rescheduling chain ends here and midnight stops committing until
        // something calls `reconcile`. Last, because REPLACE re-enqueues the very
        // spec that is running: nothing that must finish belongs after it.
        SyncScheduler.scheduleNextRollover(applicationContext)
        // Always success: the chain above is re-armed regardless, and this very
        // work spec has just been replaced by it — a retry would land nowhere.
        return Result.success()
    }

    private suspend fun commitPass() {
        val repository = ServiceLocator.stepRepository(applicationContext)
        ServiceLocator.stepSensorReader(applicationContext).readCurrent()
            ?.let { repository.ingest(it) }
        val committed = repository.commitDaysBefore(LocalDate.now(ZoneId.systemDefault()))
        committed.maxByOrNull { it.date }?.let { newest ->
            val settings = ServiceLocator.settingsStore(applicationContext).read()
            if (settings.notifications.dailyCommit) {
                StepsNotifier.postDailyCommit(
                    applicationContext,
                    StepsNotifications.dailyCommit(
                        newest, settings.units, Locale.getDefault(), applicationContext.resources
                    )
                )
            }
        }
        // Fase 12: the closing day's final hours reach Health Connect with the
        // commit. Inert while sync is off.
        ServiceLocator.healthConnectSync(applicationContext).sync()
    }
}
