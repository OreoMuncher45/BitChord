package com.music.bitchord.data.qobuz

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * flacdownloader.com's Qobuz conversion queue, as their own frontend speaks
 * it (deobfuscated from `assets/index-*.js`; mirrors the community
 * spotube-plugin-flac-audio protocol, which died with the site's Turnstile
 * gate because it only ever `GET /prepare` with nothing in hand).
 *
 * The ritual, in order:
 * 1. `GET /api/qobuz/search` — free catalogue, no auth, no challenge.
 * 2. `POST /prepare {sp}` — resume a saved session, no Turnstile. Fresh
 *    unlocks (`POST /prepare {cf}` with a Turnstile token) happen in the
 *    in-app browser sheet, which runs the site's own invisible widget and
 *    bridges `{t, sp}` out — see `QobuzUnlockSheet`.
 * 3. `POST /api/qobuz/queue {…metadata, formatId}` + `X-DL-Token` — a
 *    conversion job id.
 * 4. `GET /api/qobuz/jobs/{id}` polled — the finished file URL.
 *
 * Format ids are the site's own: 27 = 24/192, 7 = 24/96, 6 = 16/44.1 FLAC,
 * 5 = MP3 320. Their Qobuz converter is intermittently down server-side
 * (HTTP 503 + their own "BETA, fallos puntuales" strings) — that surfaces
 * as [QueueResult.ConverterDown], a wait, never a fault.
 */
object QobuzQueueApi {

    private const val TAG = "BitChord"

    const val BASE = "https://flacdownloader.com"
    const val BASE_HOST = "flacdownloader.com"

    /** Test seam: MockWebServer root, e.g. `http://localhost:1234`. */
    var baseOverride: String? = null
    fun resetForTests() {
        baseOverride = null
        synchronized(jobMemoLock) { jobMemo.clear() }
    }

    private val base: String get() = baseOverride?.trimEnd('/') ?: BASE

    const val FORMAT_HIRES_192 = 27
    const val FORMAT_HIRES_96 = 7
    const val FORMAT_FLAC_CD = 6
    const val FORMAT_MP3_320 = 5

