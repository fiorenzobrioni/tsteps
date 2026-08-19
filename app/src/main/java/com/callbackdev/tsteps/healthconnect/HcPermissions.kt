package com.callbackdev.tsteps.healthconnect

import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord

/**
 * What the `health_connect` settings section needs to render and act: the
 * device's availability plus which of our three permissions are granted.
 * Refreshed on resume like the notification permission — grants and revokes
 * happen on Health Connect's own screens while tsteps is paused.
 */
data class HcSectionStatus(
    val availability: HcAvailability = HcAvailability.UNAVAILABLE,
    val writeSteps: Boolean = false,
    val writeSessions: Boolean = false,
    val readSteps: Boolean = false
) {
    val anyGranted: Boolean get() = writeSteps || writeSessions || readSteps
}

/** The permission surface of Fase 12 — exactly the three, never more. */
object HcPermissions {

    val ALL: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getWritePermission(ExerciseSessionRecord::class)
    )

    /** The system contract that opens Health Connect's own grant screen. */
    fun requestContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    /** One IPC when available, none otherwise. */
    suspend fun sectionStatus(gateway: HealthConnectGateway): HcSectionStatus {
        val availability = gateway.availability()
        if (availability != HcAvailability.AVAILABLE) {
            return HcSectionStatus(availability = availability)
        }
        val granted = gateway.grantedPermissions()
        return HcSectionStatus(
            availability = availability,
            writeSteps = HcPermission.WRITE_STEPS in granted,
            writeSessions = HcPermission.WRITE_EXERCISE in granted,
            readSteps = HcPermission.READ_STEPS in granted
        )
    }
}
