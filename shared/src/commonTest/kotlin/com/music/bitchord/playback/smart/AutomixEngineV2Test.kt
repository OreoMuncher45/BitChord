package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Engine V2 policies against synthetic analyses: entrance rescoring moves
 * the drop onto the drums, the vocal gate waits out a lyric, and nothing
 * moves without evidence. Full-plan cases pin that legacy output is
 * untouched when V2 has nothing to act on.
 */
class AutomixEngineV2Test {

    private fun energyLevels(
        duration: Double = 200.0,
        step: Double = 0.5,
        levelAt: (Double) -> Double = { 1.0 },
    ) = generateSequence(0.0) { it + step }
        .takeWhile { it < duration }
        .map { EnergySample(it, levelAt(it)) }
        .toList()

    private fun track(
        id: String,
        bpm: Double = 120.0,
        levels: List<Double>? = null,
        vocals: List<Double>? = null,
        mixIn: List<MixCandidate> = emptyList(),
        duration: Double = 200.0,
    ): TrackAnalysis {
        val beat = 60.0 / bpm
        val downbeats = generateSequence(0.5) { it + 4 * beat }.takeWhile { it < duration - 1 }.toList()
        val energy = energyLevels(duration)
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = duration,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = 0.9,
            downbeats = downbeats,
            phraseBoundaries = downbeats.filterIndexed { index, _ -> index % 8 == 0 },
            firstBeat = 0.5,
            key = "C major",
            keyConfidence = 0.9,
            audibleStartTime = 0.5,
            contentEndTime = duration - 2,
            mixInTime = downbeats.getOrElse(4) { 8.5 },
            mixInCandidates = mixIn,
            energyCurve = energy,
            lowEnergyCurve = energy.mapIndexed { i, s ->
                s.copy(energy = (levels ?: List(energy.size) { 1.0 })[i])
            },
            vocalActivityMask = vocals ?: emptyList(),
            vocalProbability = if (vocals != null) 0.9 else 0.0,
        )
    }

    private fun plan(current: TrackAnalysis, next: TrackAnalysis, v2: Boolean) = planTransition(
        analysis = current,
        nextAnalysis = next,
        currentTrack = TransitionTrackInfo(current.trackId, (current.duration * 1000).toLong()),
        nextTrack = TransitionTrackInfo(next.trackId, (next.duration * 1000).toLong()),
        currentTime = 10.0,
        duration = current.duration,
        mode = CrossfadeMode.SMART,
        advanced = true,
        engineV2 = v2,
    )

    // ── Entrance rescoring ──────────────────────────────────────────

    @Test
    fun entranceMovesOntoTheDrums() {
        val levels = List(400) { i -> if (i * 0.5 < 20.0) 0.2 else 1.0 }
        val rescore = AutomixEngineV2.rescoreEntrance(
            candidates = listOf(
                MixCandidate(time = 4.0, score = 0.9, type = "structural"),
                MixCandidate(time = 20.0, score = 0.5, type = "structural"),
            ),
            original = 4.0,
            lowEnergy = energyLevels().mapIndexed { i, s -> s.copy(energy = levels[i]) },
            beatInterval = 0.5,
            firstBeat = 0.5,
            duration = 200.0,
        )
        assertTrue(rescore.applied, "a 7dB onset must win over a flat pick")
        assertTrue(rescore.time >= 19.0 && rescore.time <= 21.0, "lands on the onset, got ${rescore.time}")
        assertTrue(rescore.improvementDb >= AutomixEngineV2.IMPROVEMENT_DB)
    }

    @Test
    fun entranceKeepsAPickNothingBeats() {
        val rescore = AutomixEngineV2.rescoreEntrance(
            candidates = listOf(MixCandidate(time = 20.0, score = 0.9, type = "structural")),
            original = 4.0,
            lowEnergy = energyLevels(),
            beatInterval = 0.5,
            firstBeat = 0.5,
            duration = 200.0,
        )
        assertTrue(!rescore.applied)
        assertEquals(4.0, rescore.time)
    }

    @Test
    fun entranceNeedsEvidence() {
        val rescore = AutomixEngineV2.rescoreEntrance(
            candidates = listOf(MixCandidate(time = 20.0, score = 0.9, type = "structural")),
            original = 4.0,
            lowEnergy = emptyList(),
            beatInterval = 0.5,
            firstBeat = 0.5,
            duration = 200.0,
        )
        assertTrue(!rescore.applied)
        assertEquals(4.0, rescore.time)
    }

    // ── Vocal gate ──────────────────────────────────────────────────

    private fun vocalTrack(sungUntil: Double): TrackAnalysis {
        val energy = energyLevels()
        val mask = energy.map { if (it.time < sungUntil) 1.0 else 0.0 }
        return track("v", vocals = mask)
    }

    @Test
    fun gateWaitsForThePhraseToEnd() {
        val gate = AutomixEngineV2.vocalGateDelay(vocalTrack(23.0), plannedStart = 20.0, maxDelay = 20.0)
        assertEquals(4.5, gate)
    }

    @Test
    fun gateRespectsItsCap() {
        val gate = AutomixEngineV2.vocalGateDelay(vocalTrack(23.0), plannedStart = 20.0, maxDelay = 2.0)
        assertEquals(2.0, gate)
    }

    @Test
    fun gateGivesUpWhenVocalsNeverClear() {
        // Singing past the whole horizon: start as planned, never slide forever.
        assertEquals(0.0, AutomixEngineV2.vocalGateDelay(vocalTrack(300.0), plannedStart = 20.0, maxDelay = 20.0))
    }

    @Test
    fun gateSilentInAGap() {
        assertEquals(0.0, AutomixEngineV2.vocalGateDelay(vocalTrack(10.0), plannedStart = 20.0, maxDelay = 20.0))
    }

    @Test
    fun gateNeedsAMask() {
        assertEquals(0.0, AutomixEngineV2.vocalGateDelay(track("v"), plannedStart = 20.0, maxDelay = 20.0))
    }

    // ── Full plans ──────────────────────────────────────────────────

    @Test
    fun v2LeavesFlatEvidencePlansAlone() {
        val current = track("a")
        val next = track("b")
        val legacy = plan(current, next, v2 = false)
        val v2 = plan(current, next, v2 = true)
        assertEquals(legacy.transitionStart, v2.transitionStart)
        assertEquals(legacy.incomingCueTime, v2.incomingCueTime)
        assertTrue(v2.policyReasons.none { it.startsWith("v2-") })
    }

    @Test
    fun v2TagsEveryMoveItMakes() {
        // Mismatched pair so the full advanced plan runs (not the phrase
        // shortcut): onset energy plus two structural candidates on the
        // incoming side must move the entrance and say so.
        val current = track("a", bpm = 120.0)
        val levels = List(400) { i -> if (i * 0.5 < 20.0) 0.2 else 1.0 }
        val next = track(
            "b",
            bpm = 97.0,
            levels = levels,
            mixIn = listOf(
                MixCandidate(time = 4.0, score = 0.9, type = "structural"),
                MixCandidate(time = 20.0, score = 0.5, type = "structural"),
            ),
        )
        // Existing track() hardcodes C major; F# forces the filter path.
        val nextMismatch = next.copy(key = "F♯ major")
        val v2 = plan(current, nextMismatch, v2 = true)
        assertTrue(
            v2.policyReasons.any { it.startsWith("v2-entrance") },
            "expected a v2-entrance tag, got: ${v2.policyReasons}",
        )
    }

    @Test
    fun v2AllSungKeepsLegacyStart() {
        // Vocals everywhere: no gap to wait for, so the gate stays silent
        // and the start matches legacy exactly.
        val energy = energyLevels()
        val mask = energy.map { 1.0 }
        val current = track("a", vocals = mask)
        val next = track("b")
        val legacy = plan(current, next, v2 = false)
        val v2 = plan(current, next, v2 = true)
        assertEquals(legacy.transitionStart, v2.transitionStart)
        assertTrue(v2.policyReasons.none { it.startsWith("v2-vocal-gate") })
    }
}
