package com.music.bitchord

import android.media.AudioDeviceInfo
import com.music.bitchord.data.sources.addon.AddonManifest
import com.music.bitchord.playback.audio.LosslessOutput
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two manifest switches an addon can set — `allowDownloads` and
 * `checkValidLossless` — and the output test the second one is gated on.
 */
class AddonPolicyTest {

    // Configured like the client's own, which is the one that reads manifests.
    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private fun manifest(extra: String) =
        json.decodeFromString(AddonManifest.serializer(), """{"id":"x","name":"X","resources":["search","stream"]$extra}""")

    @Test
    fun `missing keys default to downloads allowed and no output check`() {
        val m = manifest("")
        assertTrue(m.downloadsAllowed)
        assertFalse(m.requiresLosslessOutput)
    }

    @Test
    fun `numbers booleans and strings are all read`() {
        for (off in listOf("0", "false", "\"0\"", "\"false\"")) {
            assertFalse(off, manifest(""","allowDownloads":$off""").downloadsAllowed)
        }
        for (on in listOf("1", "true", "\"1\"", "\"true\"")) {
            assertTrue(on, manifest(""","checkValidLossless":$on""").requiresLosslessOutput)
        }
    }

    @Test
    fun `an unreadable value falls back to the default rather than flipping`() {
        val m = manifest(""","allowDownloads":"sometimes","checkValidLossless":null""")
        assertTrue(m.downloadsAllowed)
        assertFalse(m.requiresLosslessOutput)
    }

    @Test
    fun `cables qualify whatever bluetooth is doing`() {
        for (type in listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
        )) {
            assertTrue("$type", LosslessOutput.decide(setOf(type, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), "SBC").capable)
        }
    }

    @Test
    fun `bluetooth qualifies only on ldac lhdc or aptx lossless`() {
        val a2dp = setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        for (codec in listOf("LDAC", "LHDC V5", "LHDC V3", "aptX Lossless")) {
            val state = LosslessOutput.decide(a2dp, codec)
            assertTrue(codec, state.capable)
            assertEquals(LosslessOutput.Kind.BLUETOOTH, state.via)
        }
        for (codec in listOf("SBC", "AAC", "aptX", "aptX HD", "aptX Adaptive", "LC3", "Opus")) {
            val state = LosslessOutput.decide(a2dp, codec)
            assertFalse(codec, state.capable)
            assertEquals(codec, state.bluetoothCodec)
        }
    }

    @Test
    fun `an unnamed bluetooth codec is reported, not guessed`() {
        val state = LosslessOutput.decide(setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), null)
        assertFalse(state.capable)
        assertTrue(state.bluetoothCodecUnknown)
    }

    @Test
    fun `the phone speaker alone does not qualify`() {
        assertEquals(LosslessOutput.State(), LosslessOutput.decide(setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), null))
    }
}

/**
 * The output device must not decide which sources exist.
 *
 * `checkValidLossless` used to remove a lossless addon from
 * [com.music.bitchord.data.sources.SourceRegistry.active] outright whenever the
 * connected output could not carry lossless. On any Bluetooth device that is
 * every device but the wired ones, so a turned-on lossless addon went silent,
 * YouTube became the only source in the walk, and
 * [com.music.bitchord.data.sources.SourceResolver.canSubstituteForYouTube]
 * correctly reported there was nothing better to ask.
 *
 * That is the whole of "it never upgrades when I'm on headphones": the setting
 * said lossless, a source that could serve lossless was switched on, and it
 * was never asked. The listener's ceiling is a preference about the stream; the
 * codec is a fact about the output, and Android re-encodes on the way out
 * either way.
 */
class LosslessOutputDoesNotShunAddonsTest {

    @Test
    fun `a bluetooth codec is still reported as not carrying lossless`() {
        // The fact itself stays true and is still shown: what must not happen is
        // the fact removing the source from the walk.
        val state = LosslessOutput.decide(
            setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            "AAC",
        )
        assertFalse(state.capable)
        assertEquals("AAC", state.bluetoothCodec)
    }

    @Test
    fun `a cable keeps qualifying, so the two states are distinguishable`() {
        val wired = LosslessOutput.decide(
            setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
            "SBC",
        )
        assertTrue(wired.capable)
    }
}
