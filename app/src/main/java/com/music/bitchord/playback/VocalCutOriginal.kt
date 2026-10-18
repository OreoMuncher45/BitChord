package com.music.bitchord.playback

/**
 * The original Sing engine, restored verbatim in behaviour from v2.1.2
 * (`8c93099b`) — the first karaoke this fork shipped, before any filtering.
 *
 * `out = (in - cut · mid) · 1/(1 - cut/2)`, per frame, with no filter state at
 * all. What survives is the side signal plus half the mid, and the makeup gain
 * puts the volume back so full cut is not half as loud.
 *
 * It is here because it is genuinely better than its successors on some
 * material: no biquads means no phase shift, no ring, no latency, and a centre
 * mix comes apart cleanly. On a mix with a kick and a snare in the middle it
 * takes them with the vocal, which is why it was replaced. Both facts are the
 * argument for letting the listener choose.
 *
 * Pure math, no Android types, so the tests drive it directly.
 */
class VocalCutOriginal : SingEngine {

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48_000
    }

    var sampleRateHz: Int = DEFAULT_SAMPLE_RATE
        private set

    /** Reusable frame output — audio-thread confined, never allocated per call. */
    private val frameOut = FloatArray(2)

    override fun configure(sampleRate: Int) {
        sampleRateHz = sampleRate.coerceAtLeast(8_000)
    }

    override fun reset() = Unit

    override fun processSpan(samples: FloatArray, sampleCount: Int, cut: Float) {
        val active = cut > 0.001f
        val c = cut.coerceIn(0f, 1f)
        // Headroom is preserved rather than clamped: the makeup gain is what
        // brings a full cut back to a listenable level, and clamping first
        // would flat-top every peak the vocal was providing.
        val gain = makeupGain(c)
        var idx = 0
        while (idx < sampleCount) {
            val left = samples[idx]
            val right = samples[idx + 1]
            val mid = (left + right) * 0.5f
            val l = (left - c * mid) * gain
            val r = (right - c * mid) * gain
            if (active) {
                samples[idx] = l
                samples[idx + 1] = r
            }
            idx += 2
        }
    }

    override fun processFrame(left: Float, right: Float, cut: Float): FloatArray {
        val c = cut.coerceIn(0f, 1f)
        val mid = (left + right) * 0.5f
        val gain = makeupGain(c)
        frameOut[0] = (left - c * mid) * gain
        frameOut[1] = (right - c * mid) * gain
        return frameOut
    }

    /** 1.0 at no cut, rising to 2.0 at a full one. */
    private fun makeupGain(cut: Float): Float = 1f / (1f - 0.5f * cut)
}
