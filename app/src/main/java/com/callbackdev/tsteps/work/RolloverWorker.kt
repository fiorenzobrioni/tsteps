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
        SyncScheduler.scheduleNextRollover(applicationContext)
        TstepsWidgetUpdater.updateAll(applicationContext)
        return Result.success()
    }
}
