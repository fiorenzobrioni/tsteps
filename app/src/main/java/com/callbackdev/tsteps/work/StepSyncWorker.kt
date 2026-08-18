package com.callbackdev.tsteps.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.callbackdev.tsteps.data.ServiceLocator
import java.time.LocalDate
import java.time.ZoneId

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
        val reading = reader.readCurrent() ?: return Result.success()
        repository.ingest(reading)
        repository.commitDaysBefore(LocalDate.now(ZoneId.systemDefault()))
        return Result.success()
    }
}
