package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.audio.AudioBlock
import com.music.bitchord.playback.audio.FloatAudioProcessor
import com.music.bitchord.playback.audio.PcmBoundary
import java.nio.ByteOrder

/**
 * Apple Music Sing equivalent: turns the lead vocal down (or off) so the
 * listener can sing over the track.
 *
 * Three engines, one slider — see [SingMethod] for what each one is for and
 * which kind of mix it suits. This is a thin shell over whichever pure kernel
 * is selected, so all the math stays testable without an audio stack.
 *
 * At cut = 0 every engine tracks its own state but writes nothing, so a
 * disabled Sing leaves the block byte-identical — see [DspChain]'s exactness
 * contract. That holds for all three, including [VocalCutOriginal], whose
 * makeup gain is unity at zero cut.
 */
@UnstableApi
class VocalCutProcessor : BaseAudioProcessor(), FloatAudioProcessor {

    /**
     * How much of the vocal band to remove, 0..1. Written from the UI thread via
     * [AppSettings.singLevel][com.music.bitchord.data.settings.AppSettings.singLevel];
     * read on the audio thread, hence volatile.
     */
    @Volatile
    var level: Float = 0f

    /**
     * Which remover is running. Swapped from the UI thread via [setMethod],
     * read on the audio thread, hence volatile.
     *
     * The three engines are kept alive one at a time rather than all three at
     * once: each carries filter memory sized for its own band, and holding
     * three of them per player to switch between costs memory nobody asked for.
     */
    @Volatile
    private var engine: SingEngine = engineFor(AppSettings.singMethod.value)

    private var configuredSampleRate: Int = VocalCutDsp.DEFAULT_SAMPLE_RATE

    /**
     * Switch engines, configured for the rate this processor is already
     * running at and reset so no filter memory from the last method leaks into
     * the first frame of the new one.
     *
     * Called from the player thread rather than from inside [process], so the
     * swap happens between callbacks and never mid-buffer.
     */
    fun setMethod(method: SingMethod) {
        if (method == currentMethod()) return
        engine = engineFor(method).apply {
            configure(configuredSampleRate)
            reset()
        }
    }

    private fun currentMethod(): SingMethod = when (engine) {
        is VocalCutOriginal -> SingMethod.ORIGINAL
        is VocalCutBandLimited -> SingMethod.BAND_LIMITED
        else -> SingMethod.VOICE_GATE
    }

    override fun configure(sampleRate: Int, channelCount: Int) {
        configuredSampleRate = sampleRate
        engine.configure(sampleRate)
    }

    /**
     * Processes interleaved Float32 audio samples in [block] in-place.
     * Returns before touching a sample when idle or non-stereo, so a
     * disabled Sing — or a mono track — passes through byte-identical.
     */
    override fun process(block: AudioBlock) {
        if (block.channelCount != 2 || block.frameCount == 0) return
        engine.processSpan(block.samples, block.frameCount * 2, level)
    }

    /**
     * Stereo 16-bit only, for the same reason as
     * [SpatialAudioProcessor.onConfigure]: bowing out with NOT_SET keeps
     * mono and multichannel tracks playable instead of failing the sink.
     */
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        if (inputAudioFormat.sampleRate > 0) {
            configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        }
        return inputAudioFormat
    }

    override fun onFlush() = engine.reset()

    override fun onReset() = engine.reset()

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val frameCount = inputBuffer.remaining() / BYTES_PER_FRAME
        if (frameCount == 0) return
        val outputBuffer = replaceOutputBuffer(frameCount * BYTES_PER_FRAME)

        val cut = level
        if (cut <= 0.001f) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())

        val invScale = 1.0f / 32768.0f
        repeat(frameCount) {
            val left = inputBuffer.short.toFloat() * invScale
            val right = inputBuffer.short.toFloat() * invScale
            val out = engine.processFrame(left, right, cut)
            outputBuffer.putShort(PcmBoundary.clamp16FromFloat(out[0]))
            outputBuffer.putShort(PcmBoundary.clamp16FromFloat(out[1]))
        }
        outputBuffer.flip()
    }

    private companion object {
        const val BYTES_PER_FRAME = 4 // stereo, 16-bit
    }
}
