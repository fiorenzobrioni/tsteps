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
import com.callbackdev.tsteps.widget.ScreenWakeRefresh
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
 * battery spend. The widget's screen-wake refresh (Fase 25) follows the same
 * decision from here, for the same reason.
 */
object SyncScheduler {

    const val SYNC_WORK = "step-sync"
    const val ROLLOVER_WORK = "midnight-rollover"

    fun reconcile(context: Context) {
        val canSample = hasPermission(context) &&
            ServiceLocator.stepSensorReader(context).isAvailable
        val workManager = WorkManager.getInstance(context)
        // Fase 25: the widget's screen-wake refresh lives behind the same gate and
        // hears about the same changes — a widget placed or removed, a permission
        // granted or revoked. It is the only other thing armed from here, and it
        // arms nothing on its own: no widget, no receiver.
        ScreenWakeRefresh.reconcile(context, canSample)
        if (!canSample) {
            workManager.cancelUniqueWork(SYNC_WORK)
            workManager.cancelUniqueWork(ROLLOVER_WORK)
            // Fase 24e: and hand the recorder back. Cancelling the jobs only stops
            // us from reading; without this, Play services would go on recording
            // steps for an app the user has just told to stop counting.
            ServiceLocator.stopRecording(context)
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
