package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Engine V2 transition policies, ported from the automix-engine reference
 * (beat-synced, phrase-aligned, vocal-aware DJ handoffs).
 *
 * What ports and what does not is deliberate. The reference renders
 * transitions offline with stem separation (Demucs), structural cue models
 * (CUE-DETR) and time-stretching (Rubber Band) — none of which has an
 * on-device story here. What ports is the *policy*: where the ear wants the
 * entrance, and when a lyric is still being sung. This file answers both
 * from the analysis BitChord already measures (beat grids, low-band energy,
 * vocal masks), and both answers feed the existing renderer unchanged — no
 * new styles, no new gestures, just better-placed ones.
 *
 * Everything here is pure and deterministic per track pair: the planner
 * re-runs every tick, and a policy that changed its mind mid-song would
 * move markers and rendering under the listener.
 */
object AutomixEngineV2 {

    /**
     * Apply the refined entrance only on a material improvement, in dB —
     * or when the analyzer's own pick lands somewhere weak. Verbatim
     * thresholds from the reference's rescoring rule.
     */
    const val IMPROVEMENT_DB = 3.0
    const val WEAK_ENTRANCE_DB = -10.0

    /**
     * Capped contrast bonus: a genuinely loud four-beat entrance wins, but
     * near-silence alone cannot make a weak cue win.
     */
    const val CONTRAST_CAP_DB = 12.0
    const val CONTRAST_WEIGHT = 0.65

    /** Beats of low-band energy read either side of a candidate entrance. */
    const val RISE_WINDOW_BEATS = 8

    /** Candidates need room for the handoff and the tempo release after them. */
    const val MIN_BEATS_BEFORE = 4
    const val MIN_BEATS_AFTER = 20

    /** Only the front of a track is entrance territory. */
    const val CANDIDATE_FRACTION = 0.35
    const val CANDIDATE_CAP_SECONDS = 75.0

    /** How far past the planned start a vocal gap is waited for. */
    const val VOCAL_HORIZON_SECONDS = 6.0

    /** Consecutive quiet mask samples that count as a sustained gap. */
    const val VOCAL_QUIET_RUN = 3

    /** Fade starts this long after the last active vocal frame. */
    const val VOCAL_SETTLE_SECONDS = 0.5

    /** A gated start must still leave a real blend behind. */
    const val MIN_GATED_OVERLAP_SECONDS = 3.0

    /**
     * The entrance moved onto the strongest drum/bass onset, or kept where
     * the analyzer put it.
     */
    data class EntranceRescore(
        val time: Double,
        val applied: Boolean,
        val improvementDb: Double,
    )

