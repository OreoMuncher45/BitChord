package com.music.bitchord

import com.music.bitchord.data.sources.HiResMonochromeBackend
import com.music.bitchord.data.sources.HiresDirectSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.StreamRequest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Hi-Res source end to end against a real HTTP server: direct-catalogue
 * search mapping, HEAD-measured stream figures, fake-file refusal, and
 * every failure shaped as a miss rather than a fault.
 *
 * Deliberately narrow: this source is one backend with no accounts, tokens
 * or converters, and the tests cover exactly that surface.
 */
class HiresDirectSourceTest {

    private lateinit var server: MockWebServer
    private val routes = mutableMapOf<String, MockResponse>()
    private val seen = mutableListOf<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request }
                val path = request.requestUrl?.encodedPath.orEmpty()
                return routes[path] ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        HiResMonochromeBackend.baseOverride = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        HiResMonochromeBackend.resetForTests()
        server.shutdown()
    }

    private fun route(path: String, json: String, code: Int = 200) {
        routes[path] = MockResponse().setResponseCode(code).setBody(json)
    }

    private fun source() = HiresDirectSource(
        SourceConfig(kind = SourceKind.HIRES),
    )

    private fun catalogueJson() = """
        {"tracks":[
            {"id":"777","title":"Creepin","artistNames":["Metro Boomin"],"duration":221520,
             "artwork":"https://art/direct.jpg","albumTitle":"Heroes & Villains",
             "isrc":"USSM12208949","explicit":false,"playable":true},
            {"id":"778","title":"Creepin Karaoke","artistNames":["Metro Boomin"],"duration":221000,
             "playable":true}
        ]}
    """.trimIndent()

    private fun innerId(videoId: String): String =
        SourceRegistry.parseTrackKey(videoId)?.second ?: error("not a track key: $videoId")

    @Test
    fun `search maps direct rows to tagged songs`() = runBlocking {
        route("/search/tracks", catalogueJson())
        val src = source()
        val rows = src.search("Creepin", limit = 10)
        assertEquals(1, rows.size)
        assertEquals("Creepin", rows[0].title)
        assertEquals("Metro Boomin", rows[0].artist)
        assertEquals("LOSSLESS", rows[0].sourceQuality)
        assertEquals("Heroes & Villains", rows[0].albumName)
        assertTrue(rows.all { it.videoId.startsWith("src:${src.configId}::") })
    }

    @Test
    fun `title matching ignores featured and remaster noise`() {
        assertTrue(HiResMonochromeBackend.titlesMatch("Blinding Lights", "Blinding Lights (Remastered)"))
        assertTrue(HiResMonochromeBackend.titlesMatch("Song feat. Someone", "Song"))
        assertTrue(!HiResMonochromeBackend.titlesMatch("Blinding Lights", "Save Your Tears"))
    }

    @Test
    fun `stream measures size into hi-res figures`() = runBlocking {
        route("/search/tracks", catalogueJson())
        routes["/track/777"] = MockResponse().setResponseCode(200)
            .addHeader("Content-Length", "74310837")
        val src = source()
        val trackId = innerId(src.search("Creepin", limit = 5).single().videoId)
        val stream = src.stream(trackId, StreamRequest.Lossless)
        assertNotNull(stream)
        assertTrue(stream!!.url.contains("/track/777"))
        assertEquals("flac", stream.format.codec)
        assertEquals(true, stream.format.isLossless)
        assertEquals(2683, stream.format.kbps)
        assertEquals(24, stream.format.bitDepth)
        assertEquals(96_000, stream.format.sampleRateHz)
        assertEquals(false, stream.belowRequest)
        assertEquals(221, stream.durationSec)
    }

    @Test
    fun `stream refuses a below-lossless file`() = runBlocking {
        route(
            "/search/tracks",
            """{"tracks":[{"id":"888","title":"Creepin","artistNames":["Metro Boomin"],"duration":200000,"playable":true}]}""",
        )
        routes["/track/888"] = MockResponse().setResponseCode(200)
            .addHeader("Content-Length", "1000000")
        val src = source()
        val trackId = innerId(src.search("Creepin", limit = 5).single().videoId)
        assertNull(src.stream(trackId, StreamRequest.Lossless))
    }

    @Test
    fun `origins survive any instance`() = runBlocking {
        route("/search/tracks", catalogueJson())
        val row = source().search("Creepin", limit = 5).single()
        val packed = SourceRegistry.parseTrackKey(row.videoId)?.second
        assertNotNull(packed)
        // A rebuilt instance (cold memo) still resolves it: origins live on
        // the backend object, not the source.
        assertNotNull(HiResMonochromeBackend.originOf(packed!!))
    }

    @Test
    fun `health is ok when the catalogue answers`() = runBlocking {
        route("/search/tracks", """{"tracks":[]}""")
        assertTrue(source().health().isOk)
    }

    @Test
    fun `health is unreachable when the catalogue is down`() = runBlocking {
        route("/search/tracks", "error", 500)
        assertTrue(!source().health().isOk)
    }

    @Test
    fun `kind carries the branded display name`() {
        assertEquals("BitChord Hi-Res Lossless Addon", SourceRegistry.HIRES_DISPLAY_NAME)
        assertEquals(SourceKind.HIRES, source().kind)
    }
}
