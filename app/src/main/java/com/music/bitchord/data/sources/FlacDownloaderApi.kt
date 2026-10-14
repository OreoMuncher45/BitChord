package com.music.bitchord.data.sources

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.settings.AppSettings
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.coroutineContext

/**
 * The flacdownloader.com backend behind the Spotube FLAC plugin, spoken
 * directly: Qobuz catalogue search, a tokened queue for conversions, and a
 * job poll that ends in a file URL. No plugin runtime, no Spotube — the
 * plugin's Hetu scripts reduce to these six calls, and every one of them is
 * plain HTTP and JSON.
 *
 * Two things about this backend shape the client. First, search is open and
 * fast while stream issuance is gated: `/prepare` answers a token or a
 * `challenge_failed`, and without the token there is nothing to queue. A
 * challenge is environmental (bot defense vs. this network), not a miss, so
 * it fails loudly as null-with-a-log-line rather than burning the poll
 * budget proving it. Second, a conversion is a *job*, not a URL: the poll
 * loop is bounded in both count and wall time, delayed between rounds (the
 * plugin hammers with no delay at all), and cancellable at every await, so
 * a slow backend loses the resolver's race instead of holding it.
 */
object FlacDownloaderApi {

    const val BASE = "https://flacdownloader.com"
    private const val TAG = "BitChord"

    /**
     * Test hook: point the client at a MockWebServer instead of the live
     * backend. Internal so production code cannot wander off-host; unit
     * tests reset it after each case.
     */
    internal var baseOverride: String? = null
    private val base: String get() = baseOverride?.trimEnd('/') ?: BASE

    /** Qobuz format ids, best first — the plugin's `[27, 7, 6, 5]`. */
    const val F_24_192 = 27
    const val F_24_96 = 7
    const val F_16_44 = 6
    const val F_MP3_320 = 5

    val LOSSLESS_FORMATS = listOf(F_24_192, F_24_96, F_16_44)
    val LOSSY_FORMATS = listOf(F_MP3_320)

    /**
     * Wall clock for one resolve across all formats. Four minutes, not
     * seventy-five seconds: a conversion is server-side work on a free
     * service, and the site itself polls a job for up to fifteen minutes
     * before timing out. The upgrade and read-ahead paths that consume this
     * cancel a resolve the moment nobody is waiting on it, so a long budget
     * costs radio only while an answer is genuinely still wanted.
     */
    const val RESOLVE_BUDGET_MS = 240_000L

    /**
     * Poll timing. Internal vars rather than consts so tests can shrink a
     * minute-long stall into milliseconds — same hook precedent as
     * [baseOverride]. Production never writes them; tests reset them.
     */
    internal var pollDelayMs: Long = 2_000L
    internal var maxPollsPerFormat: Int = 60
    /** Consecutive identical non-terminal statuses before a job is declared stalled. */
    internal var stagnantPolls: Int = 30
    /** Consecutive failed poll round trips before a job is abandoned. */
    internal var maxPollErrors: Int = 3

