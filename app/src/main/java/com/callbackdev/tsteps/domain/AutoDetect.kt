package com.callbackdev.tsteps.domain

import kotlin.math.roundToLong

/**
 * One recorded span of the cumulative counter: [steps] landed between
 * [fromMillis] and [toMillis]. Spans are contiguous by construction (each one
 * starts where the previous ended — the anchor chain), which is what lets the
 * detector tell "no steps" from "no data".
 */
data class SampleSpan(
    val fromMillis: Long,
    val toMillis: Long,
    val steps: Long
)

/** A detected walk candidate, ready to become an `(auto)` session. */
data class DetectedWalk(
    val startMillis: Long,
    val endMillis: Long,
    val steps: Long
)

/**
 * Conservative walk detection over already-collected counter samples — pure
 * arithmetic, no sensing of its own. The passive pipeline samples every ~15
 * minutes, so this can only ever *infer*: boundaries are honest approximations
 * quantized to the grid (the UI marks them `~`), and a missed walk is by design
 * cheaper than an invented one (PLANNING Fase 11).
 *
 * How it reads the day:
 * 1. Samples are resampled onto a fixed grid of [Tuning.bucketMinutes]-minute
 *    buckets (spread proportionally to time, like [StepAttribution]) so screen-on
 *    2s ticks and passive 15-min spans classify identically.
 * 2. Each bucket is CORE (sustained walking cadence), BRIDGE (mild movement —
 *    a traffic light inside a walk, or a walk edge diluted by a 15-min sample)
 *    or a BREAK. A bridge stretch longer than one passive sampling period stops
 *    gluing: that much ambiguity splits the chain instead of padding it.
 * 3. Maximal unbroken chains qualify only if they have enough core minutes,
 *    enough total span, enough steps and a walking average — every gate biased
 *    toward silence.
 *
 * Detection is windowed to **today** ([dayStartMillis]..[nowMillis]): sessions
 * are working-tree phenomena, committed history never grows new hunks. A chain
 * still warm (ending inside [Tuning.closeGraceMinutes] of now) is withheld — the
 * walk may be ongoing; the next sync will see it finished. [Exclusion] ranges are
 * the times already claimed by existing sessions (manual, auto, dismissed
 * tombstones, the live tracking window): buckets near them break, so nothing is
 * ever double-detected or resurrected after an `[rm]`.
 */
object AutoDetect {

    /**
     * Every threshold in one place — the device-tuning surface of Fase 11.
     * Defaults chosen on paper (conservative), to be re-verified on a real
     * phone before the phase is declared done.
     */
    data class Tuning(
        /** Grid resolution. 5 min splits one passive sample into three buckets. */
        val bucketMinutes: Int = 5,
        /** A bucket at or above this cadence is evidence of real walking. */
        val coreCadenceSpm: Double = 65.0,
        /** Between bridge and core: joinable movement, never evidence by itself. */
        val bridgeCadenceSpm: Double = 40.0,
        /**
         * Longest run of consecutive bridge buckets a chain may contain (3 ×
         * 5 min = one passive sampling period — a walk edge diluted by exactly
         * one sample). Anything longer is ambience, and ambience breaks chains.
         */
        val maxBridgeRunBuckets: Int = 3,
        /** Minimum core evidence a chain needs to qualify. */
        val minCoreMinutes: Int = 10,
        /** Minimum overall span — shorter movement is noise, not a walk. */
        val minSpanMinutes: Int = 20,
        /** Minimum steps — with the other gates this implies a brisk average. */
        val minSteps: Long = 1_500,
        /** Bridged edges may dilute the chain; the average must stay a walk. */
        val minAvgCadenceSpm: Double = 60.0,
        /** A chain ending this close to now may still be in progress: withhold. */
        val closeGraceMinutes: Int = 10,
        /** Buckets within this distance of an exclusion break with it. */
        val exclusionMarginMinutes: Int = 10
    )

    val DEFAULT = Tuning()

    /** A time range already claimed by a session; [endMillis] exclusive. */
    data class Exclusion(val startMillis: Long, val endMillis: Long)

    private enum class Kind { CORE, BRIDGE, BREAK }

