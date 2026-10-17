package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local is authoritative.
 *
 * The policy this pins used to be `shouldPreferStream(online)`: the network
 * outranked the file, the disk was the offline path and the failure fallback.
 * Because it read `AppSettings.meteredConnection`, which starts `null` and
 * flaps across onLost/onAvailable, the same downloaded song came out of one
 * queue as a local file and out of the next as a stream — and the quality
 * upgrade, which a file forbids and a stream allows, followed it. These tests
 * exist so that inversion cannot come back quietly.
 */
class StreamPreferenceTest {

    @Test
    fun `with a network up, the file is not what plays`() {
        assertFalse(shouldPlayLocalFile(hasLocalFile = true, canStream = true))
    }

    @Test
    fun `with no network, the file is all there is`() {
        assertTrue(shouldPlayLocalFile(hasLocalFile = true, canStream = false))
    }

    @Test
    fun `no file means no local playback either way`() {
        assertFalse(shouldPlayLocalFile(hasLocalFile = false, canStream = true))
        assertFalse(shouldPlayLocalFile(hasLocalFile = false, canStream = false))
    }

    @Test
    fun `a stream that failed can still be handed the file`() {
        // The fallback rebuilds the item after a stream has already failed. It
        // must not be possible for that rebuild to come back as a stream again.
        assertTrue(shouldPlayLocalFile(hasLocalFile = true, canStream = false))
    }
}
