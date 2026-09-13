package com.callbackdev.tsteps.recording

/**
 * What the `steps` section of `settings.config` says about who is counting
 * (Fase 24c). Deliberately read-only: the source is the app's business — there
 * is no switch here, because a step counter that asks you to turn counting on is
 * asking you to know what a subscription is. The file still says which of the
 * two is doing the work, because the file does not lie about where a number
 * came from.
 */
data class StepSourceStatus(
    val availability: RecordingAvailability = RecordingAvailability.UNAVAILABLE,
    /** Play services has been asked and agreed to record for us. */
    val recording: Boolean = false,
    /**
     * How long it has been since an import last landed — and only when that is
     * longer than the app itself is willing to trust (Fase 24f). Null means the
     * recorder is answering, which is the ordinary case and says nothing.
     */
    val staleForMillis: Long? = null
) {
    companion object {

        /** One cheap version check plus one DataStore read; no IPC to the recorder. */
        suspend fun of(
            gateway: StepRecordingGateway,
            store: RecordingStateStore,
            nowMillis: Long = System.currentTimeMillis()
        ): StepSourceStatus {
            val state = store.read()
            return StepSourceStatus(
                availability = gateway.availability(),
                recording = state.subscribed,
                staleForMillis = staleFor(state, nowMillis)
            )
        }

        /**
         * The silence worth reporting. Pure, so the thresholds are a test rather
         * than a thing to reproduce on a phone.
         *
         * Two states deliberately do **not** count as stale. A subscription that
         * has never been read yet is not late, it is new — the first import waits
         * for the hour to end. And a recorder nobody subscribed to has nothing to
         * be late about. What is left is the case that matters on a phone whose
         * system suspends apps: it worked, and then it stopped.
         */
        fun staleFor(state: RecordingState, nowMillis: Long): Long? {
            if (!state.subscribed || state.lastImportMillis <= 0L) return null
            val coverage = ImportCoverage(state.importedUntilMillis, state.lastImportMillis)
            return if (coverage.isLiveAt(nowMillis)) {
                null
            } else {
                (nowMillis - state.lastImportMillis).coerceAtLeast(0L)
            }
        }
    }
}