    internal fun resetPollTiming() {
        pollDelayMs = 2_000L
        maxPollsPerFormat = 60
        stagnantPolls = 30
        maxPollErrors = 3
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * When a token was last refused by the backend's challenge, until which
     * no new attempt is made. A challenged network answers every attempt
     * identically, so retrying per track would hold each upgrade walk for
     * the full retry budget accomplishing nothing — the miss has to cost
     * milliseconds, not seconds. A success clears it; an explicit user
     * Test bypasses it (see [prepareToken]).
     */
    @Volatile
    private var tokenBlockedUntilMs = 0L

    /** In-memory sp session; persisted via [AppSettings.fdlSp]. */
    @Volatile
    private var spToken: String? = null
    private const val TOKEN_BLOCK_MS = 10 * 60 * 1000L
    private const val TOKEN_ATTEMPTS = 3
    private const val TOKEN_RETRY_DELAY_MS = 1_500L

    private val client: OkHttpClient by lazy {
        Http.client.newBuilder()
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    // ── Wire shapes ───────────────────────────────────────────────────

    @Serializable
    data class QobuzSearchResponse(
        @SerialName("tracks") val tracks: List<QobuzTrack> = emptyList(),
    )

    @Serializable
    data class QobuzTrack(
        @SerialName("id") val id: Long = 0,
        @SerialName("title") val title: String = "",
        @SerialName("artist") val artist: String = "",
        @SerialName("album") val album: String = "",
        @SerialName("albumArtist") val albumArtist: String = "",
        @SerialName("cover") val cover: String? = null,
        @SerialName("durationMs") val durationMs: Long? = null,
        @SerialName("isrc") val isrc: String? = null,
        @SerialName("hires") val hires: Boolean = false,
        @SerialName("bitDepth") val bitDepth: Double? = null,
        @SerialName("samplingRate") val samplingRate: Double? = null,
        @SerialName("url") val url: String? = null,
        @SerialName("explicit") val explicit: Boolean = false,
        @SerialName("date") val date: String = "",
        @SerialName("trackNumber") val trackNumber: Int = 0,
        @SerialName("trackTotal") val trackTotal: Int = 0,
        @SerialName("discNumber") val discNumber: Int = 0,
        @SerialName("discTotal") val discTotal: Int = 0,
        @SerialName("genre") val genre: String = "",
        @SerialName("label") val label: String = "",
        @SerialName("copyright") val copyright: String = "",
        @SerialName("composer") val composer: String = "",
        @SerialName("barcode") val barcode: String = "",
        @SerialName("version") val version: String = "",
        @SerialName("work") val work: String = "",
    )

    @Serializable
    private data class TokenResponse(
        @SerialName("t") val token: String? = null,
        @SerialName("sp") val session: String? = null,
    )

    @Serializable
    private data class QueueResponse(@SerialName("id") val jobId: String? = null)

    @Serializable
    private data class FormatsResponse(
        @SerialName("formats") val formats: List<FormatRow> = emptyList(),
    )

    @Serializable
    private data class FormatRow(@SerialName("id") val id: Int = -1)

    @Serializable
    private data class JobResponse(
        @SerialName("status") val status: String? = null,
        @SerialName("file") val file: String? = null,
        @SerialName("pretty_name") val prettyName: String? = null,
    )

    // ── Tidal (same site, second catalogue) ───────────────────────────
    //
    // flacdownloader fronts two catalogues behind one token. Qobuz's
    // conversion half stopped answering for everyone -- our addon and a
    // third-party one alike -- while Tidal's is live and its catalogue is
    // *broader* than the direct host we fall back to: tracks.monochrome.st
    // returned nothing for 'Barqat' or 'Cigarette Coping', this returns
    // both, marked hires, with artist credits matching what YouTube files
    // them under. Same /prepare token, same queue -> job -> file shape.

    @Serializable
    data class TidalSearchResponse(
        @SerialName("tracks") val tracks: List<TidalTrack> = emptyList(),
    )

    @Serializable
    data class TidalTrack(
        @SerialName("id") val id: Long = 0,
        @SerialName("title") val title: String = "",
        @SerialName("artist") val artist: String = "",
        @SerialName("album") val album: String = "",
        @SerialName("cover") val cover: String? = null,
        @SerialName("durationMs") val durationMs: Long? = null,
        @SerialName("isrc") val isrc: String? = null,
        @SerialName("hires") val hires: Boolean = false,
        @SerialName("lossless") val lossless: Boolean = false,
        @SerialName("explicit") val explicit: Boolean = false,
        @SerialName("version") val version: String = "",
        @SerialName("url") val url: String? = null,
    )

    /** Tidal ids look exactly like Qobuz ones, so packed with a prefix. */
    private const val TIDAL_PACK_PREFIX = "t:"

    data class TidalOrigin(
        val id: Long,
        val url: String,
        val title: String,
        val artist: String,
    )

    private val tidalOrigins = Collections.synchronizedMap(
        object : LinkedHashMap<String, TidalOrigin>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, TidalOrigin>) = size > 128
        },
    )

    fun packTidalId(id: Long): String = "$TIDAL_PACK_PREFIX$id"

    fun memoizeTidalOrigin(packed: String, origin: TidalOrigin) {
        tidalOrigins[packed] = origin
    }