    /** Conversion tiers worth asking for, best first. */
    val LOSSLESS_FORMATS = listOf(FORMAT_HIRES_192, FORMAT_HIRES_96, FORMAT_FLAC_CD)

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val quickClient by lazy {
        Http.client.newBuilder()
            .callTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    private val jsonMediaType = "application/json".toMediaType()

    private fun get(path: String): JsonObject {
        val request = Request.Builder()
            .url(base + path)
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "$BASE/")
            .header("Origin", BASE)
            .get()
            .build()
        return quickClient.newCall(request).execute().use { response ->
            val text = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} on $path")
            runCatching { json.parseToJsonElement(text) as JsonObject }
                .getOrElse { throw IOException("bad JSON on $path") }
        }
    }

    private fun post(path: String, payload: String, token: String? = null): Pair<Int, JsonObject?> {
        val builder = Request.Builder()
            .url(base + path)
            .header("User-Agent", BROWSER_UA)
            .header("Referer", "$BASE/")
            .header("Origin", BASE)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/plain, */*")
            .post(payload.toRequestBody(jsonMediaType))
        if (token != null) builder.header("X-DL-Token", token)
        return builder.build().let { request ->
            quickClient.newCall(request).execute().use { response ->
                val text = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                val obj = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
                response.code to obj
            }
        }
    }

    // ── Catalogue (free, unchallenged) ──────────────────────────────────

    data class QobuzTrack(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val cover: String?,
        val durationMs: Long?,
        val isrc: String,
        val url: String,
        val bitDepth: Int?,
        val samplingRateHz: Double?,
        val hires: Boolean,
    )

    suspend fun searchTracks(query: String, limit: Int = 25): List<QobuzTrack> =
        withContext(Dispatchers.IO) {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val obj = get("/api/qobuz/search?q=$encoded&offset=0&limit=${limit.coerceIn(1, 100)}")
            (obj["tracks"] as? JsonArray).orEmpty().mapNotNull { it.toTrack() }
        }

    private fun kotlinx.serialization.json.JsonElement.toTrack(): QobuzTrack? {
        val o = this as? JsonObject ?: return null
        fun str(key: String): String = (o[key] as? JsonPrimitive)?.content.orEmpty()
        val id = (o["id"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
        if (str("title").isBlank()) return null
        return QobuzTrack(
            id = id,
            title = str("title"),
            artist = str("artist"),
            album = str("album"),
            cover = str("cover").takeIf { it.isNotBlank() },
            durationMs = (o["durationMs"] as? JsonPrimitive)?.content?.toLongOrNull(),
            isrc = str("isrc"),
            url = str("url").ifBlank { "https://open.qobuz.com/track/$id" },
            bitDepth = (o["bitDepth"] as? JsonPrimitive)?.content?.toIntOrNull(),
            samplingRateHz = (o["samplingRate"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
            hires = (o["hires"] as? JsonPrimitive)?.content == "true",
        )
    }

    // ── Token (sp resume natively; cf mint lives in the unlock sheet) ──

    sealed interface TokenOutcome {
        data class Ready(val token: String, val sp: String?) : TokenOutcome
        /** No session and no way to mint one here — open the unlock sheet. */
        data object Challenge : TokenOutcome
        data class Failed(val reason: String) : TokenOutcome
    }

    /**
     * Resumes [sp] into a queue token. The one call the app makes natively:
     * no Turnstile runs outside a real browser, so a blank session answers
     * [TokenOutcome.Challenge] and the sheet takes it from there.
     */
    suspend fun prepare(sp: String?): TokenOutcome = withContext(Dispatchers.IO) {
        if (sp.isNullOrBlank()) return@withContext TokenOutcome.Challenge
        val (code, obj) = try {
            post("/prepare", """{"sp":"$sp"}""")
        } catch (e: Exception) {
            return@withContext TokenOutcome.Failed(e.message ?: "No answer")
        }
        if (code == 403) return@withContext TokenOutcome.Challenge
        if (code != 200 || obj == null) return@withContext TokenOutcome.Failed("HTTP $code")
        val token = (obj["t"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: return@withContext TokenOutcome.Failed("No token")
        val rotated = (obj["sp"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        TokenOutcome.Ready(token, rotated ?: sp)
    }

    // ── Queue + poll ────────────────────────────────────────────────────

    data class QueuedFile(
        val url: String,
        val container: String,
        val bitrate: Int?,
        val sampleRateHz: Int?,
        val bitDepth: Int?,
    )

    sealed interface QueueOutcome {
        data class Job(val jobId: String) : QueueOutcome
        /** Token rejected — resume the session, then retry once. */
        data object TokenDead : QueueOutcome
        /** Their converter is down (HTTP 503 as of writing) — wait, not fault. */
        data object ConverterDown : QueueOutcome
        data class Failed(val reason: String) : QueueOutcome
    }

    suspend fun queue(
        track: QobuzTrack,
        formatId: Int,
        token: String,
    ): QueueOutcome = withContext(Dispatchers.IO) {
        val payload = buildString {
            append("""{"url":"${track.url}","title":${jsonString(track.title)},""")
            append(""""artist":${jsonString(track.artist)},"formatId":$formatId}""")
        }
        val (code, obj) = try {
            post("/api/qobuz/queue", payload, token)
        } catch (e: Exception) {
            return@withContext QueueOutcome.Failed(e.message ?: "No answer")
        }
        if (code == 401 || code == 403) return@withContext QueueOutcome.TokenDead
        if (code == 503) return@withContext QueueOutcome.ConverterDown
        if (code != 200 || obj == null) {
            val err = (obj?.get("error") as? JsonPrimitive)?.content
            return@withContext QueueOutcome.Failed(err ?: "HTTP $code")
        }
        val id = (obj["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: return@withContext QueueOutcome.Failed("No job")
        QueueOutcome.Job(id)
    }

    private fun jsonString(value: String): String =
        JsonPrimitive(value).toString()

    sealed interface JobOutcome {
        data class Done(val file: QueuedFile) : JobOutcome
        data class Failed(val reason: String) : JobOutcome
        data object Timeout : JobOutcome
    }

    suspend fun pollJob(
        jobId: String,
        formatId: Int,
        budgetMs: Long = 120_000L,
        intervalMs: Long = 2_000L,
    ): JobOutcome = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            val obj = try {
                get("/api/qobuz/jobs/$jobId")
            } catch (e: Exception) {
                TrackLog.d(TAG, "  ✗ qobuz job poll failed: ${e.message}")
                return@withContext JobOutcome.Failed(e.message ?: "No answer")
            }
            val status = (obj["status"] as? JsonPrimitive)?.content.orEmpty()
            if (status == "done" && (obj["file"] as? JsonPrimitive)?.content?.isNotBlank() == true) {
                return@withContext JobOutcome.Done(fileFor(obj, formatId))
            }
            if (status == "error") {
                val err = (obj["error"] as? JsonPrimitive)?.content ?: "Conversion failed"
                return@withContext JobOutcome.Failed(err)
            }
            delay(intervalMs)
        }
        return@withContext JobOutcome.Timeout
    }

    private fun fileFor(obj: JsonObject, formatId: Int): QueuedFile {
        fun str(key: String): String = (obj[key] as? JsonPrimitive)?.content.orEmpty()
        val filename = str("file")
        val pretty = str("pretty_name").ifBlank { filename }
        val url = "$base/qobuz/files/$filename?name=$pretty"
        return if (formatId == FORMAT_MP3_320) {
            QueuedFile(url, "mp3", 320, 44100, 16)
        } else {
            // The plugin hardcodes the CD PCM rate here; the decoder's own
            // figures overwrite it at play time (see NerdStats), so this is a
            // placeholder, never a claim.
            QueuedFile(
                url, "flac", null,
                sampleRateHz = if (formatId == FORMAT_HIRES_192) 192000
                else if (formatId == FORMAT_HIRES_96) 96000 else 44100,
                bitDepth = if (formatId == FORMAT_HIRES_192 || formatId == FORMAT_HIRES_96) 24 else 16,
            )
        }
    }

    // ── Origin memo (stream() never guesses) ────────────────────────────

    data class Origin(val url: String, val title: String, val artist: String, val durationMs: Long?)

    private val origins = mutableMapOf<String, Origin>()

    @Synchronized
    fun memoizeOrigin(packedId: String, origin: Origin) {
        origins[packedId] = origin
    }

    @Synchronized
    fun originOf(packedId: String): Origin? = origins[packedId]

    fun packId(qobuzId: Long): String = "qobuz:$qobuzId"

    /**
     * Conversion jobs already minted, by track + format. A poll that runs
     * out of budget leaves the job here instead of losing it: the next
     * round re-polls the same job rather than minting a second conversion
     * of the same track. Cleared when the job resolves either way.
     */
    private val jobMemoLock = Any()
    private val jobMemo = mutableMapOf<String, String>()

    private fun jobKey(packedId: String, formatId: Int) = "$packedId::$formatId"

    fun memoizeJob(packedId: String, formatId: Int, jobId: String) {
        synchronized(jobMemoLock) { jobMemo[jobKey(packedId, formatId)] = jobId }
    }

    fun jobOf(packedId: String, formatId: Int): String? =
        synchronized(jobMemoLock) { jobMemo[jobKey(packedId, formatId)] }

    fun forgetJob(packedId: String, formatId: Int) {
        synchronized(jobMemoLock) { jobMemo.remove(jobKey(packedId, formatId)) }
    }

    fun unpackId(packedId: String): Long? =
        packedId.removePrefix("qobuz:").toLongOrNull()
}
