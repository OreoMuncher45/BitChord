package com.music.bitchord.playback.audio

import android.util.Log

/**
 * The native JamesDSP engine, as exposed by `libbitchord_jamesdsp.so`.
 *
 * Loaded lazily: simply constructing [JamesDspProcessor] must stay safe on
 * JVM unit-test hosts and on devices where the ABI was not packaged.
 */
internal object JamesDspNative {
    private const val TAG = "JamesDspNative"

    val available: Boolean by lazy {
        runCatching { System.loadLibrary("bitchord_jamesdsp") }
            .onFailure { Log.w(TAG, "JamesDSP native library unavailable", it) }
            .isSuccess
    }

    /** Warms class loading / dlopen before the audio thread needs either. */
    fun warmUp(): Boolean = available

    external fun nativeCreate(sampleRate: Int, maxBlockFrames: Int): Long
    external fun nativeDestroy(handle: Long)
    external fun nativeApply(
        handle: Long,
        bassEnabled: Boolean,
        bassGainDb: Float,
        stereoEnabled: Boolean,
        stereoLevel: Float,
        tubeEnabled: Boolean,
        tubeLevel: Float,
        reverbPreset: Int,
        preGainDb: Float,
        limiterEnabled: Boolean,
    )

    external fun nativeProcess(
        handle: Long,
        input: FloatArray,
        output: FloatArray,
        frames: Int,
    )
}

/**
 * One immutable snapshot of the JamesDSP controls.
 *
 * Passed from the settings collectors to [JamesDspProcessor] as a single
 * reference so the audio thread never observes half of an update. Defaults are
 * neutral: switching the engine on with no effect dialled in leaves the sound
 * alone apart from the limiter's final safety clamp.
 */
data class JamesDspConfig(
    val enabled: Boolean = false,
    /** 0 disables the bass boost; 15 dB is the engine's useful ceiling. */
    val bassGainDb: Float = 0f,
    /** 0 is off, 100 is the engine's maximum widening. */
    val stereoLevel: Float = 0f,
    /** 0 is off, 100 is the engine's maximum drive. */
    val tubeLevel: Float = 0f,
    /** -1 is off; otherwise an SF2 reverb preset index (0..18). */
    val reverbPreset: Int = -1,
    /** Pre-level trim in dB, applied before BitChord's loudness stage. */
    val preGainDb: Float = 0f,
    val limiterEnabled: Boolean = true,
) {
    fun sanitized(): JamesDspConfig = copy(
        bassGainDb = bassGainDb.coerceIn(0f, 15f),
        stereoLevel = stereoLevel.coerceIn(0f, 100f),
        tubeLevel = tubeLevel.coerceIn(0f, 100f),
        reverbPreset = if (reverbPreset in 0..18) reverbPreset else -1,
        preGainDb = preGainDb.coerceIn(-15f, 15f),
    )
}

/**
 * Runs JamesDSP inside BitChord's existing Float32 DSP chain.
 *
 * The engine is stereo interleaved. BitChord's music streams are stereo, so
 * the normal path is a straight copy in and out; mono is duplicated into both
 * channels and averaged back afterwards. Multichannel sources are left alone
 * rather than silently downmixed here — the output stage already owns that
 * decision.
 *
 * Every method that touches the native handle runs on the audio thread. The
 * settings side only publishes an immutable [JamesDspConfig], so there is no
 * lock and no chance of a control update tearing a buffer.
 */
class JamesDspProcessor : FloatAudioProcessor {
    @Volatile
    private var config: JamesDspConfig = JamesDspConfig()

    private var sampleRate: Int = 0
    private var channelCount: Int = 0
    private var engine: Long = 0L
    private var applied: JamesDspConfig? = null
    private var failed: Boolean = false
    private var framesSinceApply: Long = 0L
    private var scratchIn: FloatArray = FloatArray(0)
    private var scratchOut: FloatArray = FloatArray(0)

    /** Published by the settings collectors; the audio thread picks it up at a block boundary. */
    fun update(next: JamesDspConfig) {
        config = next.sanitized()
    }

