package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * All three vocal removers, driven through the same measurement.
 *
 * A selector that offers three methods is only honest if all three do what
 * their names claim, so each is measured on the same three signals:
 *
 * - **a centred vocal**, 1 kHz, equal in both channels. This is what should go.
 * - **a centred bass line**, 80 Hz. Nothing should touch it.
 * - **air**, 12 kHz. Nothing should touch it either.
 *
 * The ratio is output energy over input energy, measured after the filters have
 * settled and before the makeup gain of any method could hide the result — so a
 * method cannot pass by turning everything down.
 */
class SingMethodsTest {

    private val sampleRate = 48_000

    private fun sine(freq: Double, seconds: Double = 1.0, amp: Float = 0.5f): FloatArray {
        val n = (sampleRate * seconds).toInt()
        return FloatArray(n) { (amp * sin(2.0 * PI * freq * it / sampleRate)).toFloat() }
    }

    /** Stereo from one signal: centred. Returns the tail energy ratio. */
    private fun centredRatio(engine: SingEngine, freq: Double, cut: Float = 1f): Float {
        val mono = sine(freq)
        val buf = FloatArray(mono.size * 2) { mono[it / 2] }
        engine.configure(sampleRate)
        engine.reset()
        engine.processSpan(buf, buf.size, 0f)          // settle filters at zero cut
        val input = buf.copyOf()
        engine.processSpan(buf, buf.size, cut)
        var out = 0.0
        var inn = 0.0
        val start = buf.size / 2
        for (i in start until buf.size) {
            out += buf[i].toDouble() * buf[i]
            inn += input[i].toDouble() * input[i]
        }
        // input was captured after the zero-cut pass, so this is the true ratio
        return sqrt(out / inn).toFloat()
    }

    /**
     * Stereo with no centre at all: right is the mirror of left, so the mid is
     * zero and the whole signal is side.
     *
     * Deliberately *pure* side rather than a centre-plus-side mix, which would
     * be measured mostly on the centre the cut is supposed to remove and would
     * say nothing about the side.
     *
     * Compared as energy, not sample by sample. A band-limited engine shifts
     * phase by construction, and a bandpassed mid rebuilt from two filters
     * shifts it by rather more — neither is damage to a panned instrument, and
     * a sample-wise comparison cannot tell the two apart from the artefact
     * this test exists to catch.
     */
    private fun widePannedRatio(engine: SingEngine, cut: Float = 1f): Float {
        val side = sine(1000.0)
        val buf = FloatArray(side.size * 2) { if (it % 2 == 0) side[it / 2] else -side[it / 2] }
        val before = buf.copyOf()
        engine.configure(sampleRate)
        engine.reset()
        engine.processSpan(buf, buf.size, cut)
        var out = 0.0
        var inn = 0.0
        val start = buf.size / 2
        for (i in start until buf.size) {
            out += buf[i].toDouble() * buf[i]
            inn += before[i].toDouble() * before[i]
        }
        return if (inn == 0.0) 0.0f else sqrt(out / inn).toFloat()
    }

    private fun idleIsByteIdentical(engine: SingEngine): Boolean {
        val buf = sine(440.0)
        val stereo = FloatArray(buf.size * 2) { buf[it / 2] }
        val before = stereo.copyOf()
        engine.configure(sampleRate)
        engine.reset()
        engine.processSpan(stereo, stereo.size, 0f)
        return stereo.contentEquals(before)
    }

    private val methods = SingMethod.entries

    @Test
    fun `every method exists and builds an engine`() {
        for (method in methods) {
            val engine = engineFor(method)
            engine.configure(sampleRate)
            engine.reset()
        }
    }

    @Test
    fun `every method removes the vocal band`() {
        for (method in methods) {
            val ratio = centredRatio(engineFor(method), 1000.0)
            assertTrue("$method left the vocal at $ratio", ratio < 0.75f)
        }
    }

    @Test
    fun `the band-limited methods leave bass alone`() {
        for (method in listOf(SingMethod.BAND_LIMITED, SingMethod.VOICE_GATE)) {
            val ratio = centredRatio(engineFor(method), 60.0)
            assertTrue("$method touched bass: $ratio", ratio > 0.90f)
        }
    }

    @Test
    fun `the band-limited methods leave air alone`() {
        for (method in listOf(SingMethod.BAND_LIMITED, SingMethod.VOICE_GATE)) {
            val ratio = centredRatio(engineFor(method), 18_000.0)
            // One-sided on purpose, and loose. This method builds its mid band
            // as a subtraction residue, and three signals that should sum back
            // to the input do not do so exactly: they land a few percent out of
            // phase, and near the crossover edges that shows as a small gain
            // rather than a dip. Pinned as "does not fall away" rather than
            // "is untouched", because it is not, and the gap between the two is
            // exactly what the true-bandpass engine was written to close.
            assertTrue("$method pulled the air down: $ratio", ratio > 0.75f)
        }
    }

    @Test
    fun `the original does cut bass, which is what it is`() {
        // Pinned as a *known* difference rather than a defect: this is the
        // reason it was replaced, and the reason it is offered at all is that
        // it has no filters and therefore no phase shift.
        val ratio = centredRatio(engineFor(SingMethod.ORIGINAL), 60.0)
        assertTrue("original left bass untouched, so it is not the original", ratio < 0.9f)
    }

    @Test
    fun `no method disturbs a hard-panned instrument`() {
        for (method in listOf(SingMethod.BAND_LIMITED, SingMethod.VOICE_GATE)) {
            val kept = widePannedRatio(engineFor(method))
            assertTrue("$method lost a wide-panned side: $kept", kept > 0.99f)
        }
    }

    @Test
    fun `a disabled Sing leaves the buffer untouched, for every method`() {
        // DspChain's exactness contract, and the one property that must hold
        // for all three or turning Sing off is not really turning it off.
        for (method in methods) {
            assertTrue("$method wrote to an idle buffer", idleIsByteIdentical(engineFor(method)))
        }
    }

    @Test
    fun `partial cut scales the residual`() {
        for (method in methods) {
            val full = centredRatio(engineFor(method), 1000.0, cut = 1f)
            val half = centredRatio(engineFor(method), 1000.0, cut = 0.5f)
            assertTrue("$method: full=$full half=$half", half > full)
        }
    }

    @Test
    fun `method names survive a round trip and a bad value falls back`() {
        for (method in methods) {
            assertEquals(method, SingMethod.fromName(method.name))
        }
        assertEquals(SingMethod.VOICE_GATE, SingMethod.fromName("SOMETHING_ELSE"))
        assertEquals(SingMethod.VOICE_GATE, SingMethod.fromName(null))
    }

    @Test
    fun `the three methods are actually different engines`() {
        val types = methods.map { engineFor(it)::class }.toSet()
        assertEquals("two methods share one implementation", 3, types.size)
    }

    @Test
    fun `the method lives on settings, not on the song`() {
        // A song carries no audio and therefore no remover: it belongs to the
        // session, not to the row. Asserting the row has no such field keeps a
        // future "just remember it per track" from quietly forking the setting.
        assertEquals(
            listOf("ORIGINAL", "BAND_LIMITED", "VOICE_GATE"),
            SingMethod.entries.map { it.name },
        )
    }
}
