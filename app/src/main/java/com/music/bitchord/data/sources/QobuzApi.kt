package com.music.bitchord.data.sources

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
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
 * Qobuz's own API, spoken directly.
 *
 * This is the route that finally has no bot gate. flacdownloader fronts
 * Qobuz behind a Turnstile challenge and SpotiFLAC's community gateway is
 * Turnstile-gated too, so both refuse anything that is not a human in a
 * browser. Qobuz itself does not: it signs catalogue calls with a *public*
 * app id and secret, which is why search works with no account at all, and
 * it only asks for a `user_auth_token` when a caller wants the actual audio
 * bytes. That token comes from the listener's own credentials, exchanged once.
 *
 * So the split is deliberate and is the whole point:
 *
 *  - **Search** — app id + secret only. No account, no network gate.
 *  - **Stream** — the listener's own `user_auth_token`. Their account, their
 *    tier, their call; nothing is shared and nothing is proxied.
 *
 * FLAC and hi-res are gated by the *subscription tier* of that account, not
 * by any challenge, which is why a free login authenticates cleanly and then
 * only ever offers 320kbps. The tier is reported honestly rather than
 * guessed at — see [tierName].
 */
object QobuzApi {

    private const val TAG = "BitChord"
    const val BASE = "https://www.qobuz.com"

    /**
     * Qobuz's published web client id, and its secret. These ship inside the
     * Qobuz website itself and are not per-account; every third-party client
     * uses them. Only the user's own token distinguishes one listener's calls
     * from another's.
     */
    private const val APP_ID = "712109809"
    private const val APP_SECRET = "589be88e4538daea11f509d29e4a23b1"

    /** Qobuz format ids, best first — same set the other clients request. */
    const val F_24_192 = 27
    const val F_24_96 = 7
    const val F_16_44 = 6
    const val F_MP3_320 = 5

    val LOSSLESS_FORMATS = listOf(F_24_192, F_24_96, F_16_44)

    /** Test hook: point the client at a MockWebServer; reset after each case. */
    internal var baseOverride: String? = null
    private val base: String get() = baseOverride?.trimEnd('/') ?: BASE

    /** Packed ids for direct-Qobuz rows. The `q:` prefix keeps them apart from
     * flacdownloader rows, which carry the same global Qobuz track ids bare. */
    fun packId(id: Long): String = "q:$id"

    /** What a direct-Qobuz search row needs later at queue time. */
    data class Origin(val id: Long)

