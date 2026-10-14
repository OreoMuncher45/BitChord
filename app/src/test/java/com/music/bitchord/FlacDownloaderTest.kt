package com.music.bitchord

import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.FlacDownloaderApi
import com.music.bitchord.data.sources.FlacDownloaderSource
import com.music.bitchord.data.sources.HiResMonochromeBackend
import com.music.bitchord.data.sources.QobuzApi
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceHealth
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.StreamFormat
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
 * The flacdownloader.com backend plus the Hi-Res direct-FLAC fallback, end
 * to end against a real HTTP server: search mapping, the token → queue →
 * poll handshake, tier fallback, catalogue merging, and every failure
 * shaped as a miss rather than a fault.
 *
 * The live backend's `/prepare` is bot-gated from datacenter networks, so
 * no test here touches it — the mock stands in for a network the challenge
 * lets through, and what is proven is that this client drives the whole
 * exchange correctly once it does.
 */
class FlacDownloaderTest {

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
        FlacDownloaderApi.baseOverride = server.url("/").toString().trimEnd('/')
        HiResMonochromeBackend.baseOverride = server.url("/").toString().trimEnd('/')
        QobuzApi.baseOverride = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        FlacDownloaderApi.baseOverride = null
        FlacDownloaderApi.resetPollTiming()
        HiResMonochromeBackend.resetForTests()
        QobuzApi.resetForTests()
        FlacDownloaderApi.loadSp(null)
        FlacDownloaderApi.clearTokenBlock()
        FlacDownloaderApi.spReader = { "" }
        FlacDownloaderApi.spWriter = {}
        server.shutdown()
    }

    private fun route(path: String, json: String, code: Int = 200) {
        routes[path] = MockResponse().setResponseCode(code).setBody(json)
    }

    private fun source() = FlacDownloaderSource(
        SourceConfig(kind = SourceKind.HIRES),
    )

    private fun searchJson(vararg titles: String) = """
        {"has_more":false,"total":${titles.size},"tracks":[
        ${titles.mapIndexed { i, t ->
        """{"id":${1000 + i},"title":"$t","artist":"The Artist","album":"The Album","cover":"https://art/$i.jpg","durationMs":200000,"isrc":"USXX10000$i","hires":true,"bitDepth":24,"samplingRate":44.1,"url":"https://open.qobuz.com/track/${1000 + i}","explicit":false}"""
    }.joinToString(",")}
        ]}
    """.trimIndent()

    private fun directJson(
        id: String = "777",
        title: String = "Creepin",
        artist: String = "Metro Boomin",
        durationMs: Long = 221520,
        isrc: String = "USSM12208949",
    ) = """
        {"tracks":[{
            "id":$id,"title":"$title","artistNames":["$artist"],
            "duration":$durationMs,"artwork":"https://art/direct.jpg",
            "albumTitle":"Heroes & Villains","isrc":"$isrc",
            "explicit":false,"playable":true
        }]}
    """.trimIndent()

    private fun directFile(id: String, sizeBytes: Long, code: Int = 200) {
        routes["/track/$id"] = MockResponse().setResponseCode(code)
            .addHeader("Content-Length", sizeBytes.toString())
    }

    // ── Search ──────────────────────────────────────────────────────

    @Test
    fun `search maps qobuz rows to tagged songs`() = runBlocking {
        route("/api/qobuz/search", searchJson("Blinding Lights (Remastered)", "Blinding Lights"))
        val src = source()
        val rows = src.search("Blinding Lights", limit = 10)
        assertEquals(2, rows.size)
        assertEquals("Blinding Lights (Remastered)", rows[0].title)
        assertEquals("The Artist", rows[0].artist)
        assertEquals("LOSSLESS", rows[0].sourceQuality)
        assertTrue(rows.all { it.videoId.startsWith("src:${src.configId}::") })
    }

    private fun innerId(videoId: String): String =
        SourceRegistry.parseTrackKey(videoId)?.second ?: error("not a track key: $videoId")

    @Test
    fun `search skips rows carrying modifiers the query lacks`() = runBlocking {
        route("/api/qobuz/search", searchJson("Blinding Lights Karaoke", "Blinding Lights"))
        val rows = source().search("Blinding Lights", limit = 10)
        assertEquals(1, rows.size)
        assertEquals("Blinding Lights", rows[0].title)
    }

    @Test
    fun `title matching ignores featured and remaster noise`() {
        assertTrue(FlacDownloaderApi.titlesMatch("Blinding Lights", "Blinding Lights (Remastered)"))
        assertTrue(FlacDownloaderApi.titlesMatch("Song feat. Someone", "Song"))
        assertTrue(!FlacDownloaderApi.titlesMatch("Blinding Lights", "Save Your Tears"))
    }

    @Test
    fun `search merges downloader and direct catalogue rows`() = runBlocking {
        route("/api/qobuz/search", searchJson("Blinding Lights"))
        route("/search/tracks", directJson())
        val rows = source().search("Blinding Lights Creepin", limit = 10)
        assertEquals(2, rows.size)
        assertEquals("Blinding Lights", rows[0].title)
        assertEquals("Creepin", rows[1].title)
        assertEquals("Metro Boomin", rows[1].artist)
        assertEquals("LOSSLESS", rows[1].sourceQuality)
        val packed = innerId(rows[1].videoId)
        assertTrue(packed.startsWith("m:"))
        assertNotNull(HiResMonochromeBackend.originOf(packed))
    }

    @Test
    fun `search does not duplicate a recording held by both catalogues`() = runBlocking {
        route("/api/qobuz/search", searchJson("Creepin"))
        route("/search/tracks", directJson(artist = "The Artist"))
        val rows = source().search("Creepin", limit = 10)
        assertEquals(1, rows.size)
        assertTrue(!innerId(rows[0].videoId).startsWith("m:"))
    }

    @Test
    fun `direct rows expose album and credits`() = runBlocking {
        route("/search/tracks", directJson())
        val row = source().search("Creepin", limit = 5).single()
        assertEquals("Heroes & Villains", row.albumName)
        assertEquals("https://art/direct.jpg", row.thumbnailUrl)
        assertEquals(false, row.isExplicit)
    }

    // ── Full handshake ──────────────────────────────────────────────

    private fun prepareHandshake(
        token: String? = "tok123",
        jobId: String = "job9",
        file: String? = "track.flac",
        pretty: String = "Artist - Title.flac",
        pendingFirst: Int = 1,
    ) {
        // Every handshake runs on an sp session now: seed one like the
        // unlock would have, and clear it after in each test's finally.
        FlacDownloaderApi.loadSp("sp-test")
        route("/prepare", if (token == null) """{"error":"challenge_failed"}""" else """{"t":"$token"}""")
        route("/api/qobuz/queue", """{"id":"$jobId"}""")
        var polls = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request }
                val path = request.requestUrl?.encodedPath.orEmpty()
                if (path == "/api/qobuz/jobs/$jobId" && file != null) {
                    polls++
                    if (polls <= pendingFirst) return MockResponse().setResponseCode(200).setBody("""{"status":"working"}""")
                    return MockResponse().setResponseCode(200)
                        .setBody("""{"status":"done","file":"$file","pretty_name":"$pretty"}""")
                }
                return routes[path] ?: MockResponse().setResponseCode(404)
            }
        }
    }

    @Test
    fun `stream walks prepare queue poll to a flac file`() = runBlocking {
        route("/api/qobuz/search", searchJson("Blinding Lights"))
        prepareHandshake()
        val src = source()
        val trackId = innerId(src.search("Blinding Lights", limit = 5).first().videoId)
        val stream = src.stream(trackId, StreamRequest.Lossless)
        assertNotNull(stream)
        assertTrue(stream!!.url.contains("/qobuz/files/track.flac"))
        assertEquals("flac", stream.format.codec)
        assertEquals(24, stream.format.bitDepth)
        assertEquals(192_000, stream.format.sampleRateHz)
        assertEquals(1411, stream.format.kbps)
        assertEquals(false, stream.belowRequest)
        assertEquals(200, stream.durationSec)
    }

    @Test
    fun `challenge on prepare is a miss, not a fault`() = runBlocking {
        route("/api/qobuz/search", searchJson("Blinding Lights"))
        prepareHandshake(token = null)
        val src = source()
        val trackId = innerId(src.search("Blinding Lights", limit = 5).first().videoId)
        assertNull(src.stream(trackId, StreamRequest.Lossless))
    }

    @Test
    fun `direct stream measures size into hi-res figures`() = runBlocking {
        route("/search/tracks", directJson())
        directFile(id = "777", sizeBytes = 74_310_837)
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
    fun `direct stream refuses a below-lossless file`() = runBlocking {
        route("/search/tracks", directJson(id = "888", durationMs = 200_000))
        directFile(id = "888", sizeBytes = 1_000_000)
        val src = source()
        val trackId = innerId(src.search("Creepin", limit = 5).single().videoId)
        assertNull(src.stream(trackId, StreamRequest.Lossless))
    }

    @Test
    fun `locked downloader rows fall back to the direct catalogue`() = runBlocking {
        route("/api/qobuz/search", searchJson("Locked Song"))
        route("/search/tracks", directJson(title = "Locked Song", artist = "The Artist", durationMs = 200_000))
        directFile(id = "777", sizeBytes = 30_000_000)
        prepareHandshake(token = null)
        val src = source()
        val trackId = innerId(src.search("Locked Song", limit = 5).first().videoId)
        val stream = src.stream(trackId, StreamRequest.Lossless)
        assertNotNull(stream)
        assertTrue(stream!!.url.contains("/track/777"))
        assertEquals("flac", stream.format.codec)
    }

    @Test
    fun `queue body carries the full row metadata the site posts`() {
        val payload = FlacDownloaderApi.queuePayload(
            FlacDownloaderApi.Origin(
                qobuzUrl = "https://open.qobuz.com/track/1",
                title = "T",
                artist = "A",
                durationSec = 200,
                album = "Alb",
                cover = "https://art/0.jpg",
                isrc = "USXX100000",
                explicit = true,
            ),
            FlacDownloaderApi.F_24_192,
        )
        for (field in listOf(
            """"url":"https://open.qobuz.com/track/1"""",
            """"title":"T"""",
            """"artist":"A"""",
            """"album":"Alb"""",
            """"cover":"https://art/0.jpg"""",
            """"isrc":"USXX100000"""",
            """"duration":200""",
            """"explicit":true""",
            """"formatId":27""",
            """"albumArtist":"""",
            """"trackNumber":0""",
            """"genre":"""",
            """"barcode":"""",
        )) {
            assertTrue(field, field in payload)
        }
    }

    @Test
    fun `finished file urls are encoded like the site encodes them`() = runBlocking {
        route("/prepare", """{"t":"tok123"}""")
        FlacDownloaderApi.loadSp("sp-test")
        try {
            route("/api/qobuz/queue", """{"id":"job9"}""")
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    synchronized(seen) { seen += request }
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    if (path == "/api/qobuz/jobs/job9") {
                        return MockResponse().setResponseCode(200).setBody(
                            """{"status":"done","file":"Metro Boomin - Creepin (with The Weeknd).flac","pretty_name":"Metro Boomin - Creepin.flac"}""",
                        )
                    }
                    return routes[path] ?: MockResponse().setResponseCode(404)
                }
            }
            val file = FlacDownloaderApi.resolveStream(
                qobuzUrl = "https://open.qobuz.com/track/1",
                title = "Creepin",
                artist = "Metro Boomin",
                formatIds = listOf(FlacDownloaderApi.F_16_44),
            )
            assertNotNull(file)
            // Raw spaces would 404; the site percent-encodes both segments.
            assertTrue(file!!.url, "%20" in file.url)
            assertTrue(file.url, " " !in file.url.substringAfter("/qobuz/files/"))
            assertTrue(file.url, file.url.contains("Metro%20Boomin%20-%20Creepin%20%28with%20The%20Weeknd%29.flac"))
        } finally {
            FlacDownloaderApi.loadSp(null)
            FlacDownloaderApi.clearTokenBlock()
        }
    }

    @Test
    fun `a job stuck on one status is abandoned, not watched forever`() = runBlocking {
        FlacDownloaderApi.loadSp("sp-test")
        FlacDownloaderApi.pollDelayMs = 1
        FlacDownloaderApi.stagnantPolls = 5
        try {
            route("/prepare", """{"t":"tok123"}""")
            route("/api/qobuz/queue", """{"id":"stuck9"}""")
            route("/api/qobuz/jobs/stuck9", """{"status":"queued"}""")
            val file = FlacDownloaderApi.resolveStream(
                qobuzUrl = "https://open.qobuz.com/track/1",
                title = "T",
                artist = "A",
                formatIds = listOf(FlacDownloaderApi.F_16_44),
            )
            assertNull(file)
            assertTrue(
                FlacDownloaderApi.lastFailure.orEmpty(),
                "stalled" in FlacDownloaderApi.lastFailure.orEmpty(),
            )
        } finally {
            FlacDownloaderApi.loadSp(null)
            FlacDownloaderApi.clearTokenBlock()
            FlacDownloaderApi.clearLastFailure()
            FlacDownloaderApi.resetPollTiming()
        }
    }

    @Test
    fun `a progressing job is waited out, not mistaken for stalled`() = runBlocking {
        FlacDownloaderApi.loadSp("sp-test")
        FlacDownloaderApi.pollDelayMs = 1
        FlacDownloaderApi.stagnantPolls = 3
        try {
            route("/prepare", """{"t":"tok123"}""")
            route("/api/qobuz/queue", """{"id":"moving9"}""")
            var polls = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    synchronized(seen) { seen += request }
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    if (path == "/api/qobuz/jobs/moving9") {
                        polls++
                        // Status keeps moving — never 3 identical in a row —
                        // so the stall trip must not fire before done lands.
                        if (polls >= 5) {
                            return MockResponse().setResponseCode(200)
                                .setBody("""{"status":"done","file":"track.flac","pretty_name":"T.flac"}""")
                        }
                        val status = if (polls <= 2) "queued" else "working"
                        return MockResponse().setResponseCode(200)
                            .setBody("""{"status":"$status"}""")
                    }
                    return routes[path] ?: MockResponse().setResponseCode(404)
                }
            }
            val file = FlacDownloaderApi.resolveStream(
                qobuzUrl = "https://open.qobuz.com/track/1",
                title = "T",
                artist = "A",
                formatIds = listOf(FlacDownloaderApi.F_16_44),
            )
            assertNotNull(file)
        } finally {
            FlacDownloaderApi.loadSp(null)
            FlacDownloaderApi.clearTokenBlock()
            FlacDownloaderApi.clearLastFailure()
            FlacDownloaderApi.resetPollTiming()
        }
    }

    @Test
    fun `queue refusal is recorded for the sources screen`() = runBlocking {
        FlacDownloaderApi.clearLastFailure()
        FlacDownloaderApi.loadSp("sp-test")
        route("/prepare", """{"t":"tok123"}""")
        route("/api/qobuz/queue", """{"error":"nope"}""", 400)
        val file = FlacDownloaderApi.resolveStream(
            qobuzUrl = "https://open.qobuz.com/track/1",
            title = "T",
            artist = "A",
            formatIds = listOf(FlacDownloaderApi.F_16_44),
        )
        assertNull(file)
        val failure = FlacDownloaderApi.lastFailure
        assertNotNull(failure)
        assertTrue(failure!!, "400" in failure)
        FlacDownloaderApi.loadSp(null)
        FlacDownloaderApi.clearTokenBlock()
        FlacDownloaderApi.clearLastFailure()
    }

    @Test
    fun `mp3 fallback is marked below a lossless request`() = runBlocking {
        // Search tested separately; drive resolveStream through the mock
        // directly for the tier assertion.
        prepareHandshake()
        val file = FlacDownloaderApi.resolveStream(
            qobuzUrl = "https://open.qobuz.com/track/1",
            title = "T",
            artist = "A",
            formatIds = listOf(FlacDownloaderApi.F_MP3_320),
        )
        assertNotNull(file)
        assertEquals(false, file!!.isLossless)
    }

    @Test
    fun `seconds arrive as milliseconds without becoming hours`() {
        assertEquals(200_000L, HiResMonochromeBackend.normalizeDurationMs(200))
        assertEquals(200_000L, HiResMonochromeBackend.normalizeDurationMs(200_000))
    }

    @Test
    fun `size maps to the least resolution it implies`() {
        val cd = HiResMonochromeBackend.fileFrom("https://host/track/1", 8_000_000, 60_000)
        assertEquals(1066, cd.kbps)
        assertEquals(16, cd.bitDepth)
        assertEquals(44_100, cd.sampleRateHz)
        val hires = HiResMonochromeBackend.fileFrom("https://host/track/2", 74_310_837, 221_520)
        assertEquals(2683, hires.kbps)
        assertEquals(24, hires.bitDepth)
        assertEquals(96_000, hires.sampleRateHz)
    }

    @Test
    fun `health is ok when the catalogue answers`() = runBlocking {
        route("/api/qobuz/search", searchJson("x"))
        val health = source().health()
        assertTrue(health.isOk)
    }

    @Test
    fun `health names the direct fallback while qobuz stays locked`() = runBlocking {
        route("/api/qobuz/search", searchJson("x"))
        route("/search/tracks", """{"tracks":[]}""")
        val health = source().health()
        assertTrue(health.isOk)
        assertEquals(
            "tap to unlock Qobuz · direct FLAC ready",
            (health as SourceHealth.Ok).detail,
        )
    }

    @Test
    fun `health is unreachable when the catalogue is down`() = runBlocking {
        route("/api/qobuz/search", "error", 500)
        val health = source().health()
        assertTrue(!health.isOk)
    }

    @Test
    fun `challenge backs off and short-circuits repeat attempts`() = runBlocking {
        FlacDownloaderApi.clearTokenBlock()
        route("/prepare", """{"error":"challenge_failed"}""")
        assertNull(FlacDownloaderApi.prepareToken())
        assertTrue(FlacDownloaderApi.isTokenBlocked())
        val seenBefore = seen.count { it.requestUrl?.encodedPath == "/prepare" }
        assertNull(FlacDownloaderApi.prepareToken())
        val seenAfter = seen.count { it.requestUrl?.encodedPath == "/prepare" }
        assertEquals(seenBefore, seenAfter)
        FlacDownloaderApi.clearTokenBlock()
    }

    @Test
    fun `without a session there is nothing to try`() = runBlocking {
        FlacDownloaderApi.clearTokenBlock()
        FlacDownloaderApi.loadSp(null)
        route("/prepare", """{"t":"tok-live"}""")
        assertNull(FlacDownloaderApi.prepareToken(force = true))
        assertTrue(FlacDownloaderApi.isTokenBlocked())
        val hits = seen.count { it.requestUrl?.encodedPath == "/prepare" }
        assertEquals(0, hits)
        FlacDownloaderApi.clearTokenBlock()
    }

    @Test
    fun `origins survive any instance`() = runBlocking {
        route("/api/qobuz/search", searchJson("Blinding Lights"))
        val first = source()
        val row = first.search("Blinding Lights", limit = 5).first()
        val packed = SourceRegistry.parseTrackKey(row.videoId)?.second
        assertNotNull(packed)
        // A rebuilt instance (new config id, cold memo) still resolves it:
        // origins live on the API object, not the source.
        assertNotNull(FlacDownloaderApi.originOf(packed!!))
    }

    @Test
    fun `requests carry browser identity, never okhttp default`() = runBlocking {
        route("/api/qobuz/search", searchJson("x"))
        FlacDownloaderApi.searchQobuz("x", 1)
        val sent = seen.map { it.getHeader("User-Agent").orEmpty() }
        assertTrue(sent.isNotEmpty())
        assertTrue(sent.toString(), sent.all { !it.startsWith("okhttp") })
        assertTrue(sent.toString(), sent.all { "Chrome" in it })
        val lang = seen.mapNotNull { it.getHeader("Accept-Language") }
        assertTrue(lang.isNotEmpty())
        // A real fetch() from the site's own page states same-origin/cors;
        // none/no-cors belongs on navigations and marks the call as foreign.
        val sites = seen.mapNotNull { it.getHeader("Sec-Fetch-Site") }
        assertTrue(sites.toString(), sites.isNotEmpty() && sites.all { it == "same-origin" })
        val modes = seen.mapNotNull { it.getHeader("Sec-Fetch-Mode") }
        assertTrue(modes.toString(), modes.isNotEmpty() && modes.all { it == "cors" })
    }

    @Test
    fun `sp session exchanges without challenge`() = runBlocking {
        var stored = ""
        FlacDownloaderApi.spReader = { stored }
        FlacDownloaderApi.spWriter = { stored = it }
        try {
            FlacDownloaderApi.loadSp("sp-live")
            route("/prepare", """{"t":"tok-sp","sp":"sp-rotated"}""")
            assertEquals("tok-sp", FlacDownloaderApi.prepareToken(force = true))
            assertEquals("sp-rotated", stored)
            val bodies = seen.filter { it.requestUrl?.encodedPath == "/prepare" }
                .map { it.body.readUtf8() }
            assertTrue(bodies.isNotEmpty() && bodies.all { "sp" in it })
        } finally {
            FlacDownloaderApi.loadSp(null)
            FlacDownloaderApi.spReader = { "" }
            FlacDownloaderApi.spWriter = {}
        }
    }

    @Test
    fun `dead sp is dropped and falls back to blocked`() = runBlocking {
        var stored = "sp-dead"
        FlacDownloaderApi.spReader = { stored }
        FlacDownloaderApi.spWriter = { stored = it }
        try {
            route("/prepare", """{"error":"gone"}""")
            assertNull(FlacDownloaderApi.prepareToken(force = true))
            assertEquals("", stored)
            assertTrue(FlacDownloaderApi.isTokenBlocked())
        } finally {
            FlacDownloaderApi.loadSp(null)
            FlacDownloaderApi.spReader = { "" }
            FlacDownloaderApi.spWriter = {}
            FlacDownloaderApi.clearTokenBlock()
        }
    }

    private fun directSearchJson() = """
        {"tracks":{"limit":5,"offset":0,"total":1,"items":[{
            "id":555,"title":"Account Song",
            "performer":{"name":"Account Artist","id":7},
            "album":{"title":"Account Album","maximum_bit_depth":24,
                "image":{"large":"https://static.qobuz.com/a_600.jpg"}},
            "duration":200,"maximum_bit_depth":24,"maximum_sampling_rate":96.0,
            "hires":true,"isrc":"USXX1000001","parental_warning":false
        }]}}
    """.trimIndent()

    @Test
    fun `direct qobuz rows merge with namespaced ids`() = runBlocking {
        route("/api.json/0.2/track/search", directSearchJson())
        val rows = source().search("Account Song", limit = 10)
        val row = rows.single { innerId(it.videoId).startsWith("q:") }
        assertEquals("Account Song", row.title)
        assertEquals("Account Artist", row.artist)
        assertEquals("LOSSLESS", row.sourceQuality)
        assertEquals("https://static.qobuz.com/a_600.jpg", row.thumbnailUrl)
        assertNotNull(QobuzApi.originOf(innerId(row.videoId)))
    }

    @Test
    fun `signed-in account streams instantly with tier figures`() = runBlocking {
        AppSettings.qobuzToken.value = "tok-account"
        try {
            route("/api.json/0.2/track/search", directSearchJson())
            route("/api.json/0.2/track/getFile", """{"url":"https://stream.qobuz.com/f?x=1"}""")
            val src = source()
            val trackId = innerId(src.search("Account Song", limit = 5).first().videoId)
            val stream = src.stream(trackId, StreamRequest.Lossless)
            assertNotNull(stream)
            assertTrue(stream!!.url.contains("stream.qobuz.com"))
            assertEquals("flac", stream.format.codec)
            assertEquals(false, stream.belowRequest)
            assertEquals("Qobuz account", stream.backend)
        } finally {
            AppSettings.qobuzToken.value = ""
        }
    }

    @Test
    fun `health repeats a recorded stream refusal`() = runBlocking {
        route("/api/qobuz/search", searchJson("x"))
        route("/search/tracks", """{"tracks":[]}""")
        com.music.bitchord.data.NerdStats.noteRefusal("flacdownloader.com", "Token inválido o expirado")
        try {
            val health = source().health()
            assertTrue(health.isOk)
            assertTrue(
                (health as com.music.bitchord.data.sources.SourceHealth.Ok).detail.orEmpty(),
                "last stream: Token inválido o expirado" in
                    (health as com.music.bitchord.data.sources.SourceHealth.Ok).detail.orEmpty(),
            )
        } finally {
            com.music.bitchord.data.NerdStats.clearRefusalsForTests()
        }
    }

    @Test
    fun `health names the signed-in account`() = runBlocking {
        AppSettings.qobuzToken.value = "tok-account"
        AppSettings.qobuzTier.value = "Studio"
        try {
            route("/api/qobuz/search", searchJson("x"))
            val health = source().health()
            assertTrue(health.isOk)
            assertTrue(
                (health as com.music.bitchord.data.sources.SourceHealth.Ok).detail.orEmpty(),
                (health as com.music.bitchord.data.sources.SourceHealth.Ok).detail.orEmpty()
                    .contains("Qobuz account (Studio)"),
            )
        } finally {
            AppSettings.qobuzToken.value = ""
            AppSettings.qobuzTier.value = ""
        }
    }

    @Test
    fun `unlock cookies ride the gated calls only`() = runBlocking {
        FlacDownloaderApi.loadSp("sp-test")
        FlacDownloaderApi.cookiesReader = { "cf_clearance=abc" }
        try {
            route("/prepare", """{"t":"tok123"}""")
            route("/api/qobuz/search", searchJson("x"))
            assertEquals("tok123", FlacDownloaderApi.prepareToken(force = true))
            FlacDownloaderApi.searchQobuz("x", 1)
            val prepare = seen.filter { it.requestUrl?.encodedPath == "/prepare" }
            assertTrue(prepare.isNotEmpty())
            assertTrue(prepare.all { it.getHeader("Cookie") == "cf_clearance=abc" })
            val search = seen.filter { it.requestUrl?.encodedPath == "/api/qobuz/search" }
            assertTrue(search.isNotEmpty())
            assertTrue(search.all { it.getHeader("Cookie") == null })
        } finally {
            FlacDownloaderApi.loadSp(null)
            FlacDownloaderApi.clearTokenBlock()
            FlacDownloaderApi.cookiesReader = { "" }
        }
    }

    @Test
    fun `kind carries the branded display name`() {
        assertEquals("BitChord Hi-Res Lossless Addon", SourceRegistry.HIRES_DISPLAY_NAME)
        assertEquals(SourceKind.HIRES, source().kind)
    }

    // ── Live-wire regressions: the direct catalogue as it really answers ──

    private fun queuedFormatIds(): List<Int> =
        synchronized(seen) { seen.toList() }
            .filter { it.requestUrl?.encodedPath == "/api/qobuz/queue" }
            .map { request ->
                Regex(""""formatId":(\d+)""")
                    .find(request.body.readUtf8())?.groupValues?.get(1)?.toInt() ?: -1
            }

    private fun sawQueue(): Boolean =
        synchronized(seen) { seen.any { it.requestUrl?.encodedPath == "/api/qobuz/queue" } }

    @Test
    fun `direct catalogue parses quoted ids as the live host sends them`() = runBlocking {
        // The host quotes large ids ("id":"154032361934163968"); a strict
        // Long parses the test-shaped bare ones and throws on these, which
        // emptied the whole direct catalogue in production while tests stayed
        // green. This is the live shape, byte for byte.
        route("/search/tracks", directJson(id = "\"154032361934163968\"", title = "Umbrella", durationMs = 222_000))
        directFile(id = "154032361934163968", sizeBytes = 73_067_021)
        val src = source()
        val row = src.search("Umbrella", limit = 5).single()
        val packed = innerId(row.videoId)
        assertTrue(packed, packed == "m:154032361934163968")
        assertNotNull(HiResMonochromeBackend.originOf(packed))
        val stream = src.stream(packed, StreamRequest.Lossless)
        assertNotNull(stream)
        assertTrue(stream!!.url.contains("/track/154032361934163968"))
        assertEquals("flac", stream.format.codec)
        assertEquals(24, stream.format.bitDepth)
        assertEquals(FlacDownloaderSource.BACKEND_DIRECT, stream.backend)
    }

    @Test
    fun `qobuz rows resolve direct first and never touch the queue`() = runBlocking {
        route("/api/qobuz/search", searchJson("Locked Song"))
        route("/search/tracks", directJson(title = "Locked Song", artist = "The Artist", durationMs = 200_000))
        directFile(id = "777", sizeBytes = 30_000_000)
        // No sp, no prepare, no queue routes: direct must win on its own.
        val src = source()
        val trackId = innerId(src.search("Locked Song", limit = 5).first().videoId)
        val stream = src.stream(trackId, StreamRequest.Lossless)
        assertNotNull(stream)
        assertTrue(stream!!.url.contains("/track/777"))
        assertEquals(FlacDownloaderSource.BACKEND_DIRECT, stream.backend)
        assertTrue("conversion was queued", !sawQueue())
    }

    @Test
    fun `conversion rows carry their backend name`() = runBlocking {
        route("/api/qobuz/search", searchJson("Blinding Lights"))
        prepareHandshake()
        try {
            val src = source()
            val trackId = innerId(src.search("Blinding Lights", limit = 5).first().videoId)
            val stream = src.stream(trackId, StreamRequest.Lossless)
            assertNotNull(stream)
            assertEquals(FlacDownloaderSource.BACKEND_CONVERSION, stream!!.backend)
        } finally {
            FlacDownloaderApi.loadSp(null)
            FlacDownloaderApi.clearTokenBlock()
            FlacDownloaderApi.clearLastFailure()
        }
    }

    @Test
    fun `formats response narrows queued tiers to what the master holds`() = runBlocking {
        route("/api/qobuz/search", searchJson("Blinding Lights"))
        route("/api/qobuz/formats", """{"formats":[{"id":6,"label":"FLAC CD"}]}""")
        prepareHandshake()
        try {
            val src = source()
            val trackId = innerId(src.search("Blinding Lights", limit = 5).first().videoId)
            val stream = src.stream(trackId, StreamRequest.Lossless)
            assertNotNull(stream)
            assertTrue(stream!!.url.contains("/qobuz/files/track.flac"))
            // 27/7 were never attempted: the track was never mastered for them.
            assertEquals(listOf(6), queuedFormatIds())
        } finally {
            FlacDownloaderApi.loadSp(null)
            FlacDownloaderApi.clearTokenBlock()
            FlacDownloaderApi.clearLastFailure()
        }
    }

    @Test
    fun `formats outage keeps the full tier order`() = runBlocking {
        route("/api/qobuz/search", searchJson("Blinding Lights"))
        route("/api/qobuz/formats", """{"error":"boom"}""", 500)
        prepareHandshake()
        try {
            val src = source()
            val trackId = innerId(src.search("Blinding Lights", limit = 5).first().videoId)
            val stream = src.stream(trackId, StreamRequest.Lossless)
            assertNotNull(stream)
            // Best-first, exactly as without the pre-check.
            assertEquals(listOf(27), queuedFormatIds().take(1))
        } finally {
            FlacDownloaderApi.loadSp(null)
            FlacDownloaderApi.clearTokenBlock()
            FlacDownloaderApi.clearLastFailure()
        }
    }

    @Test
    fun `no lossless master fails fast with a named reason`() = runBlocking {
        route("/api/qobuz/search", searchJson("Blinding Lights"))
        route("/api/qobuz/formats", """{"formats":[{"id":5,"label":"MP3 320"}]}""")
        FlacDownloaderApi.loadSp("sp-test")
        try {
            val src = source()
            val trackId = innerId(src.search("Blinding Lights", limit = 5).first().videoId)
            assertNull(src.stream(trackId, StreamRequest.Lossless))
            assertTrue("conversion was queued", !sawQueue())
            assertTrue(
                FlacDownloaderApi.lastFailure.orEmpty(),
                "lossless" in FlacDownloaderApi.lastFailure.orEmpty(),
            )
        } finally {
            FlacDownloaderApi.loadSp(null)
            FlacDownloaderApi.clearTokenBlock()
            FlacDownloaderApi.clearLastFailure()
        }
    }

    @Test
    fun `backend rides with the declared stream and clears with it`() {
        NerdStats.onSourceStream(
            "track-be",
            StreamFormat(codec = "flac"),
            source = "BitChord Hi-Res Lossless Addon",
            backend = FlacDownloaderSource.BACKEND_DIRECT,
        )
        try {
            assertEquals(FlacDownloaderSource.BACKEND_DIRECT, NerdStats.backendFor("track-be"))
        } finally {
            NerdStats.clearDeclared("track-be")
        }
        assertNull(NerdStats.backendFor("track-be"))
    }
}
