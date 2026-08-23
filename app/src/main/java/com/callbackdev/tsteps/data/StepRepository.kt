package com.callbackdev.tsteps.data

import com.callbackdev.tsteps.data.local.DaySummaryDao
import com.callbackdev.tsteps.data.local.DaySummaryEntity
import com.callbackdev.tsteps.data.local.HourlyStepsDao
import com.callbackdev.tsteps.data.local.HourlyStepsEntity
import com.callbackdev.tsteps.data.local.SessionDao
import com.callbackdev.tsteps.data.local.SessionEntity
import com.callbackdev.tsteps.data.local.StepSampleDao
import com.callbackdev.tsteps.data.local.StepSampleEntity
import com.callbackdev.tsteps.domain.BucketShare
import com.callbackdev.tsteps.domain.Estimates
import com.callbackdev.tsteps.domain.GoalCheck
import com.callbackdev.tsteps.domain.GoalCheckResult
import com.callbackdev.tsteps.domain.SampleSpan
import com.callbackdev.tsteps.domain.SessionMetrics
import com.callbackdev.tsteps.domain.SessionResize
import com.callbackdev.tsteps.domain.StepAttribution
import com.callbackdev.tsteps.domain.StepReading
import com.callbackdev.tsteps.domain.StepTracker
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where sensor readings become rows. Two verbs:
 *
 * - [ingest]: advance the continuity anchor with a new reading and spread the
 *   delta over hourly buckets (the working tree grows).
 * - [commitDaysBefore]: turn every finished day's buckets into an immutable
 *   [DaySummaryEntity] (the commit). Called by the midnight worker, and by every
 *   sync as a safety net — if the phone slept through midnight, the first sample
 *   of the morning commits yesterday. Insert-only + idempotent, so the two
 *   callers can race freely.
 */
