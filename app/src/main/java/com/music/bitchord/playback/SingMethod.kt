package com.music.bitchord.playback

/**
 * The vocal removers Sing has shipped, kept side by side because they fail in
 * opposite directions and there is no single right answer for every record.
 *
 * All three read the same slider; they differ in what they refuse to damage.
 */
enum class SingMethod {
    /**
     * The original, restored from v2.1.2 (`8c93099b`).
     *
     * Full-spectrum centre cut with makeup gain — the hardware karaoke box, and
     * the fastest of the three: nothing is filtered, so there is no phase shift
     * and no ring. It also cuts everything panned centre, which on a modern
     * mix means the kick, the bass and the snare. Correct for a track recorded
     * like 1975, muddy for anything with a drum kit in it.
     */
    ORIGINAL,

    /**
     * Band-limited cut with transient bypass, as shipped in v2.1.11
     * (`7b146fe6`).
     *
     * Cues only the 150 Hz–10 kHz band and ducks out of the way for drum
     * transients, so bass and air survive. The mid band is computed as a
     * subtraction residue rather than a true bandpass, which leaves faint phase
     * ghosts across the band — audible as a slight thinning, and the reason the
     * next method exists.
     */
    BAND_LIMITED,

    /**
     * The current engine, since v2.1.12 (`f8ae56d8`).
     *
     * The same band-limited split with the mid rebuilt as a real bandpass, a
     * gradual crest trigger instead of a hard switch, a voice-activity gate so
     * instrumental passages are left alone, and side makeup to replace the
     * energy the cut removes. The most moving parts, and the only one of the
     * three that lets a solo through untouched.
     */
    VOICE_GATE,
    ;

    companion object {
        fun fromName(value: String?): SingMethod =
            entries.firstOrNull { it.name == value } ?: VOICE_GATE
    }
}

/** The one shape all three engines answer to, so the processor can swap them. */
interface SingEngine {
    fun configure(sampleRate: Int)
    fun processSpan(samples: FloatArray, sampleCount: Int, cut: Float)
    fun processFrame(left: Float, right: Float, cut: Float): FloatArray
    fun reset()
}

internal fun engineFor(method: SingMethod): SingEngine = when (method) {
    SingMethod.ORIGINAL -> VocalCutOriginal()
    SingMethod.BAND_LIMITED -> VocalCutBandLimited()
    SingMethod.VOICE_GATE -> VocalCutDsp()
}
