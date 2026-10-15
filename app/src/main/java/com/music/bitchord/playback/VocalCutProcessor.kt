package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.playback.audio.AudioBlock
import com.music.bitchord.playback.audio.FloatAudioProcessor
import com.music.bitchord.playback.audio.PcmBoundary
import java.nio.ByteOrder

/**
 * Apple Music Sing equivalent: turns the lead vocal down (or off) so the
 * listener can sing over the track.
 *
 * The classic center-cut karaoke trick, since lead vocals are near-universally
 * mixed dead center: subtract a share of the mid signal from both channels.
 * At full cut each channel keeps only its side component — the part of the
 * mix panned away from center — which is exactly what the old hardware
 * karaoke boxes did. O(1) per sample, stateless, no FFT or stem separation.
 *
 * Makeup gain preserves the side energy so full cut does not halve the
 * volume: out = (in - cut * mid) / (1 - cut / 2). At cut = 0 the gain is 1,
 * and the processor returns before reading a sample anyway — see [process].
 */
@UnstableApi
class VocalCutProcessor : BaseAudioProcessor(), FloatAudioProcessor {

    /**
     * How much of the center to remove, 0..1. Written from the UI thread via
     * [AppSettings.singLevel][com.music.bitchord.data.settings.AppSettings.singLevel];
     * read on the audio thread, hence volatile.
     */
    @Volatile
    var level: Float = 0f

    private val active: Boolean get() = level > 0.001f

    override fun configure(sampleRate: Int, channelCount: Int) {
        // Stateless: nothing to allocate. Kept for the [FloatAudioProcessor]
        // contract and so [DspChain] can forward uniformly.
    }

    /**
     * Processes interleaved Float32 audio samples in [block] in-place.
     * Returns before reading a sample when idle, so a disabled Sing leaves
     * the block byte-identical — see [DspChain]'s exactness contract.
     */
    override fun process(block: AudioBlock) {
        val cut = level
        if (cut <= 0.001f || block.frameCount == 0) return
        if (block.channelCount != 2) return // Center means nothing outside stereo
        cutCenter(block.samples, block.frameCount * 2, cut)
    }

    /**
     * The shared kernel, pure over the array span so unit tests can drive it
     * without an [AudioBlock].
     */
    fun cutCenter(samples: FloatArray, sampleCount: Int, cut: Float) {
        val gain = 1f / (1f - 0.5f * cut.coerceIn(0f, 1f))
        var idx = 0
        while (idx < sampleCount) {
            val left = samples[idx]
            val right = samples[idx + 1]
            val mid = (left + right) * 0.5f
            // Headroom is preserved: no clamping to [-1.0f, +1.0f]
            samples[idx] = (left - cut * mid) * gain
            samples[idx + 1] = (right - cut * mid) * gain
            idx += 2
        }
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
        return inputAudioFormat
    }

    override fun onFlush() = Unit

    override fun onReset() = Unit

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

        val gain = 1f / (1f - 0.5f * cut.coerceIn(0f, 1f))
        val invScale = 1.0f / 32768.0f
        repeat(frameCount) {
            val left = inputBuffer.short.toFloat() * invScale
            val right = inputBuffer.short.toFloat() * invScale
            val mid = (left + right) * 0.5f
            outputBuffer.putShort(PcmBoundary.clamp16FromFloat((left - cut * mid) * gain))
            outputBuffer.putShort(PcmBoundary.clamp16FromFloat((right - cut * mid) * gain))
        }
        outputBuffer.flip()
    }

    private companion object {
        const val BYTES_PER_FRAME = 4 // stereo, 16-bit
    }
}
