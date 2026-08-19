package com.callbackdev.tsteps.healthconnect

/**
 * The seam between tsteps and the Health Connect client library. Everything
 * above this interface works with the plain DTOs below and can run on the JVM
 * with a fake; only [AndroidHealthConnectGateway] touches androidx.health —
 * which is a Jetpack library speaking local IPC to the on-device HC module:
 * no Google Play Services dependency, no network, ever.
 */
interface HealthConnectGateway {

    /** Our own package name — the origin to exclude when reading. */
    val ownPackageName: String

    fun availability(): HcAvailability

    suspend fun grantedPermissions(): Set<HcPermission>

    /**
     * Upserts hourly step intervals. Idempotent by client id: the version is
     * the step count itself (buckets only ever grow), so re-sending the same
     * hour is a no-op and a grown hour replaces its record.
     */
    suspend fun upsertSteps(hours: List<HcHourSteps>)

    /** Upserts walk/other sessions (client id per Room row). */
    suspend fun upsertSessions(sessions: List<HcSessionRecord>)

    /** Removes sessions the user `[rm]`d — by client id, no-op when absent. */
    suspend fun deleteSessions(clientIds: List<String>)

    /** Every steps record in the range, all origins, ours included. */
    suspend fun readSteps(fromMillis: Long, toMillis: Long): List<HcExternalSteps>
}

enum class HcAvailability { AVAILABLE, UPDATE_REQUIRED, UNAVAILABLE }

enum class HcPermission { READ_STEPS, WRITE_STEPS, WRITE_EXERCISE }

/** One hourly bucket as an interval record. */
data class HcHourSteps(
    val clientId: String,
    val startMillis: Long,
    val endMillis: Long,
    val steps: Long
)

/** One completed session as an exercise record. */
data class HcSessionRecord(
    val clientId: String,
    /** Monotonic per write pass so boundary edits overwrite older shapes. */
    val version: Long,
    val startMillis: Long,
    val endMillis: Long,
    val walking: Boolean,
    val title: String
)

/** One steps record as read back: who counted, and how many. */
data class HcExternalSteps(
    val originPackage: String,
    val steps: Long
)
