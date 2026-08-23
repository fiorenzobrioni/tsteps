package com.callbackdev.tsteps.export

import com.callbackdev.tsteps.data.SettingsStore
import com.callbackdev.tsteps.data.distanceMeters
import com.callbackdev.tsteps.data.local.DaySummaryDao
import com.callbackdev.tsteps.data.local.HourlyStepsDao
import com.callbackdev.tsteps.data.local.SessionDao
import com.callbackdev.tsteps.data.toItem
import com.callbackdev.tsteps.domain.CommitHash
import com.callbackdev.tsteps.domain.Estimates
import com.callbackdev.tsteps.domain.GoalCheck
import com.callbackdev.tsteps.domain.GoalCheckResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

/** What one `$ tsteps export` did, as the terminal will report it. */
sealed interface ExportResult {

    /** [files] are the names the store actually wrote. */
    data class Written(
        val files: List<String>,
        val days: Int,
        val sessions: Int
    ) : ExportResult

    /** Nothing recorded yet — an empty file would be a worse answer than saying so. */
    data object Empty : ExportResult

    data class Failed(val message: String) : ExportResult
}

/**
 * Fase 13: the whole history handed back to the person who walked it. Reads
 * Room, renders through [ExportDocuments], writes through an [ExportSink] —
 * one pass, no state, nothing scheduled: an export happens because a command
 * was tapped.
 *
 * What goes in is every day that has steps and every completed session:
 *
 * - **Committed days keep their frozen numbers** (distance and kcal as the
 *   profile of that day computed them); the working tree — today, plus any
 *   straggler the midnight safety net hasn't picked up — is computed live and
 *   flagged `committed: false`. Leaving it out would be an archive missing
 *   today; passing it off as history would be a lie about what a commit is.
 * - **Tombstoned sessions stay out.** `[rm]` removed them from every screen;
 *   they survive only as the detector's memory, and that is machinery, not
 *   data. A running session is out too: it has no end yet.
 * - **The hourly buckets stay out.** They are partly inferred (a batched
 *   counter delta is spread proportionally across the hours it spans), so
 *   exporting them as facts would ship an estimate wearing a raw-data face.
 *   Day totals, sessions and their metrics are the record.
 */
class DataExporter(
    private val hourlyDao: HourlyStepsDao,
    private val dayDao: DaySummaryDao,
    private val sessionDao: SessionDao,
    private val settingsStore: SettingsStore,
    private val sink: ExportSink,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {

    suspend fun export(
        format: ExportFormat,
        nowMillis: Long = System.currentTimeMillis()
    ): ExportResult = try {
        val bundle = bundle(nowMillis)
        if (bundle.isEmpty) {
            ExportResult.Empty
        } else {
            val written = ExportDocuments.files(bundle, format).map { sink.write(it) }
            ExportResult.Written(written, bundle.days.size, bundle.sessions.size)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        // The storage layer's own words read like a compiler message already.
        ExportResult.Failed(error.message ?: error.javaClass.simpleName)
    }

    private suspend fun bundle(nowMillis: Long): ExportBundle {
        val zoneId = zone()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val settings = settingsStore.read()
        val commits = dayDao.all().associateBy { it.date }
        // Union of both sources: a committed day whose buckets were pruned and
        // a day with buckets and no commit both belong in the archive.
        val dates = (hourlyDao.datesBefore(today.plusDays(1).toString()) + commits.keys)
            .distinct()
            .sorted()

        val days = dates.mapNotNull { date ->
            val commit = commits[date]
            if (commit != null) {
                ExportDay(
                    date = LocalDate.parse(date),
                    commit = CommitHash.of(LocalDate.parse(date)),
                    steps = commit.steps,
                    activeMinutes = commit.activeMinutes,
                    distanceMeters = commit.distanceMeters,
                    activeKcal = commit.activeKcal,
                    goalSteps = commit.goalSteps,
                    goalMet = commit.goalMet,
                    committed = true
                )
            } else {
                val hours = hourlyDao.day(date).map { it.steps }
                val steps = hours.sum()
                if (steps <= 0L) return@mapNotNull null
                val activeMinutes = Estimates.activeMinutes(hours)
                ExportDay(
                    date = LocalDate.parse(date),
                    commit = CommitHash.of(LocalDate.parse(date)),
                    steps = steps,
                    activeMinutes = activeMinutes,
                    distanceMeters = settings.distanceMeters(steps),
                    activeKcal = Estimates.activeKcal(settings.weightKg, activeMinutes),
                    goalSteps = settings.dailyGoalSteps,
                    goalMet = when (GoalCheck.run(steps, settings.dailyGoalSteps)) {
                        GoalCheckResult.SKIPPED -> null
                        GoalCheckResult.PASSED -> true
                        GoalCheckResult.FAILED -> false
                    },
                    committed = false
                )
            }
        }

        return ExportBundle(
            exportedAtMillis = nowMillis,
            zone = zoneId,
            days = days,
            sessions = sessionDao.allCompleted().mapNotNull { it.toItem() }
        )
    }
}
