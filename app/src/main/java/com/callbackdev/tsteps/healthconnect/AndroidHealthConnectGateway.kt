package com.callbackdev.tsteps.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The only class that touches androidx.health.connect. The client is created
 * lazily and exclusively behind an [availability] == AVAILABLE check by the
 * sync engine — a device without Health Connect never instantiates anything.
 */
class AndroidHealthConnectGateway(private val context: Context) : HealthConnectGateway {

    override val ownPackageName: String get() = context.packageName

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    override fun availability(): HcAvailability =
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HcAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HcAvailability.UPDATE_REQUIRED
            else -> HcAvailability.UNAVAILABLE
        }

    override suspend fun grantedPermissions(): Set<HcPermission> {
        val granted = client.permissionController.getGrantedPermissions()
        return buildSet {
            if (HealthPermission.getReadPermission(StepsRecord::class) in granted) {
                add(HcPermission.READ_STEPS)
            }
            if (HealthPermission.getWritePermission(StepsRecord::class) in granted) {
                add(HcPermission.WRITE_STEPS)
            }
            if (HealthPermission.getWritePermission(ExerciseSessionRecord::class) in granted) {
                add(HcPermission.WRITE_EXERCISE)
            }
        }
    }

    override suspend fun upsertSteps(hours: List<HcHourSteps>) {
        if (hours.isEmpty()) return
        client.insertRecords(
            hours.map { hour ->
                StepsRecord(
                    startTime = Instant.ofEpochMilli(hour.startMillis),
                    startZoneOffset = offsetAt(hour.startMillis),
                    endTime = Instant.ofEpochMilli(hour.endMillis),
                    endZoneOffset = offsetAt(hour.endMillis),
                    count = hour.steps,
                    metadata = metadata(hour.clientId, version = hour.steps)
                )
            }
        )
    }

    override suspend fun upsertSessions(sessions: List<HcSessionRecord>) {
        if (sessions.isEmpty()) return
        client.insertRecords(
            sessions.map { session ->
                ExerciseSessionRecord(
                    startTime = Instant.ofEpochMilli(session.startMillis),
                    startZoneOffset = offsetAt(session.startMillis),
                    endTime = Instant.ofEpochMilli(session.endMillis),
                    endZoneOffset = offsetAt(session.endMillis),
                    exerciseType = if (session.walking) {
                        ExerciseSessionRecord.EXERCISE_TYPE_WALKING
                    } else {
                        ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
                    },
                    title = session.title,
                    metadata = metadata(session.clientId, session.version)
                )
            }
        )
    }

    override suspend fun deleteSessions(clientIds: List<String>) {
        if (clientIds.isEmpty()) return
        client.deleteRecords(
            recordType = ExerciseSessionRecord::class,
            recordIdsList = emptyList(),
            clientRecordIdsList = clientIds
        )
    }

    override suspend fun readSteps(fromMillis: Long, toMillis: Long): List<HcExternalSteps> {
        val out = mutableListOf<HcExternalSteps>()
        var pageToken: String? = null
        var pages = 0
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(
                        Instant.ofEpochMilli(fromMillis),
                        Instant.ofEpochMilli(toMillis)
                    ),
                    pageToken = pageToken
                )
            )
            out += response.records.map {
                HcExternalSteps(it.metadata.dataOrigin.packageName, it.count)
            }
            pageToken = response.pageToken
            pages++
        } while (pageToken != null && pages < MAX_PAGES)
        return out
    }

    /** Recorded by the phone itself, upsert-keyed by our client id. */
    private fun metadata(clientId: String, version: Long): Metadata =
        Metadata.autoRecorded(
            device = Device(type = Device.TYPE_PHONE),
            clientRecordId = clientId,
            clientRecordVersion = version
        )

    private fun offsetAt(millis: Long): ZoneOffset =
        ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(millis))

    companion object {
        /** A day of external records is small; this is a runaway backstop. */
        private const val MAX_PAGES = 5
    }
}
