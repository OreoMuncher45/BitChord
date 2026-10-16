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
 * Band-limited center cut with transient bypass (see [VocalCutDsp]): only
 * the mid-band a vocal lives in is ever cut, bass and air pass untouched,
 * and drum transients punch through unducked. Wide-panned instruments are
 * never touched at any setting. Thin shell over the pure kernel — all math
 * lives in [VocalCutDsp] where unit tests drive it without an audio stack.
 *
 * No makeup gain: removed energy stays removed, so bass and air (which
 * bypass the cut) never get doubled. At cut = 0 the kernel tracks filter
 * state but writes nothing, so a disabled Sing leaves the block
 * byte-identical — see [DspChain]'s exactness contract.
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

    private val dsp = VocalCutDsp()

    override fun configure(sampleRate: Int, channelCount: Int) {
        dsp.configure(sampleRate)
    }

    /**
     * Processes interleaved Float32 audio samples in [block] in-place.
     * Returns before touching a sample when idle or non-stereo, so a
     * disabled Sing — or a mono track — passes through byte-identical.
     */
    override fun process(block: AudioBlock) {
        if (block.channelCount != 2 || block.frameCount == 0) return
        dsp.processSpan(block.samples, block.frameCount * 2, level)
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
            dsp.configure(inputAudioFormat.sampleRate)
        }
        return inputAudioFormat
    }

    override fun onFlush() = dsp.reset()

    override fun onReset() = dsp.reset()

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
            val out = dsp.processFrame(left, right, cut)
            outputBuffer.putShort(PcmBoundary.clamp16FromFloat(out[0]))
            outputBuffer.putShort(PcmBoundary.clamp16FromFloat(out[1]))
        }
        outputBuffer.flip()
    }

    private companion object {
        const val BYTES_PER_FRAME = 4 // stereo, 16-bit
    }
}
