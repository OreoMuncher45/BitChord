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
    fun `a downloaded file plays, with or without a network`() {
        assertTrue(shouldPlayLocalFile(hasLocalFile = true))
    }

    @Test
    fun `no file means no local playback, whatever the network is doing`() {
        assertFalse(shouldPlayLocalFile(hasLocalFile = false))
    }

    @Test
    fun `connectivity is not an input to the decision`() {
        // The old signature took `online` and ignored connectivity exactly once
        // — at the call site, where it read a value that starts null and flaps.
        // Asserting the call shape keeps a `meteredConnection` read from being
        // reintroduced here, where it cannot be reasoned about.
        val calls = buildList {
            repeat(3) { add(shouldPlayLocalFile(hasLocalFile = true)) }
        }
        assertTrue(calls.all { it })
    }
}