package com.callbackdev.tsteps.recording

import com.callbackdev.tsteps.data.local.HourlyStepsDao
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One import pass: ask Play services what it recorded while nobody was looking,
 * and write those hours into the day. Ridden by the moments the app is already
 * awake (the 15-minute sampler, the midnight rollover), because reading is an
 * IPC to a local database — no sensor, no listener, no wakeup of its own.
 *
 * **Automatic, with no switch** (decided with the committente, 13 Sep 2026): a
 * step counter is expected to count, and asking the user to turn counting on is
 * asking them to know what a subscription is. Where Play services is missing or
 * too old nothing happens at all and the sensor path stays the only one — the
 * file says which of the two is counting, it never pretends.
 *
 * **One source per hour, never two.** Both the sensor and Play services count the
 * same steps, so adding them would double the day. The line between them is time:
 * everything before [RecordingState.importedUntilMillis] belongs to the import,
 * the hour in progress belongs to the sensor (that is what keeps the number on
 * screen ticking stride by stride), and the hour that just ended is read once
 * more on a later pass — the import writes by SET, so repeating is free and the
 * authoritative number always wins.
 */
class StepImporter(
    private val gateway: StepRecordingGateway,
    private val store: RecordingStateStore,
    private val hourlyDao: HourlyStepsDao,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {

    // The sampler, the rollover and a foreground resume overlap freely; each pass
    // is idempotent, so waiting is always correct.
    private val mutex = Mutex()

    suspend fun run(nowMillis: Long = System.currentTimeMillis()): StepImportOutcome =
        mutex.withLock { pass(nowMillis) }

    private suspend fun pass(nowMillis: Long): StepImportOutcome {
        val availability = gateway.availability()
        if (availability != RecordingAvailability.AVAILABLE) {
            return StepImportOutcome.Unsupported(availability)
        }
        val zoneId = zone()
        val state = store.read()
        if (!state.subscribed) return arm(state, nowMillis, zoneId)

        // The hour in progress is the sensor's, so a pass only ever imports hours
        // that have ended.
        val horizon = RecordingInterop.alignDownToHour(nowMillis, zoneId)
        val from = maxOf(state.importFromMillis, horizon - MAX_BACKFILL_MILLIS)
        if (from >= horizon) return StepImportOutcome.UpToDate

        val hours = try {
            gateway.readHourlySteps(from, horizon)
        } catch (error: Exception) {
            // The watermark stays exactly where it was: a failed read must never
            // look like "those hours held no steps", or they would be skipped for
            // good. Dropping the subscription flag re-arms it on the next pass,
            // which is what a revoked-then-granted permission needs.
            store.write(state.copy(subscribed = false))
            return StepImportOutcome.Failed(error)
        }

        val shares = RecordingInterop.hourShares(hours, zoneId)
        // An hour Play services reports as empty is left alone rather than zeroed:
        // the import fills what nobody counted, it does not erase what somebody
        // did. Only hours it has something to say about are rewritten.
        shares.forEach { hourlyDao.setSteps(it.date.toString(), it.hour, it.steps) }

        // Short of the horizon by one hour on purpose: Play services writes with a
        // lag of its own, so the hour that just ended is read again next time
        // instead of being frozen half-recorded.
        val importedUntil = maxOf(state.importedUntilMillis, horizon - HOUR_MILLIS)
        store.write(
            state.copy(
                importFromMillis = importedUntil,
                importedUntilMillis = importedUntil,
                lastImportMillis = nowMillis
            )
        )
        return StepImportOutcome.Imported(hours = shares.size, steps = shares.sumOf { it.steps })
    }

    /**
     * Stops the recording and forgets everything about it. Called when the app
     * loses the permission to count at all ([com.callbackdev.tsteps.work.SyncScheduler]
     * is the single owner of that decision): revoking `ACTIVITY_RECOGNITION` must
     * stop Play services from recording *for us* too, not just stop us reading —
     * data kept on behalf of an app that may no longer look at it is data nobody
     * asked for.
     *
     * The state is cleared even when the call fails, and that is deliberate: what
     * it holds is a claim that hours are covered by an import, and the moment the
     * import is gone that claim is false. Leaving the watermark behind would keep
     * the counter standing down from hours nobody is going to write.
     */
    suspend fun stop(): Boolean = mutex.withLock {
        if (!store.read().subscribed) return@withLock false
        val stopped = try {
            gateway.unsubscribe()
        } catch (error: Exception) {
            false
        }
        store.write(RecordingState())
        stopped
    }

    private suspend fun arm(
        state: RecordingState,
        nowMillis: Long,
        zoneId: ZoneId
    ): StepImportOutcome {
        if (!gateway.subscribe()) return StepImportOutcome.NotRecording
        // Play services knows nothing about the hour it was asked in, so the
        // import starts at the next whole hour and the sensor keeps this one.
        // `importedUntilMillis` stays where it is: nothing has been imported yet,
        // and until something is, the sensor path must not stand down anywhere.
        val nextHour = RecordingInterop.alignDownToHour(nowMillis, zoneId) + HOUR_MILLIS
        store.write(state.copy(subscribed = true, importFromMillis = nextHour))
        return StepImportOutcome.Arming
    }

    companion object {
        private const val HOUR_MILLIS = 3_600_000L

        /**
         * Play services keeps ten days. Asking for more is asking for nothing, and
         * a phone that was off for a fortnight would otherwise open with a request
         * spanning weeks of hours that no longer exist.
         */
        const val MAX_BACKFILL_MILLIS = 10L * 24 * HOUR_MILLIS
    }
}

/** What a pass did — the `//` channel and the tests read the day off this. */
sealed interface StepImportOutcome {

    /** Hours written into the day. */
    data class Imported(val hours: Int, val steps: Long) : StepImportOutcome

    /** Nothing has ended since the last pass. */
    data object UpToDate : StepImportOutcome

    /** Subscribed just now; the first hour to import has not ended yet. */
    data object Arming : StepImportOutcome

    /** Play services is there but would not record — a revoked permission. */
    data object NotRecording : StepImportOutcome

    /** No Play services, or too old for the Recording API. */
    data class Unsupported(val availability: RecordingAvailability) : StepImportOutcome

    /** The read threw; the watermark did not move. */
    data class Failed(val error: Throwable) : StepImportOutcome
}

/**
 * The instant an hourly bucket starts. `atTime` rather than `atStartOfDay` plus
 * hours: on the night the clock jumps, adding hours to midnight lands on a
 * different local hour than the one the bucket is named after.
 */
internal fun hourStartMillis(date: LocalDate, hour: Int, zone: ZoneId): Long =
    date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
