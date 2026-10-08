package com.music.bitchord

import com.music.bitchord.data.octave.OctaveApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OctaveApiTest {

    @Test
    fun `key shape matches upstream regex`() {
        assertTrue(OctaveApi.isKeyShape("octv_2b5fb74259ae9f77299f85dac75375a9ea49ca53f35011bf"))
        assertFalse(OctaveApi.isKeyShape(""))
        assertFalse(OctaveApi.isKeyShape("octv_short"))
        assertFalse(OctaveApi.isKeyShape("octv_ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ"))
    }

    @Test
    fun `base detection is hostname-only`() {
        assertEquals(
            "https://api.octavestreaming.com",
            OctaveApi.baseOf("https://api.octavestreaming.com/"),
        )
        assertNull(OctaveApi.baseOf("https://example.com/"))
        assertNull(OctaveApi.baseOf("not a url"))
    }

    // Captured live 2026-10-08: Missing Posters — Violent Vira.
    private fun searchBody() = """
        {"results": [{"id": "3649662062", "title": "Missing Posters",
          "artist": {"id": "207437657", "name": "Violent Vira"},
          "album": {"id": "854367752", "title": "Lover Of A Ghost",
            "cover_small": "https://cdn-images.dzcdn.net/images/cover/x/56x56.jpg",
            "cover_big": "https://cdn-images.dzcdn.net/images/cover/x/500x500.jpg",
            "cover_xl": "https://cdn-images.dzcdn.net/images/cover/x/1000x1000.jpg"},
          "duration": 172, "previewUrl": "https://example.com/p.mp3",
          "explicit": false, "rank": 465796},
          {"title": "Missing Id"}]}
    """.trimIndent()

    @Test
    fun `search parses live shape and skips id-less rows`() {
        val rows = OctaveApi.parseSearch(searchBody())
        assertEquals(1, rows.size)
        val row = rows[0]
        assertEquals("3649662062", row.id)
        assertEquals("Missing Posters", row.title)
        assertEquals("Violent Vira", row.artist)
        assertEquals("Lover Of A Ghost", row.album)
        assertEquals("https://cdn-images.dzcdn.net/images/cover/x/1000x1000.jpg", row.cover)
        assertEquals(172, row.durationSec)
        assertEquals(false, row.explicit)
    }

    @Test
    fun `search on garbage is empty`() {
        assertTrue(OctaveApi.parseSearch("{}").isEmpty())
        assertTrue(OctaveApi.parseSearch("nope").isEmpty())
    }

    @Test
    fun `audio url carries tier, hints and token`() {
        val url = OctaveApi.audioUrl(
            base = "https://api.octavestreaming.com",
            tier = OctaveApi.TIER_LOSSLESS,
            id = "3649662062",
            title = "Missing Posters",
            artist = "Violent Vira",
            durationSec = 172,
            explicit = false,
            playbackToken = "tok123",
        )
        assertTrue(url.startsWith("https://api.octavestreaming.com/audio/lossless?track=3649662062"))
        assertTrue("&k=tok123" in url)
        assertTrue("&t=Missing" in url)
    }

    @Test
    fun `gated token body refuses with the server reason`() {
        // Captured live 2026-10-08: valid key, banned account.
        try {
            OctaveApi.parseToken("""{"token":null,"expiresIn":0,"gated":true,"reason":"banned"}""")
            assertTrue("must throw", false)
        } catch (e: OctaveApi.Refused) {
            assertTrue(e.message!!.contains("banned", ignoreCase = true))
        }
    }

    @Test
    fun `live token body parses with ttl`() {
        val (token, ttl) = OctaveApi.parseToken("""{"token":"abc123","expiresIn":3600}""")
        assertEquals("abc123", token)
        assertEquals(3600L, ttl)
    }

    @Test
    fun `hint query matches upstream shape`() {
        val hint = OctaveApi.hint("Missing Posters", "Violent Vira", 172, false)
        assertTrue(hint.startsWith("&t=Missing"))
        assertTrue("&a=Violent" in hint)
        assertTrue("&d=172" in hint)
        assertTrue(hint.endsWith("&x=0"))
    }
}
