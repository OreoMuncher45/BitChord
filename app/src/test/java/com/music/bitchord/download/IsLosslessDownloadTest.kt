package com.music.bitchord.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsLosslessDownloadTest {

    @Test
    fun `lossless codecs keep the file`() {
        assertTrue(isLosslessDownload("FLAC"))
        assertTrue(isLosslessDownload("FLAC · 24-bit · 96 kHz"))
        assertTrue(isLosslessDownload("ALAC"))
        assertTrue(isLosslessDownload("WAV"))
        assertTrue(isLosslessDownload("Lossless"))
    }

    @Test
    fun `lossy badges stream`() {
        assertFalse(isLosslessDownload("Opus · 160 kbps"))
        assertFalse(isLosslessDownload("AAC · 320 kbps"))
        assertFalse(isLosslessDownload("MP3"))
    }

    @Test
    fun `dolby stays on disk even when the badge says flac`() {
        assertFalse(isLosslessDownload("Dolby Atmos FLAC"))
    }

    @Test
    fun `unknown badge streams`() {
        assertFalse(isLosslessDownload(null))
    }
}
