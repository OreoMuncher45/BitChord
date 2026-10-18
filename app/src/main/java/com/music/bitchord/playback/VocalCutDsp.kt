package com.music.bitchord.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Sing's vocal remover: band-limited center cut with transient bypass,
 * side makeup and a voice-activity gate.
 *
 * A plain full-spectrum L−R cut carves holes through everything panned
 * center — kick, bass, snare — because a vocal's harmonics share bins with
 * drums, synths and guitars all the way up the spectrum. This engine
 * instead layers four cheap mechanisms, each covering the others' blind
 * spot:
 *
 * - Split (Linkwitz-Riley 24 dB/oct at 150 Hz / 10 kHz): only the vocal
 *   mid-band is ever cut. Bass and air bypass untouched. The mid-band is
 *   a true bandpass (highpass the lowpassed top), never a subtraction
 *   residue: subtracting crossover outputs leaves phase-shifted ghosts
 *   across the whole spectrum, and cutting those thins bass that was
 *   supposed to be safe.
 * - Transient bypass: vocals sustain, drums spike. A fast-vs-slow
 *   envelope follower on the mid-band mono eases the cut out for the
 *   ~25 ms a drum crack lasts, proportionally rather than as a hard
 *   switch, then clamps back down.
 * - Side makeup (+1.5 dB, mid-band only): the removed vocal's energy is
 *   partially restored from the sides, which buries the residue and keeps
 *   the mix's weight. Bass sides stay flat so low end never gets heavy,
 *   and a soft limiter guarantees zero digital clipping from the boost.
 * - Voice gate: a downsampled autocorrelation detector watches for
 *   pitched (voiced) content in the mid-band. Instrumental passages with
 *   no singer pass through untouched instead of being hollowed for no
 *   reason. No microphone involved — it reads our own playback stream.
 *
 * Pure math, no Android types, so unit tests drive it directly. O(~200)
 * flops per sample plus one ~40k-mult analysis every 256 samples, no
 * allocation on the audio thread. Sample rate is set by [configure] and
 * defaults to 48 kHz before the sink reports it.
 */
class VocalCutDsp : SingEngine {