    /**
     * Move the incoming entrance onto the strongest low-band onset, keeping
     * the analyzer's pick unless another candidate is materially stronger.
     *
     * Scoring is the reference's, in dB relative to the track's own 90th
     * percentile so it needs no calibrated scale: after-energy plus a
     * capped contrast bonus for the rise into it. [lowEnergy] is the
     * low-band curve when the analyzer ran a band split, else the full
     * energy curve — drums read through either, just less cleanly.
     */
    fun rescoreEntrance(
        candidates: List<MixCandidate>,
        original: Double,
        lowEnergy: List<EnergySample>,
        beatInterval: Double,
        firstBeat: Double,
        duration: Double,
    ): EntranceRescore {
        val fallback = EntranceRescore(original, applied = false, improvementDb = 0.0)
        if (lowEnergy.size < RISE_WINDOW_BEATS || beatInterval <= 0 || duration <= 0) return fallback
        // Beat grid from the measured downbeat phase.
        val beats = ArrayList<Double>()
        var t = firstBeat.coerceAtLeast(0.0)
        while (t <= duration && beats.size < 4096) {
            beats.add(t)
            t += beatInterval
        }
        if (beats.size < MIN_BEATS_AFTER + MIN_BEATS_BEFORE) return fallback
        val window = RISE_WINDOW_BEATS * beatInterval

        fun average(from: Double, until: Double): Double? {
            if (until <= from) return null
            var sum = 0.0
            var count = 0
            for (s in lowEnergy) {
                if (!s.time.isFinite() || !s.energy.isFinite() || s.energy < 0) continue
                if (s.time >= from && s.time < until) {
                    sum += s.energy
                    count++
                }
            }
            return if (count == 0) null else sum / count
        }

        // Reference level: the track's own loud-onset regime, as a ratio the
        // dB below are measured against.
        val perBeat = beats.dropLast(1).mapNotNull { average(it, it + beatInterval) }.sorted()
        if (perBeat.isEmpty()) return fallback
        val reference = perBeat[(perBeat.lastIndex * 0.9).toInt()].takeIf { it > 1e-9 }
            ?: return fallback
        fun db(ratio: Double) = 10.0 * log10(max(ratio, 1e-12))

        data class Scored(val index: Int, val total: Double, val afterDb: Double)

        fun scoreAt(index: Int): Scored? {
            val time = beats[index]
            val after = average(time, time + window) ?: return null
            val before = average(time - window, time) ?: return null
            val afterDb = db(after / reference)
            val riseDb = max(0.0, afterDb - db(before / reference))
            return Scored(index, afterDb + CONTRAST_WEIGHT * min(riseDb, CONTRAST_CAP_DB), afterDb)
        }

        fun snapIndex(time: Double): Int {
            var best = 0
            var bestDistance = Double.MAX_VALUE
            for (i in beats.indices) {
                val d = abs(beats[i] - time)
                if (d < bestDistance) {
                    bestDistance = d
                    best = i
                }
            }
            return best
        }

        fun inMargins(index: Int) = index >= MIN_BEATS_BEFORE && index + MIN_BEATS_AFTER < beats.size

        val cap = min(CANDIDATE_FRACTION * duration, CANDIDATE_CAP_SECONDS)
        val scored = candidates
            .map { it.time }
            .filter { it.isFinite() && it in 0.0..cap }
            .map { snapIndex(it) }
            .filter { inMargins(it) }
            .distinct()
            .mapNotNull { index -> scoreAt(index) }
        if (scored.isEmpty()) return fallback
        val originalIndex = snapIndex(original.coerceIn(0.0, duration))
        val originalScore: Scored = if (inMargins(originalIndex)) {
            scoreAt(originalIndex) ?: return fallback
        } else {
            return fallback
        }
        val referenceDb = 0.0 // ratios already divide by the reference
        val strong = scored.filter { it.afterDb >= referenceDb + WEAK_ENTRANCE_DB }
        val chosen = (strong.ifEmpty { scored }).maxByOrNull { it.total } ?: return fallback
        val improvement = chosen.total - originalScore.total
        val weakOriginal = originalScore.afterDb < referenceDb + WEAK_ENTRANCE_DB
        if (improvement < IMPROVEMENT_DB && !weakOriginal) return fallback
        return EntranceRescore(beats[chosen.index], applied = true, improvementDb = improvement)
    }

    /**
     * Seconds to delay a planned transition start so it does not fade an
     * active lyric, or 0 to start as planned.
     *
     * The rule is the reference's vocal handoff: once the final active
     * frame finishes, the remaining gap sets the exact boundary. Capped by
     * [maxDelay] and abandoned entirely when no sustained gap appears
     * within the horizon — a track that sings throughout keeps its planned
     * start rather than sliding forever. No vocal mask means no evidence,
     * which never blocks.
     */
    fun vocalGateDelay(
        analysis: TrackAnalysis,
        plannedStart: Double,
        maxDelay: Double,
    ): Double {
        if (maxDelay <= 0 || !plannedStart.isFinite()) return 0.0
        val mask = analysis.vocalActivityMask
        val times = analysis.energyCurve.map { it.time }
        if (mask.isEmpty() || mask.size != times.size) return 0.0

        fun activeAt(time: Double): Boolean {
            var index = -1
            for (i in times.indices) {
                val t = times[i]
                if (!t.isFinite()) continue
                if (t <= time) index = i else break
            }
            if (index < 0) return false
            return mask[index] >= VOCAL_ACTIVE_THRESHOLD
        }

        if (!activeAt(plannedStart)) return 0.0
        val firstAfter = times.indexOfFirst { it.isFinite() && it > plannedStart }
        if (firstAfter < 0) return 0.0
        var quiet = 0
        var i = firstAfter
        while (i < mask.size) {
            val t = times[i]
            if (!t.isFinite() || t > plannedStart + VOCAL_HORIZON_SECONDS) break
            quiet = if (mask[i] < VOCAL_ACTIVE_THRESHOLD) quiet + 1 else 0
            if (quiet >= VOCAL_QUIET_RUN) {
                return min(t + VOCAL_SETTLE_SECONDS - plannedStart, maxDelay)
            }
            i++
        }
        return 0.0
    }
}

/** One decimal for policy reason tags. */
internal fun tenths(value: Double): Double = (value * 10).roundToInt() / 10.0
