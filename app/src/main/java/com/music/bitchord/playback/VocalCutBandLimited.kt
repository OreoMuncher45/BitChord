package com.music.bitchord.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sing's band-limited remover, as shipped in v2.1.11 and kept selectable.
 *
 * A plain full-spectrum L−R cut carves holes through everything panned
 * center — kick, bass, snare — because a vocal's harmonics share bins with
 * drums, synths and guitars all the way up the spectrum. This engine
 * instead cuts only the band vocals actually live in, and ducks out of the
 * way whenever a drum transient hits:
 *
 * - Sub/bass (< 150 Hz) and air (> 10 kHz) pass untouched, so kick,
 *   bassline and cymbal sheen survive. Fourth-order Linkwitz-Riley
 *   splits (24 dB/oct) keep the transition bands narrow — a 12 dB slope
 *   would leave half the shimmer sitting in the cut band.
 * - Vocals are sustained and harmonic while drums are transient spikes, so
 *   an envelope follower on the mid-band (fast vs slow attack) bypasses
 *   the cut for the ~20ms a snare crack lasts, then clamps back down.
 * - Everything panned wide is never touched at any setting.
 *
 * Pure math, no Android types, so unit tests drive it directly. O(~40)
 * flops per sample, stateless across calls except filter memory, which
 * [reset] clears on flush/seek. Sample rate is set by [configure] and
 * defaults to 48 kHz before the sink reports it.
 */
class VocalCutBandLimited : SingEngine {

    /** Split points and detector tuning — conservative, documented, tunable. */
    companion object {
        const val LOW_CUTOFF_HZ = 150.0
        const val HIGH_CUTOFF_HZ = 10_000.0
        // Butterworth stages (Q = 1/sqrt(2)): two cascaded make a
        // Linkwitz-Riley 24 dB/oct split, -6 dB at the cutoff, in phase.
        // (Q = 0.5 stages droop deep into the passband — verified the hard
        // way: bass kept barely half its energy.)
        const val FILTER_Q = 0.70710678

        /** Fast envelope follows ~1ms attacks, slow one ~60ms of sustain. */
        const val ENV_FAST_MS = 1.0
        const val ENV_SLOW_MS = 60.0

        /** Bypass slams shut in ~0.5ms and eases back over ~25ms. */
        const val BYPASS_ATTACK_MS = 0.5
        const val BYPASS_RELEASE_MS = 25.0

        /** Envelope ratio that reads as a drum hit rather than a vowel. */
        const val TRANSIENT_RATIO = 3.0f

        /** Below this mid-band level nothing counts as a transient (hiss-safe). */
        const val NOISE_FLOOR = 0.004f

        const val DEFAULT_SAMPLE_RATE = 48_000
    }

    var sampleRateHz: Int = DEFAULT_SAMPLE_RATE
        private set

    // RBJ lowpass coefficients, recomputed in configure().
    private var b0 = 0f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var c0 = 0f
    private var c1 = 0f
    private var c2 = 0f
    private var d1 = 0f
    private var d2 = 0f

    // Per-channel filter memory: two cascaded biquad stages per split
    // (Linkwitz-Riley 24 dB/oct). Arrays keep lowpass() allocation-free.
    private val low1Ls = FloatArray(4)
    private val low2Ls = FloatArray(4)
    private val low1Rs = FloatArray(4)
    private val low2Rs = FloatArray(4)
    private val high1Ls = FloatArray(4)
    private val high2Ls = FloatArray(4)
    private val high1Rs = FloatArray(4)
    private val high2Rs = FloatArray(4)

    private var envFast = 0f
    private var envSlow = 0f
    private var bypass = 0f

    private var envFastA = 0f
    private var envSlowA = 0f
    private var bypassAttackA = 0f
    private var bypassReleaseA = 0f

    /** Reusable frame output — audio-thread confined, never allocated per call. */
    private val frameOut = FloatArray(2)

    override fun configure(sampleRate: Int) {
        sampleRateHz = sampleRate.coerceAtLeast(8_000)
        val sr = sampleRateHz.toDouble()
        computeLowpass(LOW_CUTOFF_HZ / sr).let { (nb0, nb1, nb2, na1, na2) ->
            b0 = nb0.toFloat(); b1 = nb1.toFloat(); b2 = nb2.toFloat()
            a1 = na1.toFloat(); a2 = na2.toFloat()
        }
        computeLowpass(HIGH_CUTOFF_HZ / sr).let { (nb0, nb1, nb2, na1, na2) ->
            c0 = nb0.toFloat(); c1 = nb1.toFloat(); c2 = nb2.toFloat()
            d1 = na1.toFloat(); d2 = na2.toFloat()
        }
        envFastA = timeConst(ENV_FAST_MS, sr).toFloat()
        envSlowA = timeConst(ENV_SLOW_MS, sr).toFloat()
        bypassAttackA = timeConst(BYPASS_ATTACK_MS, sr).toFloat()
        bypassReleaseA = timeConst(BYPASS_RELEASE_MS, sr).toFloat()
        reset()
    }

