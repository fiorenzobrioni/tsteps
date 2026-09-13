package com.callbackdev.tsteps.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.fitness.FitnessLocal
import com.google.android.gms.fitness.LocalRecordingClient
import com.google.android.gms.fitness.data.LocalDataType
import com.google.android.gms.fitness.data.LocalField
import com.google.android.gms.fitness.request.LocalDataReadRequest
import com.google.android.gms.tasks.Task
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The only class that touches `com.google.android.gms`. The client is created
 * lazily and behind an [availability] == AVAILABLE check, so a device without
 * Play services — or with one too old — never instantiates anything.
 *
 * `LocalRecordingClient` is the on-device half of the Fitness API: it keeps no
 * account, opens no socket, and stores what it records in Play services' own
 * local database. tsteps still declares no INTERNET permission, and nothing in
 * this file can send a step anywhere.
 */
class GmsStepRecordingGateway(context: Context) : StepRecordingGateway {

    private val appContext = context.applicationContext

    private val client: LocalRecordingClient by lazy {
        FitnessLocal.getLocalRecordingClient(appContext)
    }

    override fun availability(): RecordingAvailability =
        when (
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(
                appContext,
                LocalRecordingClient.LOCAL_RECORDING_CLIENT_MIN_VERSION_CODE
            )
        ) {
            ConnectionResult.SUCCESS -> RecordingAvailability.AVAILABLE
            ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED,
            ConnectionResult.SERVICE_UPDATING -> RecordingAvailability.UPDATE_REQUIRED
            else -> RecordingAvailability.UNAVAILABLE
        }

    /**
     * Subscribing is what makes Play services record for us, and it is the one
     * call here that needs `ACTIVITY_RECOGNITION` — the same permission the
     * sensor path asks for, so there is nothing new to request. Checked rather
     * than assumed: the user can revoke it from system settings at any moment,
     * and a revoked permission must read as "not recording", never as a crash.
     */
    override suspend fun subscribe(): Boolean {
        val granted = appContext.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return false
        return try {
            client.subscribe(LocalDataType.TYPE_STEP_COUNT_DELTA).settle().isSuccessful
        } catch (denied: SecurityException) {
            false
        }
    }

    override suspend fun unsubscribe(): Boolean =
        client.unsubscribe(LocalDataType.TYPE_STEP_COUNT_DELTA).settle().isSuccessful

    /**
     * Throws whatever Play services threw, deliberately: the caller advances an
     * import watermark on the strength of this answer, and an empty list on a
     * failed read would move it past hours nobody ever looked at. Silence and
     * "no steps" must not be the same value here.
     */
    override suspend fun readHourlySteps(fromMillis: Long, toMillis: Long): List<RecordedHour> {
        if (toMillis <= fromMillis) return emptyList()
        val request = LocalDataReadRequest.Builder()
            .aggregate(LocalDataType.TYPE_STEP_COUNT_DELTA)
            .bucketByTime(1, TimeUnit.HOURS)
            .setTimeRange(fromMillis, toMillis, TimeUnit.MILLISECONDS)
            .build()
        val task = client.readData(request).settle()
        task.exception?.let { throw it }
        val response = task.result ?: return emptyList()
        return response.buckets.mapNotNull { bucket ->
            // Summed over every set in the bucket rather than looked up by data
            // type: steps are the only thing this request asks for, so every set
            // here is a step set, and keying by type would quietly read zero if
            // the aggregate came back under a name of its own.
            val steps = bucket.dataSets.sumOf { set ->
                set.dataPoints.sumOf { point ->
                    point.getValue(LocalField.FIELD_STEPS).asInt().toLong()
                }
            }
            if (steps <= 0L) {
                null
            } else {
                RecordedHour(
                    startMillis = bucket.getStartTime(TimeUnit.MILLISECONDS),
                    endMillis = bucket.getEndTime(TimeUnit.MILLISECONDS),
                    steps = steps
                )
            }
        }
    }

    /**
     * Awaits the task and hands it back whole — not its value. A `Task<Void>`
     * carries a null result that a generic `await` would have to cast blind, and
     * the failure belongs to the caller anyway: [subscribe] reads it as false,
     * [readHourlySteps] rethrows it.
     */
    private suspend fun <T> Task<T>.settle(): Task<T> =
        suspendCancellableCoroutine { continuation ->
            addOnCompleteListener { continuation.resumeWith(Result.success(it)) }
        }
}
