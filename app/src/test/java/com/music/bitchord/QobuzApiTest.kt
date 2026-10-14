package com.music.bitchord

import com.music.bitchord.data.sources.QobuzApi
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
 * Qobuz's own API, against a mock standing in for www.qobuz.com: the
 * signature scheme, the page-shaped search rows, and the login exchange.
 *
 * The search shape is load-bearing and easy to get wrong — the live API
 * nests the performer as an object and the rows in a page object, and a
 * model written from the flacdownloader dialect decodes to nothing. The
 * sample below mirrors a real `track/search` answer field for field.
 */
class QobuzApiTest {

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
        QobuzApi.baseOverride = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        QobuzApi.resetForTests()
        server.shutdown()
    }

    private fun route(path: String, json: String, code: Int = 200) {
        routes[path] = MockResponse().setResponseCode(code).setBody(json)
    }

    private fun searchJson() = """
        {"tracks":{"limit":5,"offset":0,"total":1,"items":[{
            "id":3992658431,"title":"Barqat",
            "performer":{"name":"Satyam","id":1136326},
            "album":{"title":"Barqat","maximum_bit_depth":24,
                "image":{"small":"https://static.qobuz.com/x_230.jpg","thumbnail":"https://static.qobuz.com/x_50.jpg","large":"https://static.qobuz.com/x_600.jpg"}},
            "duration":146,"maximum_bit_depth":24,"maximum_sampling_rate":48.0,
            "hires":true,"isrc":"USXX1000000","parental_warning":false
        }]}}
    """.trimIndent()

    @Test
    fun `search parses the live page shape with nested performer`() = runBlocking {
        route("/api.json/0.2/track/search", searchJson())
        val rows = QobuzApi.search("Barqat", 5)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(3992658431L, row.id)
        assertEquals("Satyam", row.artistName)
        assertEquals("Barqat", row.album.title)
        assertEquals("https://static.qobuz.com/x_600.jpg", row.album.artwork)
        assertEquals(146, row.durationSec)
        assertEquals(24, row.maximumBitDepth)
        assertEquals(false, row.isExplicit)
    }

    @Test
    fun `search sends the app signature, not a bare query`() = runBlocking {
        route("/api.json/0.2/track/search", searchJson())
        QobuzApi.search("Barqat", 5)
        val query = seen.single { it.requestUrl?.encodedPath == "/api.json/0.2/track/search" }
        val params = query.requestUrl?.queryParameterNames.orEmpty()
        assertTrue(params.toString(), "app_id" in params)
        assertTrue(params.toString(), "request_ts" in params)
        assertTrue(params.toString(), "request_sig" in params)
        assertEquals("712109809", query.getHeader("X-App-Id"))
    }

    @Test
    fun `login sends an md5 digest, never the password`() = runBlocking {
        route(
            "/api.json/0.2/user/login",
            """{"user_auth_token":"tok-abc","user":{"id":9,"email":"a@b.c","subscription":{"offer":"Studio","is_active":true}}}""",
        )
        val session = QobuzApi.login("a@b.c", "s3cret")
        assertNotNull(session)
        assertEquals("tok-abc", session!!.userAuthToken)
        assertEquals("Studio", QobuzApi.tierName(session.user))
        val body = seen.single { it.requestUrl?.encodedPath == "/api.json/0.2/user/login" }
            .body.readUtf8()
        // A 32-hex digest on the wire, never the password itself.
        assertTrue(body, Regex(""""password":"[0-9a-f]{32}"""").containsMatchIn(body))
        assertTrue(body, "s3cret" !in body)
    }

    @Test
    fun `login refusal and tier names report honestly`() {
        assertNull(runBlocking { QobuzApi.login("", "") })
        assertEquals("not signed in", QobuzApi.tierName(null))
        assertEquals(
            "free (no lossless)",
            QobuzApi.tierName(QobuzApi.LoginUser(subscription = QobuzApi.LoginSubscription(isActive = false))),
        )
    }

    @Test
    fun `resolveFile asks for the format and labels the answer`() = runBlocking {
        route("/api.json/0.2/track/getFile", """{"url":"https://stream.qobuz.com/file?x=1"}""")
        val file = QobuzApi.resolveFile(3992658431L, "tok-abc", listOf(QobuzApi.F_16_44))
        assertNotNull(file)
        assertEquals("https://stream.qobuz.com/file?x=1", file!!.url)
        assertEquals(16, file.bitDepth)
        assertEquals(44_100, file.sampleRateHz)
        val asked = seen.single { it.requestUrl?.encodedPath == "/api.json/0.2/track/getFile" }
        assertTrue(
            asked.requestUrl.toString(),
            "format_id=6" in asked.requestUrl.toString(),
        )
    }
}