    fun tidalOriginOf(packed: String): TidalOrigin? = tidalOrigins[packed]

    suspend fun searchTidal(query: String, limit: Int = 25): List<TidalTrack> {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        return try {
            json.decodeFromString<TidalSearchResponse>(
                get("$base/api/tidal/search?q=${encode(q)}&limit=${limit.coerceIn(1, 50)}"),
            ).tracks
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            TrackLog.d(TAG, "  ✗ hifiresolver tidal search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Queue a Tidal conversion and poll it to a file, on the same budget as
     * the Qobuz path: the site posts url/id/title/artist/formatId with the
     * token and the job answers with the finished file name.
     */
    suspend fun resolveTidalStream(
        origin: TidalOrigin,
        budgetMs: Long = RESOLVE_BUDGET_MS,
    ): ResolvedFile? {
        if (origin.id <= 0L || origin.url.isBlank()) return null
        val token = prepareToken() ?: return null
        val payload = "{\"url\":${q(origin.url)}," +
            "\"id\":${origin.id}," +
            "\"title\":${q(origin.title)}," +
            "\"artist\":${q(origin.artist)}," +
            "\"formatId\":\"LOSSLESS\"}"
        val jobId = try {
            val request = Request.Builder()
                .url("${base}/api/tidal/queue")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .browserHeaders()
                .header("Accept", "application/json, text/plain, */*")
                .header("X-DL-Token", token)
                .header("Referer", "${base}/en/tidal")
                .header("Origin", base)
                .withCookies()
                .build()
            val (code, body) = client.newCall(request).execute().use { response ->
                response.code to
                    (runCatching { response.body?.string() }.getOrNull()?.takeIf { it.isNotBlank() })
            }
            if (code != 200 || body == null) {
                noteFailure("tidal queue refused (HTTP $code): ${(body ?: "empty").take(120)}")
                return null
            }
            json.decodeFromString<QueueResponse>(body).jobId?.takeIf { it.isNotBlank() }
                ?: run {
                    noteFailure("tidal queue answered without a job id: ${body.take(120)}")
                    null
                }
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            noteFailure("tidal queue failed: ${e.message}")
            null
        } ?: return null
        return pollJob(jobId, F_16_44, "/api/tidal/jobs/$jobId", "/tidal/files")
    }

    @Serializable
    private data class DeezerSearchResponse(
        @SerialName("data") val data: List<DeezerRow> = emptyList(),
    )

    @Serializable
    data class DeezerRow(
        @SerialName("title") val title: String = "",
        @SerialName("isrc") val isrc: String? = null,
    )

    /**
     * What a search row needs later at queue time, parked by packed id.
     *
     * Lives here rather than on any source instance because instances are
     * rebuilt whenever configs change: a memo on the instance strands
     * every row it minted the moment the registry rebuilds, and the stream
     * call then misses on tracks just found. Qobuz ids are global, so one
     * bounded map serves all instances.
     *
     * The queue endpoint takes the row's full metadata, the way the site's
     * own client sends it — url/title/artist alone proved too thin once the
     * backend started validating the rest.
     */
    data class Origin(
        val qobuzUrl: String,
        val title: String,
        val artist: String,
        val durationSec: Int?,
        val album: String = "",
        val cover: String = "",
        val isrc: String = "",
        val explicit: Boolean = false,
        val albumArtist: String = "",
        val date: String = "",
        val trackNumber: Int = 0,
        val trackTotal: Int = 0,
        val discNumber: Int = 0,
        val discTotal: Int = 0,
        val genre: String = "",
        val label: String = "",
        val copyright: String = "",
        val composer: String = "",
        val barcode: String = "",
        val version: String = "",
        val work: String = "",
        /** The row's own master figures, for the formats pre-check below. */
        val bitDepth: Double? = null,
        val samplingRate: Double? = null,
    )

    private val origins = Collections.synchronizedMap(
        object : LinkedHashMap<String, Origin>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Origin>) = size > 128
        },
    )

    fun memoizeOrigin(packed: String, origin: Origin) {
        origins[packed] = origin
    }

    fun originOf(packed: String): Origin? = origins[packed]

    /** A converted file waiting on the backend's CDN. */
    data class ResolvedFile(
        val url: String,
        val formatId: Int,
    ) {
        val isLossless: Boolean get() = formatId != F_MP3_320
        val sampleRateHz: Int get() = if (formatId == F_24_192) 192_000 else 96_000.takeIf { formatId == F_24_96 } ?: 44_100
        val bitDepth: Int get() = if (formatId == F_24_192 || formatId == F_24_96) 24 else 16
        val kbps: Int get() = if (formatId == F_MP3_320) 320 else 1_411
    }

    // ── Catalogue ─────────────────────────────────────────────────────

    /**
     * Whether the catalogue answers at all. Unlike [searchQobuz], which
     * swallows failure into an empty list, this throws — health needs the
     * distinction between "reachable but holding nothing" and "gone".
     */
    suspend fun probe() {
        get("$base/api/qobuz/search?q=music&offset=0&limit=1")
    }

    suspend fun searchQobuz(query: String, limit: Int = 25): List<QobuzTrack> {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        val url = "${base}/api/qobuz/search?q=${encode(q)}&offset=0&limit=${limit.coerceIn(1, 100)}"
        return try {
            json.decodeFromString<QobuzSearchResponse>(get(url)).tracks
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            TrackLog.d(TAG, "  ✗ hifiresolver qobuz search failed: ${e.message}")
            emptyList()
        }
    }

    /** Deezer rows for a query, for the ISRC precision re-search below. */
    suspend fun deezerRows(query: String): List<DeezerRow> {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        return try {
            val request = Request.Builder()
                .url("https://api.deezer.com/search?q=${encode(q)}&limit=20")
                .browserHeaders()
                .build()
            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                response.body?.string()?.takeIf { it.isNotBlank() } ?: return emptyList()
            }
            json.decodeFromString<DeezerSearchResponse>(body).data
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            TrackLog.d(TAG, "  ✗ hifiresolver deezer search failed: ${e.message}")
            emptyList()
        }
    }

