package com.music.bitchord

import com.music.bitchord.data.qobuz.QobuzQueueApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * flacdownloader.com's Qobuz queue contract against a real HTTP server:
 * free search mapping, sp-resume vs challenge on /prepare, queue states,
 * and the job poll machine. Shapes copied from live responses.
 */
class QobuzQueueApiTest {

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

    private fun searchJson() = """
        {"has_more":true,"total":2,"tracks":[
            {"id":108819174,"title":"Hello","artist":"Adele","album":"25",
             "cover":"https://cover/hello.jpg","durationMs":295000,"isrc":"GBBKS1500214",
             "url":"https://open.qobuz.com/track/108819174",
             "bitDepth":16,"samplingRate":44.1,"hires":false},
            {"id":1,"title":"","artist":"Nobody"}
        ]}
    """.trimIndent()

    @Test
    fun `search maps qobuz rows, drops id-less rows`() = runBlocking {
        route("/api/qobuz/search", searchJson())
        val rows = QobuzQueueApi.searchTracks("adele hello", 10)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(108819174L, row.id)
        assertEquals("Hello", row.title)
        assertEquals("Adele", row.artist)
        assertEquals("GBBKS1500214", row.isrc)
        assertEquals(295000L, row.durationMs)
    }

    @Test
    fun `prepare with blank session answers challenge without network`() = runBlocking {
        val before = server.requestCount
        val outcome = QobuzQueueApi.prepare(null)
        assertTrue(outcome is QobuzQueueApi.TokenOutcome.Challenge)
        assertEquals(before, server.requestCount)
    }

    @Test
    fun `prepare resumes a live session and picks up rotation`() = runBlocking {
        route("/prepare", """{"t":"tok123","sp":"sp456"}""")
        val outcome = QobuzQueueApi.prepare("sp-old")
        assertTrue(outcome is QobuzQueueApi.TokenOutcome.Ready)
        outcome as QobuzQueueApi.TokenOutcome.Ready
        assertEquals("tok123", outcome.token)
        assertEquals("sp456", outcome.sp)
    }

    @Test
    fun `prepare maps 403 to challenge`() = runBlocking {
        route("/prepare", """{"error":"challenge_failed"}""", 403)
        val outcome = QobuzQueueApi.prepare("sp-dead")
        assertTrue(outcome is QobuzQueueApi.TokenOutcome.Challenge)
    }

    @Test
    fun `queue mints a job, rejects dead tokens, reports converter outages`() = runBlocking {
        val track = QobuzQueueApi.QobuzTrack(
            1, "Hello", "Adele", "25", null, 295000, "GBBKS1500214",
            "https://open.qobuz.com/track/1", 16, 44.1, false,
        )
        route("/api/qobuz/queue", """{"id":"job1"}""")
        val job = QobuzQueueApi.queue(track, QobuzQueueApi.FORMAT_FLAC_CD, "tok")
        assertTrue(job is QobuzQueueApi.QueueOutcome.Job)

        route("/api/qobuz/queue", """{"error":"unauthorized"}""", 401)
        assertTrue(
            QobuzQueueApi.queue(track, QobuzQueueApi.FORMAT_FLAC_CD, "tok") ==
                QobuzQueueApi.QueueOutcome.TokenDead,
        )

        route("/api/qobuz/queue", """{"error":"down"}""", 503)
        assertTrue(
            QobuzQueueApi.queue(track, QobuzQueueApi.FORMAT_FLAC_CD, "tok") ==
                QobuzQueueApi.QueueOutcome.ConverterDown,
        )
    }

    @Test
    fun `poll resolves a finished job to flac file figures`() = runBlocking {
        route(
            "/api/qobuz/jobs/job1",
            """{"status":"done","file":"abc.flac","pretty_name":"Adele - Hello.flac"}""",
        )
        val outcome = QobuzQueueApi.pollJob("job1", QobuzQueueApi.FORMAT_HIRES_96, budgetMs = 5_000L)
        assertTrue(outcome is QobuzQueueApi.JobOutcome.Done)
        outcome as QobuzQueueApi.JobOutcome.Done
        assertTrue(outcome.file.url.endsWith("abc.flac?name=Adele - Hello.flac"))
        assertEquals("flac", outcome.file.container)
        assertEquals(96000, outcome.file.sampleRateHz)
        assertEquals(24, outcome.file.bitDepth)
    }

    @Test
    fun `poll maps error jobs and names mp3 figures`() = runBlocking {
        route("/api/qobuz/jobs/bad", """{"status":"error","error":"region"}""")
        val outcome = QobuzQueueApi.pollJob("bad", QobuzQueueApi.FORMAT_MP3_320, budgetMs = 5_000L)
        assertTrue(outcome is QobuzQueueApi.JobOutcome.Failed)

        route(
            "/api/qobuz/jobs/mp3",
            """{"status":"done","file":"a.mp3","pretty_name":"Adele - Hello.mp3"}""",
        )
        val mp3 = QobuzQueueApi.pollJob("mp3", QobuzQueueApi.FORMAT_MP3_320, budgetMs = 5_000L)
        assertTrue(mp3 is QobuzQueueApi.JobOutcome.Done)
        mp3 as QobuzQueueApi.JobOutcome.Done
        assertEquals("mp3", mp3.file.container)
        assertEquals(320, mp3.file.bitrate)
    }

    @Test
    fun `pack unpack round trips`() {
        assertEquals(108819174L, QobuzQueueApi.unpackId(QobuzQueueApi.packId(108819174L)))
        assertNull(QobuzQueueApi.unpackId("qobuz:nope"))
    }
}
