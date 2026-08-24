package com.callbackdev.tsteps.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.data.distanceMeters
import com.callbackdev.tsteps.data.toItem
import com.callbackdev.tsteps.domain.Estimates
import com.callbackdev.tsteps.domain.GoalCheckResult
import com.callbackdev.tsteps.domain.Streaks
import com.callbackdev.tsteps.work.SyncScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Re-renders every widget instance from persisted state. Called by the sync
 * workers after each ingest/commit, by the tracking service's minute tick (a
 * walk with the screen off is exactly when the widget matters), from
 * MainActivity's settings collector (theme/units/goal/profile/opacity changes)
 * and again when the app leaves the foreground (the live listener has been
 * ingesting the whole session — the widget is the last thing to hear about it),
 * on provider onUpdate, and around the ↻ tap. No-op with zero widgets. Unlike
 * its tweather parent there is one data source — the day — so a single render
 * feeds every instance.
 */
object TstepsWidgetUpdater {

    /**
     * Outlives any single caller: MainActivity's `onStop` is routinely followed by
     * `onDestroy` (swipe away), which would cancel `lifecycleScope` mid-render.
     */
    private val detachedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Repaint that never takes its caller down with it. Every background caller
     * wants this one: a widget holding its last frame is a nuisance, a worker
     * that dies because the repaint threw is a stopped schedule.
     */
    suspend fun updateAllSafely(context: Context, syncing: Boolean = false) {
        try {
            updateAll(context, syncing)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            // The widget simply keeps whatever it was showing.
        }
    }

    /** Fire-and-forget repaint for callers whose own scope dies with them. */
    fun updateAllDetached(context: Context) {
        val appContext = context.applicationContext
        detachedScope.launch { updateAllSafely(appContext) }
    }

    /**
     * [syncing] paints the same state with the ↻ glyph in its working form — the
     * acknowledgment a tap gets while the sample it asked for is still in flight.
     */
    suspend fun updateAll(context: Context, syncing: Boolean = false) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(
            ComponentName(context, TstepsWidgetProvider::class.java)
        )
        if (ids.isEmpty()) return

        val settings = ServiceLocator.settingsStore(context).read()
        val repository = ServiceLocator.stepRepository(context)
        val anchor = ServiceLocator.trackerStateStore(context).read()
        val sensorOk = SyncScheduler.hasPermission(context) &&
            ServiceLocator.stepSensorReader(context).isAvailable

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val hourly = repository.observeDay(today).first()
        val steps = hourly.sumOf { it.steps }
        val activeMinutes = Estimates.activeMinutes(hourly.map { it.steps })
        val history = repository.observeHistory().first()
        val lastWalk = repository.observeSessionsOfDay(today).first()
            .mapNotNull { it.toItem() }
            .maxByOrNull { it.startMillis }

        val data = WidgetData(
            hasEverSampled = anchor != null,
            sensorOk = sensorOk,
            todaySteps = steps,
            goalSteps = settings.dailyGoalSteps,
            distanceMeters = settings.distanceMeters(steps),
            activeMinutes = activeMinutes,
            activeKcal = Estimates.activeKcal(settings.weightKg, activeMinutes),
            streakDays = Streaks.current(
                history.map { day ->
                    LocalDate.parse(day.date) to when (day.goalMet) {
                        null -> GoalCheckResult.SKIPPED
                        true -> GoalCheckResult.PASSED
                        false -> GoalCheckResult.FAILED
                    }
                },
                today
            ),
            lastWalkStartMillis = lastWalk?.startMillis,
            lastWalkActiveMinutes = lastWalk?.activeMinutes,
            lastWalkApprox = lastWalk?.startApprox == true,
            lastSyncMillis = anchor?.lastTimestampMillis
        )
        val palette = widgetPalette(settings.themeProfileName)
        val now = Instant.now()
        manager.updateAppWidget(
            ids,
            WidgetRenderer.sizeMap(
                context,
                content = { tier ->
                    WidgetContentBuilder.build(
                        data = data,
                        units = settings.units,
                        tier = tier,
                        zone = zone,
                        locale = Locale.getDefault(),
                        now = now
                    )
                },
                palette = palette,
                opacityPct = settings.widgetOpacityPct,
                syncing = syncing
            )
        )
    }
}
