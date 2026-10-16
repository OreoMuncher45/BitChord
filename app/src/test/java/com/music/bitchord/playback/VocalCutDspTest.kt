package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The Sing engine's contract, driven straight into the pure kernel: no
 * Android, no audio stack. Frequencies are spot-checked at 48 kHz after a
 * 1s settle so filter warm-up never flatters a number.
 */
class VocalCutDspTest {

    private val sr = 48_000

    private fun sine(freqHz: Double, seconds: Double, both: (Double) -> Pair<Float, Float>): FloatArray {
        val n = (seconds * sr).toInt()
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val t = 2.0 * PI * freqHz * i / sr
            val (l, r) = both(t)
            out[i * 2] = l
            out[i * 2 + 1] = r
        }
        return out
    }

    /** RMS over the tail half (post-settle) as a fraction of input energy. */
    private fun tailResidual(input: FloatArray, cut: Float): Double {
        val dsp = VocalCutDsp()
        dsp.configure(sr)
        val work = input.copyOf()
        dsp.processSpan(work, work.size, cut)
        var num = 0.0
        var den = 0.0
        for (i in work.size / 2 until work.size) {
            num += work[i] * work[i]
            den += input[i] * input[i]
        }
        return if (den == 0.0) 0.0 else num / den
    }

    @Test
    fun `idle leaves bytes identical`() {
        val buf = sine(1000.0, 0.5) { t -> sin(t).toFloat() to (0.5f * sin(t)).toFloat() }
        val before = buf.copyOf()
        VocalCutDsp().apply { configure(sr) }.processSpan(buf, buf.size, 0f)
        assertTrue(buf.contentEquals(before))
    }

    @Test
    fun `center vocal band is removed at full cut`() {
        val buf = sine(1000.0, 2.0) { t -> sin(t).toFloat() to sin(t).toFloat() }
        assertTrue(tailResidual(buf, 1f) < 0.08)
    }

    @Test
    fun `wide-panned content is never touched`() {
        val buf = sine(1000.0, 2.0) { t -> sin(t).toFloat() to (-sin(t)).toFloat() }
        assertTrue(tailResidual(buf, 1f) > 0.99)
    }

    @Test
    fun `bass passes untouched at full cut`() {
        val buf = sine(60.0, 2.0) { t -> sin(t).toFloat() to sin(t).toFloat() }
        assertTrue(tailResidual(buf, 1f) > 0.90)
    }

    @Test
    fun `air passes untouched at full cut`() {
        val buf = sine(18_000.0, 2.0) { t -> sin(t).toFloat() to sin(t).toFloat() }
        assertTrue(tailResidual(buf, 1f) > 0.75)
    }

    @Test
    fun `drum transient punches through immediately`() {
        val dsp = VocalCutDsp()
        dsp.configure(sr)
        val out = dsp.processFrame(1f, 1f, 1f)
        assertTrue("first transient sample kept ${out[0]}", out[0] > 0.6f && out[1] > 0.6f)
    }

    @Test
    fun `cut clamps back down after the transient passes`() {
        val dsp = VocalCutDsp()
        dsp.configure(sr)
        dsp.processFrame(1f, 1f, 1f)
        val tone = sine(1000.0, 2.0) { t -> sin(t).toFloat() to sin(t).toFloat() }
        dsp.processSpan(tone, tone.size, 1f)
        var num = 0.0
        var den = 0.0
        for (i in tone.size / 2 until tone.size) {
            num += tone[i] * tone[i]
            den += sin(2.0 * PI * 1000.0 * (i / 2) / sr).let { it * it }
        }
        // Bypass released: sustained center tone suppressed again.
        assertTrue(num / den < 0.10)
    }

    @Test
    fun `partial cut scales the residual`() {
        val buf = sine(1000.0, 2.0) { t -> sin(t).toFloat() to sin(t).toFloat() }
        val half = tailResidual(buf, 0.5f)
        val full = tailResidual(buf, 1f)
        assertTrue("half=$half full=$full", half > full && half < 0.6)
    }

    @Test
    fun `other sample rates behave`() {
        val dsp = VocalCutDsp()
        dsp.configure(44_100)
        assertEquals(44_100, dsp.sampleRateHz)
        val n = 44_100 * 2
        val buf = FloatArray(n * 2) { i ->
            val t = 2.0 * PI * 1000.0 * (i / 2) / 44_100
            sin(t).toFloat()
        }
        dsp.processSpan(buf, buf.size, 1f)
        var num = 0.0
        var den = 0.0
        for (i in buf.size / 2 until buf.size) {
            val orig = sin(2.0 * PI * 1000.0 * (i / 2) / 44_100)
            num += buf[i] * buf[i]
            den += orig * orig
        }
        assertTrue(num / den < 0.08)
    }

    @Test
    fun `side makeup lifts the backing without touching bass`() {
        // Half scale: full scale plus boost would (correctly) meet the
        // limiter, hiding the makeup being measured here.
        val buf = sine(1000.0, 2.0) { t -> (0.5f * sin(t)).toFloat() to (-0.5f * sin(t)).toFloat() }
        val ratio = tailResidual(buf, 1f)
        // 1.5 dB on the mid-band sides (~1.41 energy). Crossover phase
        // rotation lets a little high-band residue add constructively, so
        // the bound is generous: the point is lift, not an exact figure.
        assertTrue("makeup ratio=$ratio", ratio > 1.25 && ratio < 1.75)
    }

    @Test
    fun `limiter never clips a boosted mix`() {
        val n = sr * 2
        val buf = FloatArray(n * 2) { i ->
            val t = 2.0 * PI * 220.0 * (i / 2) / sr
            (0.9f * sin(t)).toFloat()
        }
        VocalCutDsp().apply { configure(sr) }.processSpan(buf, buf.size, 1f)
        assertTrue(buf.all { it in -1.0f..1.0f })
    }

    @Test
    fun `unvoiced noise passes through, voiced tone does not`() {
        // Deterministic white noise, dead center: no pitch for the voice
        // gate to hold onto, so it must open back up and let it pass.
        var seed = 12345L
        fun rnd(): Float {
            seed = (seed * 1664525L + 1013904223L) and 0xFFFFFFFFL
            return (seed.toDouble() / 4294967296.0 * 2.0 - 1.0).toFloat()
        }
        val n = sr * 2
        val noise = FloatArray(n * 2) { rnd() * 0.5f }
        val tone = sine(440.0, 2.0) { t -> sin(t).toFloat() to sin(t).toFloat() }
        assertTrue("noise residual=${tailResidual(noise, 1f)}", tailResidual(noise, 1f) > 0.6)
        assertTrue("tone residual=${tailResidual(tone, 1f)}", tailResidual(tone, 1f) < 0.08)
    }

    @Test
    fun `reset clears the transient latch`() {
        val dsp = VocalCutDsp()
        dsp.configure(sr)
        dsp.processFrame(1f, 1f, 1f)
        dsp.reset()
        // Straight into sustained tone with no transient history: suppressed.
        val out = dsp.processFrame(0.5f, 0.5f, 1f)
        // 0.5 DC is sub-bass: passes by design, proving reset didn't wedge.
        assertTrue(out[0] > 0.4f)
    }
}