class StepRepository(
    private val hourlyDao: HourlyStepsDao,
    private val dayDao: DaySummaryDao,
    private val sessionDao: SessionDao,
    private val sampleDao: StepSampleDao,
    private val trackerStateStore: TrackerStateStore,
    private val settingsStore: SettingsStore,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {

    // ingest is read-modify-write on the anchor: serialize callers (foreground
    // listener and periodic worker can overlap).
    private val ingestMutex = Mutex()

    suspend fun ingest(reading: StepReading) {
        ingestMutex.withLock {
            val state = trackerStateStore.read()
            val advance = StepTracker.advance(state, reading)
            // Anchor first: if attribution crashes we lose one delta, never
            // double-count it on retry.
            trackerStateStore.write(advance.newState)
            StepAttribution.attribute(
                deltaSteps = advance.deltaSteps,
                fromMillis = advance.fromMillis,
                toMillis = advance.toMillis,
                zone = zone()
            ).forEach { share ->
                hourlyDao.increment(share.date.toString(), share.hour, share.steps)
            }
            // Sample spans exist only for the auto detector — and only while
            // its toggle is on. Off = this branch never runs, zero rows.
            if (settingsStore.read().autoDetectSessions) {
                recordSample(advance.fromMillis, advance.toMillis, advance.deltaSteps)
            }
        }
    }

    /**
     * Records one counter span for the detector. Zero-delta spans matter too
     * (they are explicit stillness, what closes a walk chain), so everything is
     * kept — but the live listener's 2s ticks coalesce into ~1-minute rows: the
     * detector needs minutes, not vibrations.
     */
    private suspend fun recordSample(fromMillis: Long, toMillis: Long, steps: Long) {
        if (toMillis <= fromMillis && steps <= 0L) return // anchor-only advance
        val latest = sampleDao.latest()
        if (latest != null && latest.toMillis == fromMillis &&
            toMillis - latest.fromMillis <= SAMPLE_COALESCE_MILLIS
        ) {
            sampleDao.upsert(latest.copy(toMillis = toMillis, steps = latest.steps + steps))
        } else {
            sampleDao.insert(StepSampleEntity(fromMillis = fromMillis, toMillis = toMillis, steps = steps))
        }
    }

    /**
     * Commits every day strictly before [today] that has data and no commit yet.
     * Returns the days committed by THIS pass (a no-op safety-net run returns
     * empty) — the daily-commit notification hangs off that distinction.
     */
    suspend fun commitDaysBefore(today: LocalDate): List<DaySummaryEntity> {
        val settings = settingsStore.read()
        return hourlyDao.datesBefore(today.toString()).mapNotNull { date ->
            if (dayDao.byDate(date) != null) return@mapNotNull null
            val hours = hourlyDao.day(date)
            val steps = hours.sumOf { it.steps }
            if (steps <= 0L) return@mapNotNull null
            val activeMinutes = Estimates.activeMinutes(hours.map { it.steps })
            val check = GoalCheck.run(steps, settings.dailyGoalSteps)
            val day = DaySummaryEntity(
                date = date,
                steps = steps,
                activeMinutes = activeMinutes,
                // Frozen with today's profile: history must not follow the scale.
                distanceMeters = settings.distanceMeters(steps),
                activeKcal = Estimates.activeKcal(settings.weightKg, activeMinutes),
                goalSteps = settings.dailyGoalSteps,
                goalMet = when (check) {
                    GoalCheckResult.SKIPPED -> null
                    GoalCheckResult.PASSED -> true
                    GoalCheckResult.FAILED -> false
                }
            )
            day.takeIf { dayDao.insertIfAbsent(it) != -1L }
        }
    }

    /** Today's live total, for the goal watcher. */
    suspend fun stepsOfDay(date: LocalDate): Long =
        hourlyDao.day(date.toString()).sumOf { it.steps }

    fun observeDay(date: LocalDate): Flow<List<HourlyStepsEntity>> =
        hourlyDao.observeDay(date.toString())

    fun observeHistory(): Flow<List<DaySummaryEntity>> = dayDao.observeAll()

    /** Completed sessions whose start falls on [date] (local calendar). */
    fun observeSessionsOfDay(date: LocalDate): Flow<List<SessionEntity>> {
        val from = date.atStartOfDay(zone()).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()
        return sessionDao.observeBetween(from, to)
    }

    fun observeAllSessions(): Flow<List<SessionEntity>> = sessionDao.observeAll()

    /** The `longest-walk` record alone — see [SessionDao.observeLongest]. */
    fun observeLongestSession(): Flow<SessionEntity?> = sessionDao.observeLongest()

    suspend fun insertSession(session: SessionEntity): Long = sessionDao.insert(session)

    /**
     * `[rm]` on a session — a soft delete: the row survives as a tombstone so
     * the auto detector never resurrects what the user removed.
     */
    suspend fun dismissSession(id: Long, nowMillis: Long) = sessionDao.dismiss(id, nowMillis)

    /**
     * Boundary edit, auto sessions only (a manual session is a precise record:
     * it can be removed, never rewritten — the file must not lie). Steps are
     * recomputed for the new range from the recorded samples (hourly buckets as
     * the coarse fallback); distance keeps the session's own frozen stride, and
     * duration-derived metrics follow. Returns the updated row, or null when
     * the id is unknown or not an auto session.
     */
    suspend fun resizeSession(id: Long, startMillis: Long, endMillis: Long): SessionEntity? {
        val session = sessionDao.byId(id) ?: return null
        if (!session.auto || endMillis <= startMillis) return null
        val zoneId = zone()
        val samples = sampleDao.since(startMillis).map {
            SampleSpan(it.fromMillis, it.toMillis, it.steps)
        }
        val hourly = datesCovered(startMillis, endMillis, zoneId).flatMap { date ->
            hourlyDao.day(date.toString()).map { BucketShare(date, it.hour, it.steps) }
        }
        val steps = SessionResize.steps(samples, hourly, startMillis, endMillis, zoneId)
        val strideMeters = session.distanceMeters
            ?.takeIf { session.steps > 0 }?.div(session.steps)
            ?: settingsStore.read().strideMeters()
        val activeMillis = endMillis - startMillis
        sessionDao.updateBounds(
            id = id,
            startMillis = startMillis,
            endMillis = endMillis,
            steps = steps,
            distanceMeters = steps * strideMeters,
            activeMillis = activeMillis,
            avgCadenceSpm = SessionMetrics.avgCadenceSpm(steps, activeMillis)
        )
        return sessionDao.byId(id)
    }

    private fun datesCovered(fromMillis: Long, toMillis: Long, zoneId: ZoneId): List<LocalDate> {
        val first = java.time.Instant.ofEpochMilli(fromMillis).atZone(zoneId).toLocalDate()
        val last = java.time.Instant.ofEpochMilli(toMillis).atZone(zoneId).toLocalDate()
        return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
    }

    companion object {
        /** Live-listener ticks merge into one sample row up to this span. */
        const val SAMPLE_COALESCE_MILLIS = 60_000L
    }
}
