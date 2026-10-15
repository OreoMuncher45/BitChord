package com.music.bitchord

import com.music.bitchord.data.qobuz.QobuzQueueApi
import com.music.bitchord.data.sources.QobuzQueueSource
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
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
 * The Qobuz addon end to end against a real HTTP server: locked health,
 * unlocked health, free search rows, and a full queue→poll stream.
 */
class QobuzQueueSourceTest {

    private lateinit var server: MockWebServer
    private val routes = mutableMapOf<String, MockResponse>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                return routes[path] ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        QobuzQueueApi.baseOverride = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        QobuzQueueApi.resetForTests()
        server.shutdown()
    }

    private fun route(path: String, json: String, code: Int = 200) {
        routes[path] = MockResponse().setResponseCode(code).setBody(json)
    }

    private fun locked() = QobuzQueueSource(SourceConfig(kind = SourceKind.QOBUZ))

    private fun unlocked() = QobuzQueueSource(
        SourceConfig(
            kind = SourceKind.QOBUZ,
            settings = mapOf(
                QobuzQueueSource.SP_KEY to "sp1",
                QobuzQueueSource.TOKEN_KEY to "tok1",
            ),
        ),
    )

    private fun searchJson() = """
        {"tracks":[
            {"id":108819174,"title":"Hello","artist":"Adele","album":"25",
             "cover":"https://cover/h.jpg","durationMs":295000,"isrc":"GBBKS1500214",
             "url":"https://open.qobuz.com/track/108819174",
             "bitDepth":16,"samplingRate":44.1,"hires":false}
        ]}
    """.trimIndent()

    @Test
    fun `locked health names the unlock`() = runBlocking {
        val health = locked().health()
        assertTrue(health is SourceHealth.Rejected)
        assertTrue((health as SourceHealth.Rejected).reason.contains("unlock", ignoreCase = true))
    }

    @Test
    fun `unlocked health verifies the session`() = runBlocking {
        route("/prepare", """{"t":"tok2","sp":"sp2"}""")
        val health = unlocked().health()
        assertTrue(health is SourceHealth.Ok)
    }

    @Test
    fun `expired unlock reads as rejected, not unreachable`() = runBlocking {
        route("/prepare", """{"error":"challenge_failed"}""", 403)
        val health = unlocked().health()
        assertTrue(health is SourceHealth.Rejected)
    }

    @Test
    fun `search works locked and tags lossless rows`() = runBlocking {
        route("/api/qobuz/search", searchJson())
        val rows = locked().search("adele hello", limit = 5)
        assertEquals(1, rows.size)
        assertEquals("Hello", rows[0].title)
        assertEquals("LOSSLESS", rows[0].sourceQuality)
        assertTrue(rows.all { it.videoId.startsWith("src:") })
    }

    @Test
    fun `stream converts a queued job to a flac stream`() = runBlocking {
        route("/api/qobuz/search", searchJson())
        route("/api/qobuz/queue", """{"id":"job9"}""")
        route(
            "/api/qobuz/jobs/job9",
            """{"status":"done","file":"h.flac","pretty_name":"Adele - Hello.flac"}""",
        )
        val src = unlocked()
        val trackId = SourceRegistry.parseTrackKey(
            src.search("adele hello", limit = 5).single().videoId,
        )?.second ?: error("no key")
        val stream = src.stream(trackId, StreamRequest.Lossless)
        assertNotNull(stream)
        assertTrue(stream!!.url.contains("/qobuz/files/h.flac"))
        assertEquals("flac", stream.format.codec)
        assertEquals(QobuzQueueSource.BACKEND_QUEUE, stream.backend)
        assertEquals(295, stream.durationSec)
    }

    @Test
    fun `stream without a session is a miss, not a fault`() = runBlocking {
        route("/api/qobuz/search", searchJson())
        val src = locked()
        val trackId = SourceRegistry.parseTrackKey(
            src.search("adele hello", limit = 5).single().videoId,
        )?.second ?: error("no key")
        // Locked: no token, session resume challenged.
        route("/prepare", """{"error":"challenge_failed"}""", 403)
        assertNull(src.stream(trackId, StreamRequest.Lossless))
    }

    @Test
    fun `stream refuses capped requests below mp3`() = runBlocking {
        route("/api/qobuz/search", searchJson())
        val src = unlocked()
        val trackId = SourceRegistry.parseTrackKey(
            src.search("adele hello", limit = 5).single().videoId,
        )?.second ?: error("no key")
        assertNull(src.stream(trackId, StreamRequest.Capped(128)))
    }
}