    // ── Stream issuance ───────────────────────────────────────────────

    /**
     * A conversion token, from the sp session when one exists.
     *
     * Two paths, in order. The sp session (captured by the one-tap guided
     * unlock, rotated by every answer) exchanges without any challenge, so
     * it is tried first and never backs off: it either answers or it is
     * dead, and a dead one is dropped on the spot. With no session there
     * is nothing headless that could succeed — a bare call only proves
     * the challenge exists — so it fails fast instead of burning retries.
     */
    suspend fun prepareToken(force: Boolean = false): String? {
        if (!force && System.currentTimeMillis() < tokenBlockedUntilMs) {
            // Previously silent, so a backoff left the source reporting
            // nothing while conversions refused themselves. Said out loud:
            // "blocked" and "no session" are different faults with different
            // fixes, and neither is guessable from the outside.
            noteFailure("backing off for another ${(tokenBlockedUntilMs - System.currentTimeMillis()) / 1000}s")
            return null
        }
        val sp = spToken
            ?: runCatching { spReader() }.getOrDefault("").takeIf { it.isNotBlank() }?.also { spToken = it }
        if (sp == null) {
            tokenBlockedUntilMs = System.currentTimeMillis() + TOKEN_BLOCK_MS
            noteFailure("no stored session — tap the Hi-Res addon to unlock")
            TrackLog.w(TAG, "  ✗ hifiresolver has no session; conversions cannot be queued")
            return null
        }
        // Whether the backend *refused* this session, as opposed to us failing
        // to reach it. The distinction is the whole bug: a dropped connection
        // is weather, and treating it as a refusal threw the stored session
        // away, which silently disabled every conversion on the device
        // thereafter — with Direct FLAC still working, so the only symptom was
        // that the niche tracks silently never upgraded and every report said
        // "no reason recorded". Measured: Barqat by Satyam returned null 7ms
        // after its Tidal row matched, which is a lookup in memory, not a
        // request that crossed a network.
        var refused = false
        var lastError: String? = null
        repeat(TOKEN_ATTEMPTS) { attempt ->
            try {
                val answer = json.decodeFromString<TokenResponse>(postPrepare(mapOf("sp" to sp)))
                answer.session?.takeIf { it.isNotBlank() }?.let { persistSp(it) }
                val token = answer.token?.takeIf { it.isNotBlank() }
                if (token != null) {
                    tokenBlockedUntilMs = 0L
                    clearLastFailure()
                    return token
                }
                // The backend answered and declined. That is a verdict on this
                // session, and only now is dropping it justified.
                refused = true
                TrackLog.w(TAG, "  ✗ hifiresolver session refused")
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                lastError = e.message
                TrackLog.d(TAG, "  ✗ hifiresolver prepare failed: ${e.message}")
            }
            if (attempt + 1 < TOKEN_ATTEMPTS) delay(TOKEN_RETRY_DELAY_MS)
        }
        noteFailure(
            if (refused) "session refused by the backend"
            else "could not reach the backend to exchange the session (${lastError ?: "unknown"})",
        )
        // Only a refusal costs the session. A network failure must not, or one
        // bad signal disables lossless for the device until the listener
        // notices and unlocks again by hand.
        if (refused) forgetSp()
        tokenBlockedUntilMs = System.currentTimeMillis() + TOKEN_BLOCK_MS
        return null
    }

