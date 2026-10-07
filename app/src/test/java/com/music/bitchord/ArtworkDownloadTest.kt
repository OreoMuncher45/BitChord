package com.music.bitchord

import com.music.bitchord.data.artwork.ArtworkDownload
import com.music.bitchord.data.flow.FlowStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkDownloadTest {

    @Test
    fun `size hint upgrades to source rung`() {
        assertEquals(
            "https://lh3.googleusercontent.com/x=w1400-h1400-l90-rj",
            ArtworkDownload.fullUrl("https://lh3.googleusercontent.com/x=w60-h60-l90-rj"),
        )
    }

    @Test
    fun `tidal cover steps up to 1280`() {
        assertEquals(
            "https://resources.tidal.com/images/aa/bb/1280x1280.jpg",
            ArtworkDownload.fullUrl("https://resources.tidal.com/images/aa/bb/640x640.jpg"),
        )
    }

    @Test
    fun `missing or plain art passes through`() {
        assertNull(ArtworkDownload.fullUrl(null))
        assertNull(ArtworkDownload.fullUrl("  "))
        assertEquals(
            "https://example.com/a.jpg",
            ArtworkDownload.fullUrl("https://example.com/a.jpg"),
        )
    }

    @Test
    fun `excluded genres normalize to lowercase`() {
        FlowStore.setExcludedGenres(setOf("Rock", "HIP-HOP"))
        assertEquals(
            setOf("rock", "hip-hop"),
            FlowStore.tuner.value.excludedGenres,
        )
        FlowStore.setExcludedGenres(emptySet())
    }
}
