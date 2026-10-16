package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPreferenceTest {

    @Test
    fun `offline always keeps the file`() {
        assertFalse(shouldPreferStream(online = false))
    }

    @Test
    fun `online always streams, local files rank last`() {
        assertTrue(shouldPreferStream(online = true))
    }
}
