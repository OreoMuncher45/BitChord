package com.music.bitchord.playback.audio

import android.util.Log
import com.music.bitchord.BuildConfig
import com.music.bitchord.playback.EqualizerProcessor
import com.music.bitchord.playback.LoudnessProcessor
import com.music.bitchord.playback.SpatialAudioProcessor
import com.music.bitchord.playback.TransitionFilterProcessor
import com.music.bitchord.playback.VocalCutProcessor

/**
 * Composite DSP chain executing BitChord's custom audio processors in their canonical sequence:
 *
 * AudioBlock(Float32) -> VocalCutProcessor -> SpatialAudioProcessor -> EqualizerProcessor
 *   -> JamesDspProcessor -> TransitionFilterProcessor -> LoudnessProcessor -> AudioBlock(Float32)
 *
 * Sing's vocal cut runs first: it removes the center the other stages would
 * otherwise widen, equalise and level along with everything else.
 *
 * JamesDSP runs after the equaliser and before the per-track transition and
 * loudness stages. It is a tone/space stage here, not a second output chain:
 * its own limiter protects the boosts it applies, then LoudnessProcessor has
 * the last word on level. It is bypassed entirely when switched off.
 *
 * Operates purely on in-place Float32 audio blocks without intermediate fixed-point quantization,
 * preserving full dynamic range and headroom.
 *
 * Loudness normalization is the last stage, and per sink on purpose: each
 * player levels the track *it* is playing, so the two sides of a crossfade are
 * each levelled for their own song — see [LoudnessProcessor]. Last because its
 * limiter has to see the signal every other stage has finished shaping.
 *
 * ## Staying out of the way
 *
 * Every stage here self-bypasses when its own setting is off, returning before
 * it reads a single sample rather than multiplying through by unity. With all
 * three idle the block leaves this class byte-identical to how it arrived,
 * which is what lets [PcmBoundary]'s power-of-two scaling round-trip 16- and
 * 24-bit integers unchanged — see `PrecisionAudioSink.publishOutputExactness`.
 */
class DspChain(
    val sing: VocalCutProcessor = VocalCutProcessor(),
    val spatial: SpatialAudioProcessor = SpatialAudioProcessor(),
    val equalizer: EqualizerProcessor = EqualizerProcessor(),
    val jamesDsp: JamesDspProcessor = JamesDspProcessor(),
    val transition: TransitionFilterProcessor = TransitionFilterProcessor(),
    val loudness: LoudnessProcessor = LoudnessProcessor(),
) : FloatAudioProcessor {

    private var currentSampleRate: Int = 0
    private var processCounter: Long = 0L

    override fun configure(sampleRate: Int, channelCount: Int) {
        this.currentSampleRate = sampleRate
        sing.configure(sampleRate, channelCount)
        spatial.configure(sampleRate, channelCount)
        equalizer.configure(sampleRate, channelCount)
        jamesDsp.configure(sampleRate, channelCount)
        transition.configure(sampleRate, channelCount)
        loudness.configure(sampleRate, channelCount)
    }

    /** A new track has begun gaplessly on this sink; see [LoudnessProcessor.onStreamBoundary]. */
    fun onStreamBoundary() {
        loudness.onStreamBoundary()
    }

    override fun process(block: AudioBlock) {
        if (block.frameCount == 0) return

        if (BuildConfig.DEBUG) {
            processCounter++
            if (processCounter == 1L || processCounter % 500L == 0L) {
                try {
                    val count = processCounter
                    val frames = block.frameCount
                    val sr = currentSampleRate
                    val spatialOn = spatial.enabled
                    val eqOn = equalizer.isEnabled
                    val transitionOn = transition.isFiltering
                    Log.d(
                        TAG,
                        "process() #$count frames=$frames sr=$sr " +
                            "sing=${sing.level} spatial=$spatialOn eq=$eqOn transition=$transitionOn",
                    )
                } catch (_: Throwable) {
                }
            }
        }

        sing.process(block)
        spatial.process(block)
        equalizer.process(block)
        jamesDsp.process(block)
        transition.process(block)
        loudness.process(block)
    }

    @Suppress("DEPRECATION")
    override fun flush() {
        sing.flush()
        spatial.flush()
        equalizer.flush()
        jamesDsp.flush()
        transition.flush()
        loudness.flush()
    }

    override fun reset() {
        processCounter = 0L
        sing.reset()
        spatial.reset()
        equalizer.reset()
        jamesDsp.reset()
        transition.reset()
        loudness.reset()
    }

    companion object {
        private const val TAG = "DspChain"
    }
}