    fun detect(
        samples: List<SampleSpan>,
        dayStartMillis: Long,
        nowMillis: Long,
        exclusions: List<Exclusion> = emptyList(),
        tuning: Tuning = DEFAULT
    ): List<DetectedWalk> {
        val bucketMillis = tuning.bucketMinutes * 60_000L
        val bucketCount = ((nowMillis - dayStartMillis) / bucketMillis).toInt()
        if (bucketCount <= 0) return emptyList()
        val gridEndMillis = dayStartMillis + bucketCount * bucketMillis

        // 1. Resample onto the grid, clipping at the day boundary: steps that
        // fell before midnight belong to yesterday's (already committed) story.
        val bucketSteps = DoubleArray(bucketCount)
        samples.forEach { sample ->
            spread(sample, dayStartMillis, gridEndMillis, bucketMillis, bucketSteps)
        }

        // 2. Classify. Exclusion-adjacent buckets are hard breaks.
        val marginMillis = tuning.exclusionMarginMinutes * 60_000L
        val kinds = Array(bucketCount) { index ->
            val bucketStart = dayStartMillis + index * bucketMillis
            val bucketEnd = bucketStart + bucketMillis
            val excluded = exclusions.any { exclusion ->
                bucketStart < exclusion.endMillis + marginMillis &&
                    bucketEnd > exclusion.startMillis - marginMillis
            }
            val cadence = bucketSteps[index] / tuning.bucketMinutes
            when {
                excluded -> Kind.BREAK
                cadence >= tuning.coreCadenceSpm -> Kind.CORE
                cadence >= tuning.bridgeCadenceSpm -> Kind.BRIDGE
                else -> Kind.BREAK
            }
        }
        demoteLongBridgeRuns(kinds, tuning.maxBridgeRunBuckets)

        // 3. Qualify maximal unbroken chains.
        val walks = mutableListOf<DetectedWalk>()
        var chainStart = -1
        for (index in 0..bucketCount) {
            val inChain = index < bucketCount && kinds[index] != Kind.BREAK
            if (inChain && chainStart < 0) chainStart = index
            if (!inChain && chainStart >= 0) {
                qualify(
                    firstBucket = chainStart,
                    lastBucket = index - 1,
                    kinds = kinds,
                    bucketSteps = bucketSteps,
                    dayStartMillis = dayStartMillis,
                    bucketMillis = bucketMillis,
                    nowMillis = nowMillis,
                    tuning = tuning
                )?.let { walks += it }
                chainStart = -1
            }
        }
        return walks
    }

    /** Proportional share of one sample landing on the grid (clipped outside). */
    private fun spread(
        sample: SampleSpan,
        gridStartMillis: Long,
        gridEndMillis: Long,
        bucketMillis: Long,
        bucketSteps: DoubleArray
    ) {
        if (sample.steps <= 0L) return
        if (sample.toMillis <= gridStartMillis || sample.fromMillis >= gridEndMillis) return
        val span = sample.toMillis - sample.fromMillis
        if (span <= 0L) {
            // An instantaneous delta (reboot clamp): all of it lands in the
            // bucket containing its timestamp.
            val index = ((sample.toMillis - gridStartMillis) / bucketMillis)
                .toInt().coerceIn(0, bucketSteps.lastIndex)
            bucketSteps[index] += sample.steps.toDouble()
            return
        }
        val firstIndex = ((maxOf(sample.fromMillis, gridStartMillis) - gridStartMillis) /
            bucketMillis).toInt()
        val lastIndex = (((minOf(sample.toMillis, gridEndMillis) - 1) - gridStartMillis) /
            bucketMillis).toInt().coerceAtMost(bucketSteps.lastIndex)
        for (index in firstIndex..lastIndex) {
            val bucketStart = gridStartMillis + index * bucketMillis
            val overlap = minOf(sample.toMillis, bucketStart + bucketMillis) -
                maxOf(sample.fromMillis, bucketStart)
            if (overlap > 0) {
                bucketSteps[index] += sample.steps * (overlap.toDouble() / span)
            }
        }
    }

    /** Bridge runs longer than the cap turn into breaks — they glue nothing. */
    private fun demoteLongBridgeRuns(kinds: Array<Kind>, maxRun: Int) {
        var runStart = -1
        for (index in 0..kinds.size) {
            val isBridge = index < kinds.size && kinds[index] == Kind.BRIDGE
            if (isBridge && runStart < 0) runStart = index
            if (!isBridge && runStart >= 0) {
                if (index - runStart > maxRun) {
                    for (bridge in runStart until index) kinds[bridge] = Kind.BREAK
                }
                runStart = -1
            }
        }
    }

    private fun qualify(
        firstBucket: Int,
        lastBucket: Int,
        kinds: Array<Kind>,
        bucketSteps: DoubleArray,
        dayStartMillis: Long,
        bucketMillis: Long,
        nowMillis: Long,
        tuning: Tuning
    ): DetectedWalk? {
        val endMillis = dayStartMillis + (lastBucket + 1) * bucketMillis
        // Still warm: the walk may be ongoing — the next sync sees it closed.
        if (endMillis > nowMillis - tuning.closeGraceMinutes * 60_000L) return null

        val spanMinutes = (lastBucket - firstBucket + 1) * tuning.bucketMinutes
        if (spanMinutes < tuning.minSpanMinutes) return null

        val coreMinutes = (firstBucket..lastBucket).count { kinds[it] == Kind.CORE } *
            tuning.bucketMinutes
        if (coreMinutes < tuning.minCoreMinutes) return null

        val steps = (firstBucket..lastBucket).sumOf { bucketSteps[it] }.roundToLong()
        if (steps < tuning.minSteps) return null
        if (steps.toDouble() / spanMinutes < tuning.minAvgCadenceSpm) return null

        return DetectedWalk(
            startMillis = dayStartMillis + firstBucket * bucketMillis,
            endMillis = endMillis,
            steps = steps
        )
    }
}
