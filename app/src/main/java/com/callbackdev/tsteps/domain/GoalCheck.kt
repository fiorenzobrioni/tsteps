package com.callbackdev.tsteps.domain

import java.time.LocalDate

/**
 * The daily goal as a CI check: a factual pass/fail, or no run at all. `SKIPPED`
 * (goal unset) is a first-class result — the app is fully functional without a
 * goal, and no guilt mechanics exist without opting in.
 */
enum class GoalCheckResult { PASSED, FAILED, SKIPPED }

object GoalCheck {
    fun run(steps: Long, goalSteps: Int): GoalCheckResult = when {
        goalSteps <= 0 -> GoalCheckResult.SKIPPED
        steps >= goalSteps -> GoalCheckResult.PASSED
        else -> GoalCheckResult.FAILED
    }
}

/**
 * Streaks over committed days: consecutive calendar dates whose goal check
 * passed. A skipped check (no goal that day) and a missing date both end a
 * streak — the metaphor is a commit streak, and there was no green check that
 * day. Pure functions over (date, passed) pairs; computed on read, never stored,
 * so there is no streak state to corrupt.
 */
object Streaks {

    /**
     * The streak running up to [today]. Today itself is usually uncommitted, so
     * the chain is anchored at yesterday: a streak is not broken by the fact
     * that today is still being written.
     */
    fun current(days: List<Pair<LocalDate, GoalCheckResult>>, today: LocalDate): Int {
        val passed = days.filter { it.second == GoalCheckResult.PASSED }
            .map { it.first }
            .toHashSet()
        var cursor = today.minusDays(1)
        var streak = 0
        while (cursor in passed) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    fun longest(days: List<Pair<LocalDate, GoalCheckResult>>): Int {
        val passed = days.filter { it.second == GoalCheckResult.PASSED }
            .map { it.first }
            .sorted()
        var longest = 0
        var run = 0
        var previous: LocalDate? = null
        for (date in passed) {
            run = if (previous != null && previous.plusDays(1) == date) run + 1 else 1
            previous = date
            if (run > longest) longest = run
        }
        return longest
    }
}