    override fun configure(sampleRate: Int, channelCount: Int) {
        releaseEngine()
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        this.framesSinceApply = 0L
        if (channelCount in 1..2) {
            val frames = maxOf(4096, AudioBlock.DEFAULT_CAPACITY_FRAMES)
            scratchIn = FloatArray(frames * 2)
            scratchOut = FloatArray(frames * 2)
        } else {
            scratchIn = FloatArray(0)
            scratchOut = FloatArray(0)
        }
    }

    override fun process(block: AudioBlock) {
        val target = config
        if (!target.enabled) {
            // Dropping the handle on the first silent block after the switch
            // is turned off frees its reverb/convolver state rather than
            // leaving a stale tail to reappear next time it is enabled.
            releaseEngine()
            applied = null
            return
        }
        if (failed || block.frameCount <= 0 || block.channelCount !in 1..2) {
            return
        }
        if (sampleRate <= 0) return
        if (engine == 0L && !ensureEngine()) return

        framesSinceApply += block.frameCount
        val shouldApply = applied != target &&
            (applied == null || framesSinceApply >= (sampleRate / 20).coerceAtLeast(1))
        if (shouldApply) {
            JamesDspNative.nativeApply(
                engine,
                target.bassGainDb > 0f,
                target.bassGainDb,
                target.stereoLevel > 0f,
                target.stereoLevel,
                target.tubeLevel > 0f,
                target.tubeLevel,
                target.reverbPreset,
                target.preGainDb,
                target.limiterEnabled,
            )
            applied = target
            framesSinceApply = 0L
        }

        val frames = block.frameCount
        val samples = block.samples
        ensureScratch(frames)
        if (block.channelCount == 2) {
            System.arraycopy(samples, 0, scratchIn, 0, frames * 2)
            JamesDspNative.nativeProcess(engine, scratchIn, scratchOut, frames)
            System.arraycopy(scratchOut, 0, samples, 0, frames * 2)
        } else {
            var write = 0
            for (frame in 0 until frames) {
                val sample = samples[frame]
                scratchIn[write++] = sample
                scratchIn[write++] = sample
            }
            JamesDspNative.nativeProcess(engine, scratchIn, scratchOut, frames)
            for (frame in 0 until frames) {
                samples[frame] = (scratchOut[frame * 2] + scratchOut[frame * 2 + 1]) * 0.5f
            }
        }
    }

    override fun flush() {
        // There is no reset entry point in libjamesdsp. Rebuilding the handle
        // is the only way to drop a reverb/convolver tail at a seek; the next
        // process() call recreates it before touching a sample.
        releaseEngine()
        applied = null
    }

    override fun reset() {
        releaseEngine()
        applied = null
        failed = false
        framesSinceApply = 0L
        scratchIn = FloatArray(0)
        scratchOut = FloatArray(0)
        sampleRate = 0
        channelCount = 0
    }

    private fun ensureEngine(): Boolean {
        if (failed) return false
        if (!JamesDspNative.available) {
            failed = true
            Log.w(TAG, "JamesDSP enabled but native library is unavailable; bypassing")
            return false
        }
        engine = JamesDspNative.nativeCreate(
            sampleRate,
            maxOf(AudioBlock.DEFAULT_CAPACITY_FRAMES, 4096),
        )
        if (engine == 0L) {
            failed = true
            Log.w(TAG, "JamesDSP engine allocation failed; bypassing")
            return false
        }
        applied = null
        return true
    }

    private fun ensureScratch(frames: Int) {
        val needed = frames * 2
        if (scratchIn.size < needed) {
            // Should not happen in steady state: configure() sizes these for
            // the pipeline's 4096-frame capacity. Keep it correct if a future
            // sink hands over a larger block.
            scratchIn = FloatArray(needed)
            scratchOut = FloatArray(needed)
        }
    }

    private fun releaseEngine() {
        if (engine != 0L) {
            JamesDspNative.nativeDestroy(engine)
            engine = 0L
        }
    }

    private companion object {
        const val TAG = "JamesDspProcessor"
    }
}
