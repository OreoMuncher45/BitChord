package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamPreferenceTest {

    @Test
    fun `offline always keeps the file`() {
        assertFalse(shouldPreferStream(online = false, losslessCeiling = true, savedBadge = "MP3"))
        assertFalse(shouldPreferStream(online = false, losslessCeiling = true, savedBadge = "FLAC"))
    }

    @Test
    fun `lossless file under lossless ceiling stays a file`() {
        assertFalse(shouldPreferStream(online = true, losslessCeiling = true, savedBadge = "FLAC"))
        assertFalse(shouldPreferStream(online = true, losslessCeiling = true, savedBadge = "FLAC · 24-bit · 96 kHz"))
    }

    @Test
    fun `lossy file streams when online whatever the ceiling`() {
        assertTrue(shouldPreferStream(online = true, losslessCeiling = true, savedBadge = "Opus · 160 kbps"))
        assertTrue(shouldPreferStream(online = true, losslessCeiling = false, savedBadge = "Opus · 160 kbps"))
    }

    @Test
    fun `lossless file under lossy ceiling still streams`() {
        assertTrue(shouldPreferStream(online = true, losslessCeiling = false, savedBadge = "FLAC"))
    }

    @Test
    fun `unknown badge streams when online`() {
        assertTrue(shouldPreferStream(online = true, losslessCeiling = true, savedBadge = null))
    }

    @Test
    fun `dolby file is never treated as already-lossless`() {
        assertTrue(shouldPreferStream(online = true, losslessCeiling = true, savedBadge = "Dolby Atmos FLAC"))
    }
}
