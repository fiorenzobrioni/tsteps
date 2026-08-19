package com.callbackdev.tsteps.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Fase 11 detector, scenario by scenario. Times are minutes from an
 * arbitrary day start (0) so every boundary lands on the 5-min grid the
 * detector quantizes to. Cadences: 100 spm = a real walk, ~50 = ambient
 * puttering, 0 = stillness. The passive pipeline's shape — contiguous 15-min
 * sample spans — is the default fixture; a few tests use finer spans (the
 * screen-on live listener's shape) to prove the grid normalizes both.
 */
class AutoDetectTest {

    private fun m(minutes: Int): Long = minutes * 60_000L

    private fun sample(fromMin: Int, toMin: Int, steps: Long) =
        SampleSpan(m(fromMin), m(toMin), steps)

    private fun detect(
        samples: List<SampleSpan>,
        nowMin: Int,
        dayStartMin: Int = 0,
        exclusions: List<AutoDetect.Exclusion> = emptyList()
    ) = AutoDetect.detect(
        samples = samples,
        dayStartMillis = m(dayStartMin),
        nowMillis = m(nowMin),
        exclusions = exclusions
    )

    @Test
    fun `a clean walk across two passive samples becomes one detected walk`() {
        val walks = detect(
            samples = listOf(
                sample(525, 540, 0),
                sample(540, 555, 1_500),
                sample(555, 570, 1_500),
                sample(570, 585, 0)
            ),
            nowMin = 600
        )
        assertEquals(listOf(DetectedWalk(m(540), m(570), 3_000)), walks)
    }

    @Test
    fun `a walk misaligned with the sampling grid keeps its diluted edges`() {
        // Real walk 09:15..10:00-ish at 100 spm, but the samples caught the
        // edges half-walking: 53 and 47 spm — bridge buckets glued to the core.
        val walks = detect(
            samples = listOf(
                sample(540, 555, 0),
                sample(555, 570, 800),
                sample(570, 585, 1_500),
                sample(585, 600, 700),
                sample(600, 615, 0)
            ),
            nowMin = 630
        )
        assertEquals(listOf(DetectedWalk(m(555), m(600), 3_000)), walks)
    }

    @Test
    fun `an hour of ambient puttering detects nothing`() {
        // 50 spm sustained: bridge-level everywhere, no core evidence — and a
        // bridge run that long is ambience, which breaks instead of gluing.
        val walks = detect(
            samples = (0 until 4).map { i ->
                sample(540 + i * 15, 555 + i * 15, 750)
            },
            nowMin = 630
        )
        assertTrue(walks.isEmpty())
    }

    @Test
    fun `a chain still inside the close grace is withheld until the next pass`() {
        val samples = listOf(
            sample(525, 540, 0),
            sample(540, 555, 1_500),
            sample(555, 570, 1_500)
        )
        // Ended 8 min ago: might still be walking — silence.
        assertTrue(detect(samples, nowMin = 578).isEmpty())
        // Same data seen 15 min later: closed and detected.
        assertEquals(
            listOf(DetectedWalk(m(540), m(570), 3_000)),
            detect(samples, nowMin = 585)
        )
    }

    @Test
    fun `buckets near an existing session break the chain`() {
        val walks = detect(
            samples = listOf(
                sample(525, 540, 0),
                sample(540, 555, 1_500),
                sample(555, 570, 1_500),
                sample(570, 585, 0)
            ),
            nowMin = 600,
            // A manual session already claims the middle of the walk.
            exclusions = listOf(AutoDetect.Exclusion(m(555), m(560)))
        )
        assertTrue(walks.isEmpty())
    }

    @Test
    fun `an exclusion far away does not affect the walk`() {
        val walks = detect(
            samples = listOf(
                sample(540, 555, 1_500),
                sample(555, 570, 1_500),
                sample(570, 585, 0)
            ),
            nowMin = 600,
            exclusions = listOf(AutoDetect.Exclusion(m(100), m(130)))
        )
        assertEquals(listOf(DetectedWalk(m(540), m(570), 3_000)), walks)
    }

    @Test
    fun `two walks separated by real stillness are two sessions`() {
        val walks = detect(
            samples = listOf(
                sample(540, 555, 1_500),
                sample(555, 570, 1_500),
                sample(570, 585, 0),
                sample(585, 600, 0),
                sample(600, 615, 0),
                sample(615, 630, 1_500),
                sample(630, 645, 1_500),
                sample(645, 660, 0)
            ),
            nowMin = 660
        )
        assertEquals(
            listOf(
                DetectedWalk(m(540), m(570), 3_000),
                DetectedWalk(m(615), m(645), 3_000)
            ),
            walks
        )
    }

    @Test
    fun `a walk crossing midnight is clipped to today`() {
        // Walking since 575 at 100 spm, but the day starts at 600: the detected
        // walk starts exactly at day start with only today's share of the steps.
        val walks = detect(
            samples = listOf(
                sample(575, 590, 1_500),
                sample(590, 605, 1_500),
                sample(605, 620, 1_500),
                sample(620, 635, 1_500),
                sample(635, 650, 0)
            ),
            nowMin = 655,
            dayStartMin = 600
        )
        assertEquals(listOf(DetectedWalk(m(600), m(635), 3_500)), walks)
    }

    @Test
    fun `a short intense burst is below the minimum span`() {
        val walks = detect(
            samples = listOf(
                sample(530, 540, 0),
                sample(540, 550, 1_100), // 10 min at 110 spm — fine-grained span
                sample(550, 565, 0)
            ),
            nowMin = 600
        )
        assertTrue(walks.isEmpty())
    }

    @Test
    fun `a bridge stretch longer than one sampling period splits the chain`() {
        // 10 min walk · 20 min slow stroll (45 spm) · 10 min walk: the stroll is
        // too long to glue, and neither fragment alone reaches the minimum span.
        val walks = detect(
            samples = listOf(
                sample(540, 550, 1_000),
                sample(550, 570, 900),
                sample(570, 580, 1_000),
                sample(580, 595, 0)
            ),
            nowMin = 620
        )
        assertTrue(walks.isEmpty())
    }

    @Test
    fun `the minimum steps gate keeps a slow 20-minute walk silent`() {
        // 65 spm: core-level cadence, but 20 min × 65 = 1300 < 1500 steps.
        val short = detect(
            samples = listOf(sample(540, 560, 1_300), sample(560, 575, 0)),
            nowMin = 600
        )
        assertTrue(short.isEmpty())
        // Five more minutes of the same walk crosses the gate.
        val longer = detect(
            samples = listOf(sample(540, 565, 1_625), sample(565, 580, 0)),
            nowMin = 600
        )
        assertEquals(listOf(DetectedWalk(m(540), m(565), 1_625)), longer)
    }

    @Test
    fun `no samples or a day just started detect nothing`() {
        assertTrue(detect(emptyList(), nowMin = 600).isEmpty())
        assertTrue(detect(listOf(sample(0, 2, 100)), nowMin = 3).isEmpty())
    }

    @Test
    fun `an instantaneous reboot delta does not crash or invent a walk`() {
        val walks = detect(
            samples = listOf(
                sample(540, 555, 0),
                SampleSpan(m(555), m(555), 2_000), // zero-span clamp delta
                sample(555, 570, 0)
            ),
            nowMin = 600
        )
        assertTrue(walks.isEmpty())
    }
}
