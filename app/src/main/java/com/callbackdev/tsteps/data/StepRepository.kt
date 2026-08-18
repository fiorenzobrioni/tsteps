package com.callbackdev.tsteps.data

import com.callbackdev.tsteps.data.local.DaySummaryDao
import com.callbackdev.tsteps.data.local.DaySummaryEntity
import com.callbackdev.tsteps.data.local.HourlyStepsDao
import com.callbackdev.tsteps.data.local.HourlyStepsEntity
import com.callbackdev.tsteps.data.local.SessionDao
import com.callbackdev.tsteps.data.local.SessionEntity
import com.callbackdev.tsteps.domain.Estimates
import com.callbackdev.tsteps.domain.GoalCheck
import com.callbackdev.tsteps.domain.GoalCheckResult
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
        }
    }

    /** Commits every day strictly before [today] that has data and no commit yet. */
    suspend fun commitDaysBefore(today: LocalDate) {
        val settings = settingsStore.read()
        hourlyDao.datesBefore(today.toString()).forEach { date ->
            if (dayDao.byDate(date) != null) return@forEach
            val hours = hourlyDao.day(date)
            val steps = hours.sumOf { it.steps }
            if (steps <= 0L) return@forEach
            val activeMinutes = Estimates.activeMinutes(hours.map { it.steps })
            val check = GoalCheck.run(steps, settings.dailyGoalSteps)
            dayDao.insertIfAbsent(
                DaySummaryEntity(
                    date = date,
                    steps = steps,
                    activeMinutes = activeMinutes,
                    // Frozen with today's profile: history must not follow the scale.
                    distanceMeters = Estimates.distanceMeters(steps, settings.heightCm),
                    activeKcal = Estimates.activeKcal(settings.weightKg, activeMinutes),
                    goalSteps = settings.dailyGoalSteps,
                    goalMet = when (check) {
                        GoalCheckResult.SKIPPED -> null
                        GoalCheckResult.PASSED -> true
                        GoalCheckResult.FAILED -> false
                    }
                )
            )
        }
    }

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

    suspend fun insertSession(session: SessionEntity): Long = sessionDao.insert(session)
}