    companion object {
        const val LOW_CUTOFF_HZ = 150.0
        const val HIGH_CUTOFF_HZ = 10_000.0
        // Butterworth stages (Q = 1/sqrt(2)): two cascaded make a
        // Linkwitz-Riley 24 dB/oct split, -6 dB at the cutoff, in phase.
        const val FILTER_Q = 0.70710678

        /** Fast envelope follows ~1ms attacks, slow one ~60ms of sustain. */
        const val ENV_FAST_MS = 1.0
        const val ENV_SLOW_MS = 60.0

        /** Bypass slams shut in ~0.5ms and eases back over ~25ms. */
        const val BYPASS_ATTACK_MS = 0.5
        const val BYPASS_RELEASE_MS = 25.0

        /** Envelope ratio where a drum hit starts opening the bypass. */
        const val TRANSIENT_RATIO = 3.0f

        /** Bypass opens proportionally past the ratio (Gemini's 0.45 slope). */
        const val TRANSIENT_SLOPE = 0.5f

        /** Below this mid-band level nothing counts as a transient (hiss-safe). */
        const val NOISE_FLOOR = 0.004f

        /** Side makeup in dB, mid-band sides only — bass sides stay flat. */
        const val SIDE_MAKEUP_DB = 1.5

        /** Soft-limiter knee: linear below, tanh-blended to ±1 above. */
        const val LIMIT_KNEE = 0.9f

        /** Voice detector: 4× decimation, 256-sample window, 68–666 Hz pitch. */
        const val VAD_DECIM = 4
        const val VAD_WIN = 256
        const val VAD_MIN_LAG = 18
        const val VAD_MAX_LAG = 176
        const val VAD_HOP = 256

        /** Voicing opens fast (phrase starts) and hangs ~400ms (bridges gaps). */
        const val VAD_ATTACK_MS = 50.0
        const val VAD_RELEASE_MS = 400.0

        /** Below this autocorrelation peak nothing reads as voiced. */
        const val VAD_VOICED_FLOOR = 0.35

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
    private var e0 = 0f
    private var e1 = 0f
    private var e2 = 0f
    private var f1 = 0f
    private var f2 = 0f

    // Two cascaded biquad stages per split (Linkwitz-Riley 24 dB/oct).
    private val low1Ls = FloatArray(4)
    private val low2Ls = FloatArray(4)
    private val low1Rs = FloatArray(4)
    private val low2Rs = FloatArray(4)
    private val high1Ls = FloatArray(4)
    private val high2Ls = FloatArray(4)
    private val high1Rs = FloatArray(4)
    private val high2Rs = FloatArray(4)
    private val mid1Ls = FloatArray(4)
    private val mid2Ls = FloatArray(4)
    private val mid1Rs = FloatArray(4)
    private val mid2Rs = FloatArray(4)

    private var envFast = 0f
    private var envSlow = 0f
    private var bypass = 0f
    private var voiceGate = 1f

    private var envFastA = 0f
    private var envSlowA = 0f
    private var bypassAttackA = 0f
    private var bypassReleaseA = 0f
    private var vadAttackA = 0f
    private var vadReleaseA = 0f
    private var makeup = 1f

    // Voice detector: decimated mid-mono ring (window + max lag of history).
    private val vadRing = FloatArray(VAD_WIN + VAD_MAX_LAG)
    private var vadPos = 0
    private var vadDecimCount = 0
    private var vadHopCount = 0
    private var vadFilled = 0

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
        computeHighpass(LOW_CUTOFF_HZ / sr).let { (nb0, nb1, nb2, na1, na2) ->
            e0 = nb0.toFloat(); e1 = nb1.toFloat(); e2 = nb2.toFloat()
            f1 = na1.toFloat(); f2 = na2.toFloat()
        }
        envFastA = timeConst(ENV_FAST_MS, sr).toFloat()
        envSlowA = timeConst(ENV_SLOW_MS, sr).toFloat()
        bypassAttackA = timeConst(BYPASS_ATTACK_MS, sr).toFloat()
        bypassReleaseA = timeConst(BYPASS_RELEASE_MS, sr).toFloat()
        val hopSec = VAD_HOP / sr
        vadAttackA = (1.0 - exp(-hopSec / (VAD_ATTACK_MS / 1000.0))).toFloat()
        vadReleaseA = (1.0 - exp(-hopSec / (VAD_RELEASE_MS / 1000.0))).toFloat()
        makeup = 10.0.pow(SIDE_MAKEUP_DB / 20.0).toFloat()
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

    private fun computeHighpass(normalized: Double): DoubleArray {
        val w0 = 2.0 * PI * normalized.coerceIn(0.0001, 0.4999)
        val alpha = sin(w0) / (2.0 * FILTER_Q)
        val a0inv = 1.0 / (1.0 + alpha)
        return doubleArrayOf(
            ((1.0 + cos(w0)) / 2.0) * a0inv,
            (-(1.0 + cos(w0))) * a0inv,
            ((1.0 + cos(w0)) / 2.0) * a0inv,
            (-2.0 * cos(w0)) * a0inv,
            (1.0 - alpha) * a0inv,
        )
    }

    private fun timeConst(ms: Double, sr: Double): Double = 1.0 - exp(-1.0 / (ms / 1000.0 * sr))

    override fun reset() {
        low1Ls.fill(0f); low2Ls.fill(0f); low1Rs.fill(0f); low2Rs.fill(0f)
        high1Ls.fill(0f); high2Ls.fill(0f); high1Rs.fill(0f); high2Rs.fill(0f)
        mid1Ls.fill(0f); mid2Ls.fill(0f); mid1Rs.fill(0f); mid2Rs.fill(0f)
        envFast = 0f; envSlow = 0f; bypass = 0f
        // Fail open: a fresh stream cuts until proven instrumental, never
        // the reverse — a verse that starts before detection settles still
        // loses its vocal rather than leaking it.
        voiceGate = 1f
        vadRing.fill(0f); vadPos = 0; vadDecimCount = 0; vadHopCount = 0; vadFilled = 0
    }

    /**
     * Processes interleaved stereo Float32 samples in place.
     *
     * Filter memory, envelopes and the voice gate track every call, but
     * nothing is written while [cut] is idle — a disabled Sing leaves the
     * block byte-identical.
     */
    override fun processSpan(samples: FloatArray, sampleCount: Int, cut: Float) {
        val active = cut > 0.001f
        val c = cut.coerceIn(0f, 1f)
        var idx = 0
        while (idx < sampleCount) {
            step(samples[idx], samples[idx + 1], if (active) c else 0f, active)
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

    /** Linear below the knee, tanh-blended to ±1 above. Zero coloration under it. */
    private fun softLimit(x: Float): Float {
        val a = abs(x)
        if (a <= LIMIT_KNEE) return x
        val over = (a - LIMIT_KNEE) / (1f - LIMIT_KNEE)
        return (if (x < 0f) -1f else 1f) * (LIMIT_KNEE + (1f - LIMIT_KNEE) * tanh(over))
    }

    private fun step(l: Float, r: Float, cut: Float, active: Boolean) {
        val lowL = lowpass(lowpass(l, b0, b1, b2, a1, a2, low1Ls), b0, b1, b2, a1, a2, low2Ls)
        val lowR = lowpass(lowpass(r, b0, b1, b2, a1, a2, low1Rs), b0, b1, b2, a1, a2, low2Rs)
        val topL = lowpass(lowpass(l, c0, c1, c2, d1, d2, high1Ls), c0, c1, c2, d1, d2, high2Ls)
        val topR = lowpass(lowpass(r, c0, c1, c2, d1, d2, high1Rs), c0, c1, c2, d1, d2, high2Rs)
        val highL = l - topL
        val highR = r - topR
        val midL = lowpass(lowpass(topL, e0, e1, e2, f1, f2, mid1Ls), e0, e1, e2, f1, f2, mid2Ls)
        val midR = lowpass(lowpass(topR, e0, e1, e2, f1, f2, mid1Rs), e0, e1, e2, f1, f2, mid2Rs)
        val midMid = (midL + midR) * 0.5f

        // Transient hunt on the mid-band mono: drums spike, vowels glide.
        // Proportional past the ratio (not a hard switch) so the rescue
        // swells instead of pumping.
        val mag = abs(midMid)
        envFast += envFastA * (mag - envFast)
        envSlow += envSlowA * (mag - envSlow)
        val ratio = if (envSlow > NOISE_FLOOR) envFast / envSlow else 0f
        val target = ((ratio - TRANSIENT_RATIO) * TRANSIENT_SLOPE).coerceIn(0f, 1f)
        val smooth = if (target > bypass) bypassAttackA else bypassReleaseA
        bypass += smooth * (target - bypass)

        updateVoiceGate(midMid)

        if (!active || voiceGate < 0.05f) {
            // Instrumental (or idle): nothing to remove, nothing to
            // compensate — the input passes through as read.
            frameOut[0] = l
            frameOut[1] = r
            return
        }
        val effCut = cut * (1f - bypass) * voiceGate
        // Side makeup on the mid-band sides only: bass and air sides live
        // in `low`/`high` and stay flat, so low end never gets heavy and
        // the top never harshens. Pure center sees none of it (mid == midM);
        // pure side sees all of it.
        val comp = makeup - 1f
        frameOut[0] = softLimit(lowL + highL + (midL - effCut * midMid) + comp * (midL - midMid))
        frameOut[1] = softLimit(lowR + highR + (midR - effCut * midMid) + comp * (midR - midMid))
    }

    /**
     * Voice-activity gate on the mid-band mono: normalized autocorrelation
     * peak over 68–666 Hz lags. Sustained pitch reads near 1, noise and
     * silence near 0. Runs on 4×-decimated history every 256 samples.
     */
    private fun updateVoiceGate(midMid: Float) {
        vadDecimCount++
        if (vadDecimCount >= VAD_DECIM) {
            vadDecimCount = 0
            vadRing[vadPos] = midMid
            vadPos = (vadPos + 1) % vadRing.size
            if (vadFilled < vadRing.size) vadFilled++
        } else {
            return
        }
        vadHopCount++
        if (vadHopCount < VAD_HOP / VAD_DECIM || vadFilled < vadRing.size) return
        vadHopCount = 0
        var r0 = 0.0
        for (i in 0 until VAD_WIN) {
            val x = vadRing[(vadPos - 1 - i + vadRing.size * 2) % vadRing.size].toDouble()
            r0 += x * x
        }
        if (r0 < 1e-9) return // Silence holds the gate — nothing to cut anyway.
        var peak = 0.0
        for (lag in VAD_MIN_LAG..VAD_MAX_LAG) {
            var r = 0.0
            for (i in 0 until VAD_WIN) {
                val a = vadRing[(vadPos - 1 - i + vadRing.size * 2) % vadRing.size].toDouble()
                val b = vadRing[(vadPos - 1 - i - lag + vadRing.size * 3) % vadRing.size].toDouble()
                r += a * b
            }
            val norm = r / r0
            if (norm > peak) peak = norm
        }
        val target = ((peak - VAD_VOICED_FLOOR) / (1.0 - VAD_VOICED_FLOOR)).coerceIn(0.0, 1.0).toFloat()
        val smooth = if (target > voiceGate) vadAttackA else vadReleaseA
        voiceGate += smooth * (target - voiceGate)
    }

    init {
        configure(DEFAULT_SAMPLE_RATE)
    }
}