    private fun computeLowpass(normalized: Double): DoubleArray {
        val w0 = 2.0 * PI * normalized.coerceIn(0.0001, 0.4999)
        val alpha = sin(w0) / (2.0 * FILTER_Q)
        val a0inv = 1.0 / (1.0 + alpha)
        return doubleArrayOf(
            ((1.0 - cos(w0)) / 2.0) * a0inv,
            (1.0 - cos(w0)) * a0inv,
            ((1.0 - cos(w0)) / 2.0) * a0inv,
            (-2.0 * cos(w0)) * a0inv,
            (1.0 - alpha) * a0inv,
        )
    }

    private fun timeConst(ms: Double, sr: Double): Double = 1.0 - exp(-1.0 / (ms / 1000.0 * sr))

    override fun reset() {
        low1Ls.fill(0f); low2Ls.fill(0f); low1Rs.fill(0f); low2Rs.fill(0f)
        high1Ls.fill(0f); high2Ls.fill(0f); high1Rs.fill(0f); high2Rs.fill(0f)
        envFast = 0f; envSlow = 0f; bypass = 0f
    }

    /**
     * Processes interleaved stereo Float32 samples in place.
     *
     * Filter memory and envelopes track every call, but nothing is written
     * while [cut] is idle — a disabled Sing leaves the block byte-identical.
     */
    override fun processSpan(samples: FloatArray, sampleCount: Int, cut: Float) {
        val active = cut > 0.001f
        val c = cut.coerceIn(0f, 1f)
        var idx = 0
        while (idx < sampleCount) {
            val left = samples[idx]
            val right = samples[idx + 1]
            step(left, right, if (active) c else 0f, active)
            if (active) {
                samples[idx] = frameOut[0]
                samples[idx + 1] = frameOut[1]
            }
            idx += 2
        }
    }

    /** One stereo frame into [frameOut]; the short path's per-sample entry. */
    override fun processFrame(left: Float, right: Float, cut: Float): FloatArray {
        step(left, right, cut.coerceIn(0f, 1f), cut > 0.001f)
        return frameOut
    }

    private fun lowpass(
        x: Float,
        b0: Float, b1: Float, b2: Float, a1: Float, a2: Float,
        st: FloatArray,
    ): Float {
        val y = b0 * x + b1 * st[0] + b2 * st[1] - a1 * st[2] - a2 * st[3]
        st[1] = st[0]; st[0] = x; st[3] = st[2]; st[2] = y
        return y
    }

    private fun step(l: Float, r: Float, cut: Float, active: Boolean) {
        val lowL = lowpass(lowpass(l, b0, b1, b2, a1, a2, low1Ls), b0, b1, b2, a1, a2, low2Ls)
        val lowR = lowpass(lowpass(r, b0, b1, b2, a1, a2, low1Rs), b0, b1, b2, a1, a2, low2Rs)
        val highL = l - lowpass(lowpass(l, c0, c1, c2, d1, d2, high1Ls), c0, c1, c2, d1, d2, high2Ls)
        val highR = r - lowpass(lowpass(r, c0, c1, c2, d1, d2, high1Rs), c0, c1, c2, d1, d2, high2Rs)
        val midL = l - lowL - highL
        val midR = r - lowR - highR
        val midMid = (midL + midR) * 0.5f

        // Transient hunt on the mid-band mono: drums spike, vowels glide.
        val mag = abs(midMid)
        envFast += envFastA * (mag - envFast)
        envSlow += envSlowA * (mag - envSlow)
        val hit = if (envFast > maxOf(envSlow * TRANSIENT_RATIO, NOISE_FLOOR)) 1f else 0f
        val smooth = if (hit > bypass) bypassAttackA else bypassReleaseA
        bypass += smooth * (hit - bypass)

        if (!active) return
        // No makeup gain on purpose: the removed vocal's energy stays
        // removed, and boosting what is left would double bass and air
        // (they bypass the cut entirely). The volume knob is honest here.
        val effCut = cut * (1f - bypass)
        frameOut[0] = lowL + (midL - effCut * midMid) + highL
        frameOut[1] = lowR + (midR - effCut * midMid) + highR
    }

    init {
        configure(DEFAULT_SAMPLE_RATE)
    }
}
