package com.callbackdev.tsteps.healthconnect

import com.callbackdev.tsteps.data.SettingsStore
import com.callbackdev.tsteps.data.local.HourlyStepsDao
import com.callbackdev.tsteps.data.local.SessionDao
import com.callbackdev.tsteps.data.toItem
import com.callbackdev.tsteps.domain.BucketShare
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fase 12 orchestration: one stateless reconcile pass, ridden by moments the
 * app is already awake (the 15-min sync, the midnight rollover, a session
 * closing, an `[rm]`). Each pass rewrites today's and yesterday's truth into
 * Health Connect idempotently — client ids + versions make repeats no-ops — so
 * there is nothing to track, nothing to catch up, nothing to get wrong after a
 * crash.
 *
 * The gates are the whole battery story: with `health_connect.sync` off
 * (the default) this returns after one in-memory settings read — no client, no
 * IPC, no Health Connect process, nothing. Reads and writes each require their
 * own HC permission; whatever the user didn't grant simply doesn't happen.
 *
 * Reading never merges: external steps land in [HcStateStore] grouped per
 * origin for display only. tsteps' own counters remain the phone sensor's
 * truth (VISION §7 — dedup by source, never double count).
 */
class HealthConnectSync(
    private val gateway: HealthConnectGateway,
    private val settingsStore: SettingsStore,
    private val hourlyDao: HourlyStepsDao,
    private val sessionDao: SessionDao,
    private val hcStateStore: HcStateStore,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {

    // Callers overlap (worker, tracking stop, [rm] from the UI): serialize the
    // passes; each is cheap and idempotent, so waiting is always correct.
    private val mutex = Mutex()

    suspend fun sync(nowMillis: Long = System.currentTimeMillis()) {
        mutex.withLock {
            val settings = settingsStore.read()
            if (!settings.healthConnect.sync) {
                // Off means off: the cached external read disappears too.
                // (A no-op edit once cleared — DataStore skips equal writes.)
                hcStateStore.clear()
                return
            }
            if (gateway.availability() != HcAvailability.AVAILABLE) return
            val granted = gateway.grantedPermissions()
            if (granted.isEmpty()) return

            val zoneId = zone()
            val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
            val yesterday = today.minusDays(1)
            val yesterdayStartMillis =
                yesterday.atStartOfDay(zoneId).toInstant().toEpochMilli()

            if (HcPermission.WRITE_STEPS in granted) {
                val buckets = (hourlyDao.day(yesterday.toString()) + hourlyDao.day(today.toString()))
                    .map { BucketShare(LocalDate.parse(it.date), it.hour, it.steps) }
                gateway.upsertSteps(HealthInterop.hourSteps(buckets, zoneId))
            }

            if (HcPermission.WRITE_EXERCISE in granted) {
                val sessions =
                    sessionDao.overlappingIncludingDismissed(yesterdayStartMillis, nowMillis)
                val (dismissed, alive) = sessions.partition { it.dismissedMillis != null }
                gateway.upsertSessions(
                    HealthInterop.sessions(alive.mapNotNull { it.toItem() }, nowMillis)
                )
                gateway.deleteSessions(
                    dismissed.map { HealthInterop.sessionClientId(it.id) }
                )
            }

            if (HcPermission.READ_STEPS in granted) {
                val todayStartMillis = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
                val records = gateway.readSteps(todayStartMillis, nowMillis)
                hcStateStore.write(
                    ExternalStepsState(
                        date = today,
                        origins = HealthInterop.externalByOrigin(records, gateway.ownPackageName),
                        readAtMillis = nowMillis
                    )
                )
            }
        }
    }
}
