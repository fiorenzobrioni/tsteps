package com.callbackdev.tsteps.domain

/**
 * Pure state of a running tracked session ("the process"). All transitions live
 * in [LiveSessionTracker] and take explicit timestamps/readings, so pauses,
 * mid-session reboots and duration math are unit tests, not device surprises.
 *
 * No double counting by construction: session steps are a *labeled window over
 * the same cumulative counter* the daily total reads — they are never added to
 * anything, only remembered as "these of today's steps happened between start
 * and ^C".
 */
data class LiveSessionState(
    /** `walk` or `other` — deliberately few (VISION §6.7). */
    val type: String,
    val startMillis: Long,
    val steps: Long = 0,
    val lastCumulative: Long? = null,
    val paused: Boolean = false,
    val pausedTotalMillis: Long = 0,
    val pausedSinceMillis: Long? = null
) {
    /** Wall time minus every pause — what speed/pace/cadence divide by. */
    fun activeMillis(nowMillis: Long): Long {
        val runningPause = pausedSinceMillis?.let { nowMillis - it } ?: 0L
        return (nowMillis - startMillis - pausedTotalMillis - runningPause).coerceAtLeast(0L)
    }
}

object LiveSessionTracker {

    fun start(type: String, nowMillis: Long) = LiveSessionState(type = type, startMillis = nowMillis)

    /**
     * Feeds one cumulative counter reading. The first reading only anchors; while
     * paused, deltas advance the anchor but are discarded (steps walked during ^Z
     * belong to the day, not to the session). A counter that went backwards means
     * a reboot mid-session: the fresh cumulative value IS the delta since boot,
     * same rule as the daily [StepTracker].
     */
    fun onReading(state: LiveSessionState, cumulativeSteps: Long): LiveSessionState {
        val last = state.lastCumulative
            ?: return state.copy(lastCumulative = cumulativeSteps)
        val delta = if (cumulativeSteps >= last) cumulativeSteps - last else cumulativeSteps
        return state.copy(
            steps = if (state.paused) state.steps else state.steps + delta,
            lastCumulative = cumulativeSteps
        )
    }

    fun pause(state: LiveSessionState, nowMillis: Long): LiveSessionState =
        if (state.paused) state else state.copy(paused = true, pausedSinceMillis = nowMillis)

    fun resume(state: LiveSessionState, nowMillis: Long): LiveSessionState {
        val since = state.pausedSinceMillis ?: return state
        return state.copy(
            paused = false,
            pausedTotalMillis = state.pausedTotalMillis + (nowMillis - since),
            pausedSinceMillis = null
        )
    }

    fun cycleType(state: LiveSessionState): LiveSessionState =
        state.copy(type = if (state.type == "walk") "other" else "walk")
}
