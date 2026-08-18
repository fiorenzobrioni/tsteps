package com.callbackdev.tsteps.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.domain.Rollover
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Single owner of background-work reconciliation (tweather's pattern): called at
 * app start and after any change that affects whether the jobs should exist.
 * Two jobs, both unconstrained (no network to wait for) and inexact (no alarms):
 *
 * - `step-sync`: periodic 15 min sampling of the counter.
 * - `midnight-rollover`: one-shot at the next local midnight, self-rescheduling.
 *
 * Without the permission or the sensor there is nothing to sample, so both jobs
 * are cancelled — a user who revokes ACTIVITY_RECOGNITION also revokes the
 * battery spend.
 */
object SyncScheduler {

    const val SYNC_WORK = "step-sync"
    const val ROLLOVER_WORK = "midnight-rollover"

    fun reconcile(context: Context) {
        val canSample = hasPermission(context) &&
            ServiceLocator.stepSensorReader(context).isAvailable
        val workManager = WorkManager.getInstance(context)
        if (!canSample) {
            workManager.cancelUniqueWork(SYNC_WORK)
            workManager.cancelUniqueWork(ROLLOVER_WORK)
            return
        }
        workManager.enqueueUniquePeriodicWork(
            SYNC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<StepSyncWorker>(15, TimeUnit.MINUTES).build()
        )
        scheduleNextRollover(context)
    }

    fun scheduleNextRollover(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        val delay = Rollover.nextMidnightMillis(nowMillis, ZoneId.systemDefault()) - nowMillis
        WorkManager.getInstance(context).enqueueUniqueWork(
            ROLLOVER_WORK,
            // REPLACE: re-arming from reconcile() must move the appointment to the
            // *current* zone's next midnight (timezone changes re-aim the job).
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RolloverWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
        )
    }

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
}