    private val origins = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Origin>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Origin>) = size > 128
        },
    )

    fun memoizeOrigin(packed: String, origin: Origin) {
        origins[packed] = origin
    }

    fun originOf(packed: String): Origin? = origins[packed]

    internal fun resetForTests() {
        baseOverride = null
        origins.clear()
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val client: OkHttpClient by lazy {
        Http.client.newBuilder()
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    // ── Wire shapes ───────────────────────────────────────────────────

    @Serializable
    data class LoginResponse(
        @SerialName("user_auth_token") val userAuthToken: String? = null,
        @SerialName("user") val user: LoginUser? = null,
    )

    @Serializable
    data class LoginUser(
        @SerialName("id") val id: Long = 0,
        @SerialName("login") val login: String = "",
        @SerialName("email") val email: String = "",
        @SerialName("credential") val credential: LoginCredential? = null,
        @SerialName("subscription") val subscription: LoginSubscription? = null,
    )

    @Serializable
    data class LoginCredential(
        @SerialName("parameters") val parameters: List<CredentialParam> = emptyList(),
    )

    @Serializable
    data class CredentialParam(
        @SerialName("name") val name: String = "",
        @SerialName("value") val value: String = "",
    )

    @Serializable
    data class LoginSubscription(
        @SerialName("offer") val offer: String = "",
        @SerialName("is_active") val isActive: Boolean = false,
        @SerialName("hard_expiration") val hardExpiration: Long? = null,
    )

    @Serializable
    data class SearchResponse(
        /**
         * Qobuz wraps the rows in a page object — `{"tracks":{"limit":…,
         * "items":[…]}}` — while some of its own endpoints answer with a bare
         * array. Modelled as the page it actually sends and read leniently,
         * because guessing wrong here is invisible until a search silently
         * returns nothing: the decode throws, the catch swallows it, and the
         * source looks empty rather than broken.
         */
        @SerialName("tracks") val tracks: TrackPage = TrackPage(),
    )

    @Serializable
    data class TrackPage(
        @SerialName("items") val items: List<QobuzTrack> = emptyList(),
        @SerialName("total") val total: Int = 0,
    ) {
        /** True when the endpoint answered with a bare array instead. */
        val looksBare: Boolean get() = items.isEmpty() && total == 0
    }

    @Serializable
    data class QobuzTrack(
        @SerialName("id") val id: Long = 0,
        @SerialName("title") val title: String = "",
        @SerialName("artist") val artist: ArtistRef = ArtistRef(),
        @SerialName("performer") val performer: PerformerRef = PerformerRef(),
        @SerialName("album") val album: AlbumRef = AlbumRef(),
        @SerialName("duration") val durationSec: Int = 0,
        @SerialName("maximum_bit_depth") val maximumBitDepth: Int = 0,
        @SerialName("maximum_sampling_rate") val maximumSamplingRate: Double = 0.0,
        @SerialName("hires") val hires: Boolean = false,
        @SerialName("isrc") val isrc: String? = null,
        @SerialName("explicit") val explicit: Boolean = false,
        @SerialName("parental_warning") val parentalWarning: Boolean = false,
        @SerialName("url") val url: String? = null,
    ) {
        /** "Satyam" from the nested performer object Qobuz actually sends. */
        val artistName: String
            get() = artist.name.ifBlank { performer.name }
        val isExplicit: Boolean get() = explicit || parentalWarning
    }

    @Serializable
    data class ArtistRef(@SerialName("name") val name: String = "")

    @Serializable
    data class PerformerRef(
        @SerialName("name") val name: String = "",
        @SerialName("id") val id: Long = 0,
    )

    @Serializable
    data class AlbumRef(
        @SerialName("title") val title: String = "",
        @SerialName("maximum_bit_depth") val maximumBitDepth: Int = 0,
        @SerialName("image") val image: AlbumImage? = null,
    ) {
        /** The largest cover Qobuz hands over, for rows that otherwise show none. */
        val artwork: String? get() = image?.large?.takeIf { it.isNotBlank() }
    }

    @Serializable
    data class AlbumImage(
        @SerialName("small") val small: String? = null,
        @SerialName("thumbnail") val thumbnail: String? = null,
        @SerialName("large") val large: String? = null,
    )

    /** A resolved, playable file for one track. */
    data class ResolvedFile(
        val url: String,
        val formatId: Int,
        val sampleRateHz: Int,
        val bitDepth: Int,
    ) {
        val isLossless: Boolean get() = formatId != F_MP3_320
        val kbps: Int
            get() = when (formatId) {
                F_24_192 -> 2_304
                F_24_96 -> 2_304
                F_16_44 -> 1_411
                else -> 320
            }
    }

    // ── Catalogue (no account needed) ─────────────────────────────────

    /**
     * Qobuz catalogue search. Signed with the public app credentials, so this
     * works with nothing configured and nothing stored — which is the point:
     * discovery must not depend on a login the listener may not have.
     */
    suspend fun search(query: String, limit: Int = 25): List<QobuzTrack> {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        val path = "track/search"
        val params = "format=json&query=${encode(q)}&limit=${limit.coerceIn(1, 100)}"
        val body = signedGet(path, params)
        return try {
            json.decodeFromString<SearchResponse>(body).tracks.items
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            // Not a page: fall back to a bare array before giving up, because
            // "the shape changed" and "Qobuz has nothing" must not look alike.
            val bare = runCatching {
                json.decodeFromString<List<QobuzTrack>>(body)
            }.getOrNull()
            if (bare != null) {
                TrackLog.d(TAG, "  ✓ qobuz search answered with a bare row array")
                bare
            } else {
                TrackLog.d(TAG, "  ✗ qobuz search failed: ${e.message}")
                emptyList()
            }
        }
    }

    // ── Session (the listener's own account) ───────────────────────────

    /**
     * Exchanges the listener's Qobuz credentials for a session: the
     * `user_auth_token` plus the user record carrying the subscription tier.
     *
     * The password goes over the wire as an MD5 digest, exactly as Qobuz's own
     * web client sends it; the plaintext is never transmitted and never
     * stored. Returns null for a wrong password, a network failure, or an
     * empty configuration, and says which, because "no FLAC" and "bad
     * password" are very different bugs.
     */
    suspend fun login(email: String, password: String): LoginResponse? {
        if (email.isBlank() || password.isBlank()) {
            TrackLog.w(TAG, "  ✗ qobuz: no credentials entered")
            return null
        }
        val body = md5(password)
        val payload = """{"email":${q(email)},"password":"$body","format":"json"}"""
        return try {
            val request = Request.Builder()
                .url("$base/api.json/0.2/user/login?app_id=$APP_ID")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .header("X-App-Id", APP_ID)
                .browserHeaders()
                .build()
            val raw = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
            val answer = json.decodeFromString<LoginResponse>(raw)
            if (answer.userAuthToken.isNullOrBlank()) {
                TrackLog.w(TAG, "  ✗ qobuz: password rejected")
                return null
            }
            TrackLog.d(TAG, "  ✓ qobuz signed in as ${answer.user?.email ?: email}")
            answer
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            TrackLog.d(TAG, "  ✗ qobuz login failed: ${e.message}")
            null
        }
    }

    /** The subscription tier's display name, for honest reporting. */
    fun tierName(user: LoginUser?): String = when {
        user == null -> "not signed in"
        user.subscription?.isActive == true -> user.subscription.offer.ifBlank { "active" }
        else -> "free (no lossless)"
    }

    // ── Stream issuance ───────────────────────────────────────────────

    /**
     * The playable file for [trackId] at the best tier [userToken] is entitled
     * to, trying [formatIds] best-first. Null when the account cannot have it,
     * which is a normal answer for a free tier rather than a fault.
     */
    suspend fun resolveFile(
        trackId: Long,
        userToken: String,
        formatIds: List<Int> = LOSSLESS_FORMATS,
    ): ResolvedFile? {
        if (trackId <= 0L || formatIds.isEmpty()) return null
        for (formatId in formatIds) {
            coroutineContext.ensureActive()
            val body = try {
                signedGet(
                    "track/getFile",
                    "format=json&track_id=$trackId&intent=stream&format_id=$formatId",
                    userToken,
                )
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                TrackLog.d(TAG, "  ✗ qobuz getFile($formatId) failed: ${e.message}")
                continue
            }
            val url = bodyField(body, "url")
            if (url.isNullOrBlank()) {
                TrackLog.d(TAG, "  ✗ qobuz withheld format $formatId for track $trackId")
                continue
            }
            return ResolvedFile(
                url = url,
                formatId = formatId,
                sampleRateHz = when (formatId) {
                    F_24_192 -> 192_000
                    F_24_96 -> 96_000
                    else -> 44_100
                },
                bitDepth = if (formatId == F_16_44) 16 else 24,
            )
        }
        return null
    }

    // ── Transport ─────────────────────────────────────────────────────

    /**
     * A signed `GET` against the catalogue API.
     *
     * Qobuz wants `request_sig = md5(appId + rqt_id + request_ts + path +
     * query + secret [+ userAuthToken])`, with the `user_auth_token` appended
     * only when one is in play — the signature covers exactly what is sent, so
     * including a token the request does not carry invalidates it.
     */
    private suspend fun signedGet(path: String, params: String, userToken: String? = null): String {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val rqtId = "100"
        val tail = userToken ?: ""
        val sig = md5(APP_ID + rqtId + ts + path + params + APP_SECRET + tail)
        val auth = buildString {
            append("app_id=").append(APP_ID)
            append("&format=json")
            append("&request_ts=").append(ts)
            append("&request_sig=").append(sig)
            if (userToken != null) append("&user_auth_token=").append(userToken)
        }
        val url = "$base/api.json/0.2/$path?$auth&$params"
        val request = Request.Builder()
            .url(url)
            .header("X-App-Id", APP_ID)
            .browserHeaders()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Empty response")
        }
    }

    private fun bodyField(body: String, field: String): String? =
        Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)

    private val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private fun Request.Builder.browserHeaders(): Request.Builder =
        header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun md5(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun q(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"") + "\""
}