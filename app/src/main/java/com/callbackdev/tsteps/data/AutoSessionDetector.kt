package com.callbackdev.tsteps.data

import com.callbackdev.tsteps.data.local.SessionDao
import com.callbackdev.tsteps.data.local.SessionEntity
import com.callbackdev.tsteps.data.local.StepSampleDao
import com.callbackdev.tsteps.domain.AutoDetect
import com.callbackdev.tsteps.domain.Estimates
import com.callbackdev.tsteps.domain.SampleSpan
import com.callbackdev.tsteps.domain.SessionMetrics
import java.time.Instant
import java.time.ZoneId

/**
 * Fase 11 orchestration: turns [AutoDetect]'s pure inference into `(auto)`
 * session rows. Runs from the periodic sync worker — it rides samples that were
 * collected anyway, so an enabled detector costs arithmetic, not battery, and a
 * disabled one costs nothing at all (and wipes any leftover samples, so off
 * means off).
 *
 * Idempotent by construction: every existing session — manual, auto, dismissed
 * tombstones, and the live tracking window — is an exclusion for the detector,
 * so re-running over the same samples can never insert the same walk twice nor
 * resurrect one the user `[rm]`d. Detection is windowed to today: committed
 * history never grows new hunks (a walk finishing inside the last minutes of
 * the day may be lost to this rule — a missed walk is cheaper than a mutable
 * history).
 */
class AutoSessionDetector(
    private val sessionDao: SessionDao,
    private val sampleDao: StepSampleDao,
    private val settingsStore: SettingsStore,
    /** Start of the live manual session, if one is running (its window is taken). */
    private val trackingStartMillis: () -> Long? = { null },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val tuning: AutoDetect.Tuning = AutoDetect.DEFAULT
) {

    /** Returns the sessions created by this pass (empty on a quiet or disabled run). */
    suspend fun run(nowMillis: Long = System.currentTimeMillis()): List<SessionEntity> {
        val settings = settingsStore.read()
        if (!settings.autoDetectSessions) {
            sampleDao.clear()
            return emptyList()
        }

        val zoneId = zone()
        val dayStartMillis = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
            .toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli()

        // Maintenance: samples beyond retention are dead weight; tombstones get
        // one extra day past their window — Health Connect's reconcile (Fase 12)
        // deletes by tombstone, and an [rm] minutes before midnight must still
        // be visible to the first syncs of the next day.
        sampleDao.pruneBefore(nowMillis - RETENTION_MILLIS)
        sessionDao.pruneDismissedBefore(dayStartMillis - TOMBSTONE_KEEP_MILLIS)

        val samples = sampleDao.since(dayStartMillis).map {
            SampleSpan(it.fromMillis, it.toMillis, it.steps)
        }
        val exclusions = buildList {
            sessionDao.overlappingIncludingDismissed(dayStartMillis, nowMillis)
                .forEach { session ->
                    val end = session.endMillis ?: nowMillis
                    add(
                        AutoDetect.Exclusion(
                            startMillis = minOf(
                                session.startMillis,
                                session.detectedStartMillis ?: session.startMillis
                            ),
                            endMillis = maxOf(end, session.detectedEndMillis ?: end)
                        )
                    )
                }
            trackingStartMillis()?.let { add(AutoDetect.Exclusion(it, nowMillis)) }
        }

        val strideMeters = Estimates.strideMeters(settings.heightCm)
        return AutoDetect.detect(samples, dayStartMillis, nowMillis, exclusions, tuning)
            .map { walk ->
                // An inferred walk has no pause knowledge: active time is the
                // whole span, and every derived metric says so honestly.
                val activeMillis = walk.endMillis - walk.startMillis
                val entity = SessionEntity(
                    startMillis = walk.startMillis,
                    endMillis = walk.endMillis,
                    type = "walk",
                    steps = walk.steps,
                    distanceMeters = walk.steps * strideMeters,
                    avgCadenceSpm = SessionMetrics.avgCadenceSpm(walk.steps, activeMillis),
                    auto = true,
                    activeMillis = activeMillis,
                    detectedStartMillis = walk.startMillis,
                    detectedEndMillis = walk.endMillis
                )
                entity.copy(id = sessionDao.insert(entity))
            }
    }

    companion object {
        /** Samples older than this cannot influence today's detection: pruned. */
        const val RETENTION_MILLIS = 48 * 3_600_000L

        /** Tombstones outlive their day by this much, for the HC delete pass. */
        const val TOMBSTONE_KEEP_MILLIS = 24 * 3_600_000L
    }
}
