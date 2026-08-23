package com.callbackdev.tsteps.data

import com.callbackdev.tsteps.data.local.SessionEntity
import com.callbackdev.tsteps.domain.LiveSessionState
import com.callbackdev.tsteps.domain.LiveSessionTracker
import com.callbackdev.tsteps.domain.SessionMetrics
import com.callbackdev.tsteps.domain.StepReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One line of the `$ tsteps track` transcript. */
sealed interface TranscriptEntry {
    /** A minute mark: `05:00  512 steps  0.4 km`. */
    data class Minute(val elapsedMillis: Long, val steps: Long, val distanceMeters: Double) :
        TranscriptEntry

    data object Paused : TranscriptEntry
    data object Resumed : TranscriptEntry
}

/** Everything the track screen and the notification render. */
data class TrackingState(
    val session: LiveSessionState,
    val transcript: List<TranscriptEntry> = emptyList(),
    /** Stride snapshot from the profile at start — the session's own honesty. */
    val strideMeters: Double
) {
    val distanceMeters: Double get() = session.steps * strideMeters
}

/**
 * The single owner of the live session ("the process"). The foreground service
 * is its lifecycle shell: it feeds readings and minute ticks in, and asks for
 * [stop] at ^C — all state transitions are the pure [LiveSessionTracker]'s.
 * Singleton via ServiceLocator so the track screen observes the same state the
 * service writes.
 */
class TrackingManager(
    private val repository: StepRepository,
    private val settingsStore: SettingsStore
) {

    private val _state = MutableStateFlow<TrackingState?>(null)
    val state: StateFlow<TrackingState?> = _state.asStateFlow()

    val isActive: Boolean get() = _state.value != null

    suspend fun start(type: String, nowMillis: Long) {
        if (isActive) return
        val stride = settingsStore.read().strideMeters()
        _state.value = TrackingState(
            session = LiveSessionTracker.start(type, nowMillis),
            strideMeters = stride
        )
    }

    fun onReading(reading: StepReading) {
        _state.update { current ->
            current?.copy(session = LiveSessionTracker.onReading(current.session, reading.cumulativeSteps))
        }
    }

    /** Appends the minute mark for the transcript; the service ticks this. */
    fun onMinuteTick(nowMillis: Long) {
        _state.update { current ->
            if (current == null || current.session.paused) return@update current
            current.copy(
                transcript = current.transcript + TranscriptEntry.Minute(
                    elapsedMillis = current.session.activeMillis(nowMillis),
                    steps = current.session.steps,
                    distanceMeters = current.distanceMeters
                )
            )
        }
    }

    fun pause(nowMillis: Long) {
        _state.update { current ->
            if (current == null || current.session.paused) return@update current
            current.copy(
                session = LiveSessionTracker.pause(current.session, nowMillis),
                transcript = current.transcript + TranscriptEntry.Paused
            )
        }
    }

    fun resume(nowMillis: Long) {
        _state.update { current ->
            if (current == null || !current.session.paused) return@update current
            current.copy(
                session = LiveSessionTracker.resume(current.session, nowMillis),
                transcript = current.transcript + TranscriptEntry.Resumed
            )
        }
    }

    fun cycleType() {
        _state.update { it?.copy(session = LiveSessionTracker.cycleType(it.session)) }
    }

    /**
     * ^C: persists the session (a hunk is born) and clears the process. Returns
     * the stored row, or null if nothing was running.
     */
    suspend fun stop(nowMillis: Long): SessionEntity? {
        val current = _state.value ?: return null
        _state.value = null
        val session = current.session
        val activeMillis = session.activeMillis(nowMillis)
        val entity = SessionEntity(
            startMillis = session.startMillis,
            endMillis = nowMillis,
            type = session.type,
            steps = session.steps,
            distanceMeters = current.distanceMeters,
            avgCadenceSpm = SessionMetrics.avgCadenceSpm(session.steps, activeMillis),
            auto = false,
            activeMillis = activeMillis
        )
        val id = repository.insertSession(entity)
        return entity.copy(id = id)
    }
}
