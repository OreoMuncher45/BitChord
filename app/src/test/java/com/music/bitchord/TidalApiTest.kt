package com.music.bitchord

import com.music.bitchord.data.tidal.TidalApi
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalApiTest {

    // ---- Version gate ----

    @Test
    fun `v2 root document passes`() {
        assertEquals(
            "2.10",
            TidalApi.parseVersion("""{"version": "2.10", "Repo": "https://github.com/binimum/hifi-api"}"""),
        )
    }

    @Test
    fun `v1 and garbage fail`() {
        assertNull(TidalApi.parseVersion("""{"version": "1.4"}"""))
        assertNull(TidalApi.parseVersion("""{"resources": ["search"]}"""))
        assertNull(TidalApi.parseVersion("not json"))
        assertNull(TidalApi.parseVersion(""))
    }

    // ---- URL normalising ----

    @Test
    fun `normalize trims and drops trailing slash`() {
        assertEquals("https://api.monochrome.tf", TidalApi.normalize("https://api.monochrome.tf/"))
        assertEquals("https://api.monochrome.tf", TidalApi.normalize("  https://api.monochrome.tf  "))
        assertNull(TidalApi.normalize("not a url"))
        assertNull(TidalApi.normalize(""))
    }

    // ---- Search parsing ----

    private fun searchBody() = """
        {"version": "2.10", "data": {"items": [
          {"id": 48717877, "title": "SexyBack", "duration": 243, "explicit": true,
           "artist": {"name": "Justin Timberlake"},
           "artists": [{"name": "Justin Timberlake"}, {"name": "Timbaland"}],
           "album": {"title": "FutureSex/LoveSounds", "cover": "0e7b-8b11-2222-aaaa"},
           "audioQuality": "HI_RES_LOSSLESS"},
          {"id": 123, "title": "No Album Track", "duration": 180,
           "artists": [{"name": "Someone"}],
           "audioQuality": "LOW"},
          {"title": "Missing Id"}
        ]}}
    """.trimIndent()

    @Test
    fun `search parses rows and skips id-less ones`() {
        val rows = TidalApi.parseSearch(searchBody())
        assertEquals(2, rows.size)
        val first = rows[0]
        assertEquals("48717877", first.id)
        assertEquals("SexyBack", first.title)
        assertTrue(first.artist.contains("Justin Timberlake"))
        assertTrue(first.artist.contains("Timbaland"))
        assertEquals("FutureSex/LoveSounds", first.album)
        assertEquals("0e7b-8b11-2222-aaaa", first.coverSlug)
        assertEquals(243, first.durationSec)
        assertEquals(true, first.explicit)
        assertEquals("HI_RES_LOSSLESS", first.audioQuality)
        assertNull(rows[1].album)
    }

    @Test
    fun `search on garbage is empty`() {
        assertTrue(TidalApi.parseSearch("{}").isEmpty())
        assertTrue(TidalApi.parseSearch("nope").isEmpty())
    }

    // ---- Manifest picking ----

    private fun manifestBody(mime: String, encryption: String, vararg urls: String): String {
        val manifest = """{"mimeType": "$mime", "codecs": "flac",
            "encryptionType": "$encryption",
            "urls": [${urls.joinToString(",") { "\"$it\"" }}]}"""
        val encoded = Base64.getEncoder().encodeToString(manifest.toByteArray())
        return """{"version": "2.10", "data": {
            "audioQuality": "LOSSLESS", "manifestMimeType": "application/vnd.tidal.bts",
            "manifest": "$encoded"}}"""
    }

    @Test
    fun `clear manifest yields first url`() {
        val stream = TidalApi.pickStream(
            manifestBody("audio/flac", "NONE", "https://cdn/0.flac?token=1", "https://cdn/1.flac"),
        )
        assertEquals("https://cdn/0.flac?token=1", stream?.url)
        assertEquals("LOSSLESS", stream?.quality)
        assertEquals("audio/flac", stream?.mimeType)
    }

    @Test
    fun `drm and empty manifests are refused`() {
        assertNull(TidalApi.pickStream(manifestBody("audio/flac", "AES-CTR", "https://cdn/0.flac")))
        assertNull(TidalApi.pickStream(manifestBody("audio/flac", "NONE")))
        assertNull(TidalApi.pickStream("{}"))
    }

    // ---- Covers ----

    @Test
    fun `cover slug becomes image url`() {
        assertEquals(
            "https://resources.tidal.com/images/0e7b/8b11/640x640.jpg",
            TidalApi.coverUrl("0e7b-8b11"),
        )
    }

    @Test
    fun `lossless tiers order hi-res first`() {
        assertEquals(TidalApi.HI_RES, TidalApi.LOSSLESS_TIERS.first())
        assertFalse(TidalApi.LOSSLESS_TIERS.isEmpty())
    }

    // ---- Registry seeding: on by default, zero setup ----

    @Test
    fun `tidal seeds enabled with bundled primary`() {
        val sources = com.music.bitchord.data.sources.SourceRegistry.sourcesForInit(
            emptyList(),
            forceJioSaavnOff = true,
        )
        val tidal = sources.single { it.kind == com.music.bitchord.data.sources.SourceKind.TIDAL }
        assertTrue(tidal.enabled)
        assertTrue(tidal.baseUrl.isNotBlank())
    }

    @Test
    fun `existing installs gain tidal without touching other sources`() {
        val youtube = com.music.bitchord.data.sources.SourceConfig(
            kind = com.music.bitchord.data.sources.SourceKind.YOUTUBE,
        )
        val sources = com.music.bitchord.data.sources.SourceRegistry.sourcesForInit(
            listOf(youtube),
            forceJioSaavnOff = false,
        )
        assertTrue(sources.single { it.kind == com.music.bitchord.data.sources.SourceKind.TIDAL }.enabled)
        assertEquals(youtube.id, sources.single { it.kind == com.music.bitchord.data.sources.SourceKind.YOUTUBE }.id)
    }
}
