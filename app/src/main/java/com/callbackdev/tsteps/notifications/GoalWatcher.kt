package com.callbackdev.tsteps.notifications

import android.content.Context
import com.callbackdev.tsteps.data.NotificationStateStore
import com.callbackdev.tsteps.data.ServiceLocator
import com.callbackdev.tsteps.data.SettingsStore
import com.callbackdev.tsteps.data.StepRepository
import com.callbackdev.tsteps.domain.GoalCheckResult
import com.callbackdev.tsteps.domain.Streaks
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import java.util.Locale

/**
 * The goal-check watcher: edge-triggered, once per day. Evaluated at the two
 * places new steps arrive in the background — the periodic sync and the
 * tracking service's minute tick — never from the foreground screen (the user
 * is watching the number cross; a notification would just echo them).
 */
object GoalWatcher {

    suspend fun evaluate(context: Context) {
        evaluate(
            repository = ServiceLocator.stepRepository(context),
            settingsStore = ServiceLocator.settingsStore(context),
            stateStore = ServiceLocator.notificationStateStore(context),
            post = { content -> StepsNotifier.postGoalReached(context, content) },
            resources = { context.resources },
            canPost = { StepsNotifier.canPost(context) }
        )
    }

    /** Dependency-explicit core, unit-tested without a NotificationManager. */
    suspend fun evaluate(
        repository: StepRepository,
        settingsStore: SettingsStore,
        stateStore: NotificationStateStore,
        post: (StepsNotifications.Content) -> Unit,
        resources: () -> android.content.res.Resources,
        canPost: () -> Boolean = { true },
        clock: Clock = Clock.systemDefaultZone(),
        locale: Locale = Locale.getDefault()
    ) {
        val settings = settingsStore.read()
        val goal = settings.dailyGoalSteps
        if (!settings.notifications.goalCheck || goal <= 0 || !canPost()) return

        val today = LocalDate.now(clock)
        if (stateStore.goalNotifiedDate() == today) return
        val steps = repository.stepsOfDay(today)
        if (steps < goal) return

        // Mark BEFORE posting: a crash between the two costs one notification,
        // never a repeat.
        stateStore.markGoalNotified(today)
        val history = repository.observeHistory().first().map { day ->
            LocalDate.parse(day.date) to when (day.goalMet) {
                null -> GoalCheckResult.SKIPPED
                true -> GoalCheckResult.PASSED
                false -> GoalCheckResult.FAILED
            }
        }
        post(
            StepsNotifications.goalReached(
                steps = steps,
                goalSteps = goal,
                // Committed streak + today, which just went green.
                streakDays = Streaks.current(history, today) + 1,
                locale = locale,
                resources = resources()
            )
        )
    }
}
