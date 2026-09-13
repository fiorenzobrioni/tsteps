package com.callbackdev.tsteps.recording

/**
 * The seam between tsteps and Google Play services' Recording API. Everything
 * above this interface works with the plain DTOs below and runs on the JVM with
 * a fake; only [GmsStepRecordingGateway] touches `com.google.android.gms`.
 *
 * Why this exists at all (Fase 24, and the one principle of §7 it revises): the
 * step counter is an *on-change* sensor, so a reading only lands while tsteps is
 * in the foreground, and the counter is zeroed at every boot. A phone switched
 * off each night therefore loses every step walked since the last reading of
 * that day — no API can go back for them, because nothing on the device kept
 * them. The Recording API is the one exception: Play services is a system
 * component, it is not subject to the background sensor limits, and it records
 * step deltas with their timestamps, on device, offline, without an account,
 * across reboots. tsteps reads that store instead of counting those hours
 * itself. It never becomes a second counter next to the sensor: each hour has
 * exactly one source, or the day would be counted twice.
 */
interface StepRecordingGateway {

    /** Cheap, synchronous, no IPC — safe to ask on every pass. */
    fun availability(): RecordingAvailability

    /**
     * Starts (or renews) the step subscription. Idempotent: Play services keeps
     * one subscription per app. Returns false when the call failed, which is the
     * same as "not recording" for every caller.
     */
    suspend fun subscribe(): Boolean

    /** Stops recording for this app. The collected data becomes unreadable. */
    suspend fun unsubscribe(): Boolean

    /**
     * Step totals bucketed by hour over `[fromMillis, toMillis)`. Align the range
     * to a local hour boundary ([RecordingInterop.alignDownToHour]) and the
     * buckets come back aligned to the local calendar; the mapper is defensive
     * about the case where they do not.
     */
    suspend fun readHourlySteps(fromMillis: Long, toMillis: Long): List<RecordedHour>
}

enum class RecordingAvailability {
    AVAILABLE,

    /** Play services is there but older than the Recording API needs. */
    UPDATE_REQUIRED,

    /** No Play services: a de-Googled ROM. The sensor path stays the only one. */
    UNAVAILABLE
}

/** One hour of recorded steps, as Play services hands it over. */
data class RecordedHour(
    val startMillis: Long,
    val endMillis: Long,
    val steps: Long
)