    /** The live sp session, if the unlock captured one. Null clears it. */
    fun loadSp(sp: String?) {
        spToken = sp?.takeIf { it.isNotBlank() }
    }

    /**
     * Persisted sp I/O. Production talks to AppSettings; tests swap in
     * memory. Reading the flow never touches prefs, only writing does —
     * and a writer that throws (no prefs, e.g. unit tests that forgot to
     * swap) must not take the request down with it.
     */
    internal var spReader: () -> String = { AppSettings.fdlSp.value }
    internal var spWriter: (String) -> Unit = { AppSettings.setFdlSp(it) }

    /**
     * Cookies captured from the unlock WebView, sent only on the gated calls
     * (prepare/queue/jobs) — never on open search. The WebView is a real
     * browser context: it passes whatever edge checks a headless client
     * trips, and the clearance cookies it holds are what tell the edge the
     * next call comes from that same context. They age with the session, so
     * a dead session takes its cookies with it — see [forgetSp].
     */
    internal var cookiesReader: () -> String = { AppSettings.fdlCookies.value }

    private fun Request.Builder.withCookies(): Request.Builder {
        val cookies = runCatching { cookiesReader() }.getOrDefault("").trim()
        return if (cookies.isBlank()) this else header("Cookie", cookies)
    }

    private fun persistSp(sp: String) {
        spToken = sp
        runCatching { spWriter(sp) }
    }

    private fun forgetSp() {
        spToken = null
        runCatching { spWriter("") }
        runCatching { AppSettings.setFdlCookies("") }
    }

