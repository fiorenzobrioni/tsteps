package com.callbackdev.tsteps.recording

import com.callbackdev.tsteps.ui.format.UnitFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StepSourceStatusTest {

    private val hour = 3_600_000L
    private val now = 1_000L * hour

    private fun state(
        subscribed: Boolean = true,
        lastImportAgo: Long? = hour
    ) = RecordingState(
        subscribed = subscribed,
        importFromMillis = now - 2 * hour,
        importedUntilMillis = now - 2 * hour,
        lastImportMillis = lastImportAgo?.let { now - it } ?: 0L
    )

    @Test
    fun `a recorder that answered recently says nothing`() {
        assertNull(StepSourceStatus.staleFor(state(lastImportAgo = hour), now))
    }

    @Test
    fun `silence past the trust window is worth reporting`() {
        val stale = StepSourceStatus.staleFor(state(lastImportAgo = 7 * hour), now)
        assertEquals(7 * hour, stale)
    }

    /** The edge belongs to the live side: at exactly the window it still counts. */
    @Test
    fun `the trust window itself is not yet silence`() {
        assertNull(
            StepSourceStatus.staleFor(
                state(lastImportAgo = ImportCoverage.TRUST_WINDOW_MILLIS),
                now
            )
        )
    }

    /** A subscription nobody has read yet is new, not late. */
    @Test
    fun `an import that has never landed is not stale`() {
        assertNull(StepSourceStatus.staleFor(state(lastImportAgo = null), now))
    }

    @Test
    fun `a recorder nobody subscribed to has nothing to be late about`() {
        assertNull(StepSourceStatus.staleFor(state(subscribed = false, lastImportAgo = 9 * hour), now))
    }

    /**
     * A stamp in the future means the clock moved, not that the recorder is late.
     * Saying nothing is the honest answer: there is no silence to report, and an
     * age of zero would read as one.
     */
    @Test
    fun `a stamp in the future is a moved clock, not a late recorder`() {
        val state = RecordingState(subscribed = true, lastImportMillis = now + 3 * hour)
        assertNull(StepSourceStatus.staleFor(state, now))
    }

    @Test
    fun `the age reads as one compact token in either language`() {
        assertEquals("45m", UnitFormat.compactAge(45 * 60_000L))
        assertEquals("7h", UnitFormat.compactAge(7 * hour))
        assertEquals("3d", UnitFormat.compactAge(3 * 24 * hour))
        // The hour/day boundary is at two days: "36h" is more use than "1d".
        assertEquals("36h", UnitFormat.compactAge(36 * hour))
    }
}
