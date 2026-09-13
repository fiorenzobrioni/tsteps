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
    val recording: Boolean = false
) {
    companion object {

        /** One cheap version check plus one DataStore read; no IPC to the recorder. */
        suspend fun of(
            gateway: StepRecordingGateway,
            store: RecordingStateStore
        ): StepSourceStatus = StepSourceStatus(
            availability = gateway.availability(),
            recording = store.read().subscribed
        )
    }
}