    private suspend fun postPrepare(params: Map<String, String>): String {
        val payload = params.entries.joinToString(",", "{", "}") { (k, v) -> "${q(k)}:${q(v)}" }
        val request = Request.Builder()
            .url("${base}/prepare")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .browserHeaders()
            .header("Content-Type", "application/json")
            .header("Referer", "${base}/en/qobuz")
            .header("Origin", base)
            .withCookies()
            .build()
        return client.newCall(request).execute().use { response ->
            // The body is the diagnosis: challenge_failed means the session
            // is dead, a Cloudflare page means the network is gated. Both
            // used to arrive as a bare "HTTP 403", which made the two
            // indistinguishable everywhere downstream.
            val body = runCatching { response.body?.string() }.getOrNull()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${(body ?: "").take(160)}")
            }
            body?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Empty response")
        }
    }

    /** Visible for tests: whether [prepareToken] would currently short-circuit. */
    internal fun isTokenBlocked(): Boolean = System.currentTimeMillis() < tokenBlockedUntilMs

    /** Test hook: reset the challenge backoff between cases. */
    internal fun clearTokenBlock() {
        tokenBlockedUntilMs = 0L
    }

    /**
     * The last conversion failure, in plain words for the Sources screen.
     * Queue and poll misses used to vanish into a log line nobody on the
     * device ever sees, which is how "says unlocked, streams nothing" stayed
     * a mystery: the token worked and the conversion didn't. Cleared on the
     * next file that lands.
     */
    @Volatile
    var lastFailure: String? = null
        private set

    /** Test hook: forget the last recorded conversion failure. */
    internal fun clearLastFailure() {
        lastFailure = null
    }

    private fun noteFailure(message: String) {
        lastFailure = message.take(160)
        TrackLog.d(TAG, "  ✗ hifiresolver $message")
    }

    /**
     * The queue body, field for field what the site's own client posts.
     * Pure so the shape is unit-testable: a drift here is silent on the
     * wire (the backend just refuses the job) and loud nowhere else.
     */
    fun queuePayload(origin: Origin, formatId: Int): String {
        val durationSec = origin.durationSec?.takeIf { it > 0 } ?: 0
        return "{\"url\":${q(origin.qobuzUrl)}," +
            "\"title\":${q(origin.title)}," +
            "\"artist\":${q(origin.artist)}," +
            "\"album\":${q(origin.album)}," +
            "\"cover\":${q(origin.cover)}," +
            "\"albumArtist\":${q(origin.albumArtist)}," +
            "\"date\":${q(origin.date)}," +
            "\"trackNumber\":${origin.trackNumber}," +
            "\"trackTotal\":${origin.trackTotal}," +
            "\"discNumber\":${origin.discNumber}," +
            "\"discTotal\":${origin.discTotal}," +
            "\"genre\":${q(origin.genre)}," +
            "\"label\":${q(origin.label)}," +
            "\"isrc\":${q(origin.isrc)}," +
            "\"copyright\":${q(origin.copyright)}," +
            "\"composer\":${q(origin.composer)}," +
            "\"barcode\":${q(origin.barcode)}," +
            "\"version\":${q(origin.version)}," +
            "\"work\":${q(origin.work)}," +
            "\"duration\":$durationSec," +
            "\"explicit\":${origin.explicit}," +
            "\"formatId\":$formatId}"
    }

    /**
     * Which tiers the backend will actually convert this track to, asked the
     * same way the site's own download page asks before offering its quality
     * picker (`/api/qobuz/formats?url=…&bd=…&sr=…`). A track with no 24-bit
     * master simply omits those tiers, and queueing one anyway is a refusal
     * that costs a round trip to learn — one per tier, on every play.
     *
     * Best-effort by design: null on any failure, and the caller then queues
     * exactly as it would have without this, so a formats outage changes
     * nothing but speed.
     */
    suspend fun availableFormats(
        qobuzUrl: String,
        bitDepth: Double?,
        samplingRate: Double?,
    ): Set<Int>? {
        if (qobuzUrl.isBlank()) return null
        return try {
            val params = StringBuilder("url=${encode(qobuzUrl)}")
            if (bitDepth != null && bitDepth > 0) params.append("&bd=$bitDepth")
            if (samplingRate != null && samplingRate > 0) params.append("&sr=$samplingRate")
            val body = get("$base/api/qobuz/formats?$params")
            json.decodeFromString<FormatsResponse>(body).formats
                .map { it.id }
                .filter { it == F_24_192 || it == F_24_96 || it == F_16_44 || it == F_MP3_320 }
                .toSet()
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            null
        }
    }

    private suspend fun queueJob(token: String, origin: Origin, formatId: Int): String? {
        val payload = queuePayload(origin, formatId)
        return try {
            val request = Request.Builder()
                .url("${base}/api/qobuz/queue")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .browserHeaders()
                .header("Accept", "application/json, text/plain, */*")
                .header("X-DL-Token", token)
                .header("Referer", "${base}/en/qobuz")
                .header("Origin", base)
                .withCookies()
                .build()
            val (code, body) = client.newCall(request).execute().use { response ->
                response.code to
                    (runCatching { response.body?.string() }.getOrNull()?.takeIf { it.isNotBlank() })
            }
            if (code != 200 || body == null) {
                noteFailure("queue refused (HTTP $code): ${(body ?: "empty").take(120)}")
                return null
            }
            json.decodeFromString<QueueResponse>(body).jobId?.takeIf { it.isNotBlank() }
                ?: run {
                    noteFailure("queue answered without a job id: ${body.take(120)}")
                    null
                }
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            noteFailure("queue failed: ${e.message}")
            null
        }
    }

    private suspend fun pollJob(
        jobId: String,
        formatId: Int,
        // Both catalogues answer with the same {status, file, pretty_name}
        // shape and put their finished files under their own prefix, so the
        // difference is two strings rather than a second copy of this loop.
        jobsPath: String = "/api/qobuz/jobs/$jobId",
        filesPrefix: String = "/qobuz/files",
    ): ResolvedFile? {
        var lastStatus: String? = null
        var stagnant = 0
        var pollErrors = 0
        repeat(maxPollsPerFormat) {
            coroutineContext.ensureActive()
            delay(pollDelayMs)
            val job = try {
                val body = get("$base$jobsPath", withCookies = true)
                json.decodeFromString<JobResponse>(body)
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                // One blip on a phone radio is weather, not a verdict — but
                // three in a row means the backend is gone for this job.
                pollErrors++
                if (pollErrors >= maxPollErrors) {
                    noteFailure("job $jobId poll failed repeatedly")
                    return null
                }
                return@repeat
            }
            pollErrors = 0
            if (job.status == lastStatus) {
                stagnant++
            } else {
                lastStatus = job.status
                stagnant = 0
            }
            // A job that sits in one non-terminal state for a full minute is
            // not being served — move on to the next format/row rather than
            // spending the whole budget watching it. A job whose status
            // keeps changing is progressing and keeps the full budget.
            if (stagnant >= stagnantPolls && job.status != "done") {
                noteFailure("job $jobId stalled at '${job.status}'")
                return null
            }
            when (job.status) {
                "done" -> {
                    val file = job.file?.takeIf { it.isNotBlank() } ?: run {
                        noteFailure("job $jobId finished with no file")
                        return null
                    }
                    // Both segments encoded exactly like the site's own
                    // client does (encodeURIComponent on each). The file
                    // name routinely carries spaces and punctuation, and a
                    // raw space in the path is a dead URL — which is what a
                    // finished conversion that never plays looks like.
                    val name = encodeSegment(job.prettyName?.takeIf { it.isNotBlank() } ?: file)
                    clearLastFailure()
                    return ResolvedFile(
                        url = "$base$filesPrefix/${encodeSegment(file)}?name=$name",
                        formatId = formatId,
                    )
                }
                "error" -> {
                    noteFailure("job $jobId errored")
                    return null
                }
                else -> Unit // queued/working: next round
            }
        }
        return null
    }

    /**
     * Best-first conversion across [formatIds] inside one wall-clock budget.
     * Null covers everything: no token, queue refused, jobs erroring, budget
     * spent. Every one of those is a miss for the caller to walk past, never
     * a fault.
     */
    suspend fun resolveStream(
        origin: Origin,
        formatIds: List<Int>,
        budgetMs: Long = RESOLVE_BUDGET_MS,
    ): ResolvedFile? {
        if (origin.qobuzUrl.isBlank() || formatIds.isEmpty()) return null
        // Skip tiers the track was never mastered for instead of queueing
        // each one to be refused in turn. Unknown (formats outage, legacy
        // rows without master figures) keeps the full order — see
        // [availableFormats].
        val known = availableFormats(origin.qobuzUrl, origin.bitDepth, origin.samplingRate)
        val tried = known?.let { ids -> formatIds.filter { it in ids } } ?: formatIds
        if (tried.isEmpty()) {
            val losslessAsked = formatIds.any { it == F_24_192 || it == F_24_96 || it == F_16_44 }
            noteFailure(
                if (losslessAsked) "qobuz holds no lossless master for this track (tiers ${known!!.sorted()})"
                else "qobuz offers none of the requested tiers (has ${known!!.sorted()})",
            )
            return null
        }
        val token = prepareToken() ?: return null
        val deadline = System.currentTimeMillis() + budgetMs
        for (formatId in tried) {
            coroutineContext.ensureActive()
            if (System.currentTimeMillis() >= deadline) {
                noteFailure("conversion budget spent before a file landed")
                break
            }
            val jobId = queueJob(token, origin, formatId) ?: continue
            val file = pollJob(jobId, formatId)
            if (file != null) return file
        }
        return null
    }

    /** Older call shape, kept for tests: metadata unknown, payload minimal. */
    suspend fun resolveStream(
        qobuzUrl: String,
        title: String,
        artist: String,
        formatIds: List<Int>,
        budgetMs: Long = RESOLVE_BUDGET_MS,
    ): ResolvedFile? = resolveStream(
        Origin(qobuzUrl, title, artist, null),
        formatIds,
        budgetMs,
    )

    // ── Matching ──────────────────────────────────────────────────────
    // Ported from the plugin's title logic: strip featured/remaster/version
    // noise and refuse a result carrying a modifier the request lacks, so a
    // live cut never stands in for the studio recording.

    private val MODIFIERS = listOf(
        "karaoke", "remix", "live", "instrumental", "version", "edit",
        "acoustic", "reprise", "demo", "session", "cover", "tribute",
        "orchestral", "piano",
    )

    fun normalizeTitle(title: String): String {
        var t = title.replace(Regex("[áàäâã]"), "a")
            .replace(Regex("[éèëê]"), "e")
            .replace(Regex("[íìïî]"), "i")
            .replace(Regex("[óòöôõ]"), "o")
            .replace(Regex("[úùüû]"), "u")
            .replace("ñ", "n").replace("Ñ", "N")
            .trim()
        t = t.substringBefore("(").trim()
        t = t.substringBefore("[").trim()
        t = t.substringBefore(" - ").trim()
        val lower = t.lowercase()
        t = t.substringBefore(" feat", t).trim().let {
            if (lower.contains(" feat")) it.substring(0, lower.indexOf(" feat")).trim() else it
        }
        val lower2 = t.lowercase()
        t = if (" ft." in lower2) t.substring(0, lower2.indexOf(" ft.")).trim() else t
        val lower3 = t.lowercase()
        t = if ("remaster" in lower3) t.substring(0, lower3.indexOf("remaster")).trim() else t
        return t
    }

    fun titlesMatch(search: String, result: String): Boolean {
        val s = normalizeTitle(search).lowercase()
        val r = normalizeTitle(result).lowercase()
        if (s.isEmpty() || r.isEmpty()) return false
        return r.contains(s) || s.contains(r)
    }

    fun hasModifier(title: String): Boolean {
        val lower = title.lowercase()
        return MODIFIERS.any { it in lower }
    }

    /** A result is playable for a request when titles match and it adds no new modifier. */
    fun isPlayableMatch(request: String, result: String): Boolean {
        if (!titlesMatch(request, result)) return false
        return !(hasModifier(result) && !hasModifier(request))
    }

    // ── Transport ─────────────────────────────────────────────────────

    /**
     * Every request carries a full browser header set, not just a UA
     * string: bot defenses score header presence (Accept-Language,
     * Sec-Fetch-*) alongside TLS, and OkHttp's bare default (`okhttp/x`)
     * plus a missing language is two free tells. Same neutral Chrome
     * identity the addon client sends.
     *
     * The Sec-Fetch trio states what a real `fetch()` from the site's own
     * page sends: same-origin, cors, empty destination. `none`/`no-cors`
     * belong on navigations and subresources, and stating them on an XHR
     * is a small, free bot tell.
     */
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private fun Request.Builder.browserHeaders(): Request.Builder =
        header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Dest", "empty")

    private suspend fun get(url: String, withCookies: Boolean = false): String {
        val request = Request.Builder()
            .url(url)
            .browserHeaders()
            .header("Referer", "${base}/")
            .header("Origin", base)
            .let { if (withCookies) it.withCookies() else it }
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Empty response")
        }
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    /**
     * `encodeURIComponent`, as close as the JDK gets: [URLEncoder] is form
     * encoding, where a space becomes `+` — correct in a query value, a
     * literal plus in a path segment. The file segment of a download URL is
     * a path, so `+` there addresses a file that does not exist.
     */
    fun encodeSegment(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun q(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ") + "\""
}
