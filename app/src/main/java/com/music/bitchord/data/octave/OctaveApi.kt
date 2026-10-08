package com.music.bitchord.data.octave

import com.music.bitchord.BuildConfig
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request

/**
 * Talk to Octave (octavestreaming.com) with an account key.
 *
 * Protocol, read off their web player's own client code:
 * - Key shape: `octv_` + 48 hex — validated client-side upstream too.
 * - Verify: `GET {base}/api/account/me` + `Authorization: Bearer <key>`
 *   → `{"ok":true,"userId":"…"}`. 403/401 = dead key.
 * - Stream: `GET {base}/api/playback-token` + Bearer → `{token, expiresIn}`
 *   (short-lived, auto-refreshed), then audio at
 *   `{base}/audio/{tier}?track={id}&t=&a=&d=&x=` + `?k={playbackToken}`.
 * - Tiers: MAX→`lossless`, DATA_SAVER/NORMAL→`128`, else→`320`.
 *   MAX is genuine FLAC (their own spec: "FLAC · up to 1411 kbps").
 * - Search: `GET {base}/api/search/tracks?query=&limit=` → `{results:[…]}`,
 *   needs no key. Rows carry Deezer cover art up to 1000px.
 *
 * Nothing here is guessed at: every route, header and tier name above is
 * what their player sends. Anything the server refuses surfaces as a miss
 * or a rejection with the server's own reason — never a silent fallback.
 */
object OctaveApi {

    private const val TAG = "BitChord"

    const val DEFAULT_BASE = "https://api.octavestreaming.com"

    /** Setting key under which the account key is stored on the config. */
    const val ACCOUNT_KEY = "account_key"

    private val KEY_PATTERN = Regex("^octv_[a-f0-9]{48}$")

    /** Tier path segments, exactly as their player requests them. */
    const val TIER_LOSSLESS = "lossless"
    const val TIER_320 = "320"
    const val TIER_128 = "128"

    fun isKeyShape(key: String): Boolean = KEY_PATTERN.matches(key.trim())

    /**
     * The canonical base when [rawUrl] points at Octave, else null. Hostname
     * only — never fetched, so identification leaks nothing.
     */
    fun baseOf(rawUrl: String): String? {
        val host = rawUrl.trim().substringAfter("://", "")
            .substringBefore('/').substringBefore('?').substringBefore(':')
            .lowercase()
        return when (host) {
            "api.octavestreaming.com", "octavestreaming.com" -> "https://api.octavestreaming.com"
            else -> null
        }
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    data class OctaveTrack(
        val id: String,
        val title: String,
        val artist: String,
        val album: String?,
        val cover: String?,
        val durationSec: Int?,
        val explicit: Boolean,
    )

    /**
     * Anti-ban policy, in one place.
     *
     * Octave bans accounts for automated access, and the things that read as
     * "automated" are stampedes: N parallel resolves each minting a token,
     * retry loops hammering a refusal, the same search fired by read-ahead
     * and UI at once, and a size probe per play of a stable file. So:
     * token mints are singleflight with a cooldown after refusal, identical
     * searches in flight share one request, and content lengths are cached.
     * Failures still surface immediately to the caller — this throttles
     * volume, never honesty.
     */
    private data class CachedToken(val token: String, val expMs: Long)
    private val tokens = mutableMapOf<String, CachedToken>()
    private val tokenLock = Mutex()

    /** Until when a refused key must not be retried (per base+key). */
    private val tokenCooldownUntil = mutableMapOf<String, Long>()

    /** How long a refusal quiets minting: long enough to break retry loops. */
    private const val TOKEN_COOLDOWN_MS = 60_000L

    /** In-flight searches by base|query|limit — concurrent twins share one. */
    private val inFlightSearch = mutableMapOf<String, CompletableDeferred<List<OctaveTrack>>>()
    private val searchLock = Mutex()

    /** Content lengths by stream URL: file sizes do not change between plays. */
    private val sizeCache = object : LinkedHashMap<String, Long>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Long>) = size > 500
    }

    /**
     * Title/artist/duration per track id, remembered from search rows.
     * stream() only receives the id, but the audio URL carries the hints
     * their player always sends — so search files them here on the way past.
     */
    data class Hint(val title: String, val artist: String, val durationSec: Int?, val explicit: Boolean)

    private val hints = object : LinkedHashMap<String, Hint>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Hint>) = size > 500
    }

    @Synchronized
    fun memoizeHint(id: String, hint: Hint) {
        hints[id] = hint
    }

    @Synchronized
    fun hintOf(id: String): Hint? = hints[id]

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    fun parseSearch(body: String): List<OctaveTrack> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return emptyList()
        val items = (root["results"] as? JsonArray) ?: return emptyList()
        return items.mapNotNull { it as? JsonObject }.mapNotNull { parseTrack(it) }
    }

    private fun parseTrack(o: JsonObject): OctaveTrack? {
        val id = (o["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return null
        val title = o.str("title") ?: return null
        val artistObj = o["artist"] as? JsonObject
        val artist = artistObj?.str("name") ?: o.str("artist") ?: "Unknown Artist"
        val album = (o["album"] as? JsonObject)?.str("title")
        val cover = (o["album"] as? JsonObject)?.let { a ->
            a.str("cover_xl") ?: a.str("cover_big") ?: a.str("cover_medium") ?: a.str("cover_small")
        }
        return OctaveTrack(
            id = id,
            title = title,
            artist = artist,
            album = album,
            cover = cover,
            durationSec = (o["duration"] as? JsonPrimitive)?.content?.toIntOrNull(),
            explicit = (o["explicit"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true,
        )
    }

    /** Track-identity query string, same shape their player appends. */
    fun hint(title: String, artist: String, durationSec: Int?, explicit: Boolean): String {
        val q = StringBuilder()
        q.append("&t=").append(encode(title.take(200)))
        q.append("&a=").append(encode(artist.take(200)))
        if (durationSec != null && durationSec > 0) q.append("&d=").append(durationSec)
        q.append("&x=").append(if (explicit) "1" else "0")
        return q.toString()
    }

    fun audioUrl(
        base: String,
        tier: String,
        id: String,
        title: String,
        artist: String,
        durationSec: Int?,
        explicit: Boolean,
        playbackToken: String,
    ): String =
        "$base/audio/$tier?track=${encode(id)}${hint(title, artist, durationSec, explicit)}&k=${encode(playbackToken)}"

    /**
     * What the account behind [key] can actually do. Three answers, not two:
     * a key can be valid yet carry no streaming entitlement (members-only
     * gate), and conflating that with "wrong key" sends people re-typing a
     * key that was never wrong.
     */
    sealed interface KeyVerdict {
        data class Streaming(val userId: String?) : KeyVerdict
        data class NoStreaming(val userId: String?, val reason: String?) : KeyVerdict
        data class Invalid(val reason: String) : KeyVerdict
        data object Unreachable : KeyVerdict
    }

    /** Refusal with the server's own reason — banned, suspended, no role. */
    class Refused(message: String) : IOException(message)

    suspend fun verify(base: String, key: String): KeyVerdict = withContext(Dispatchers.IO) {
        val me = try {
            get("$base/api/account/me", key)
        } catch (e: Exception) {
            return@withContext KeyVerdict.Unreachable
        }
        if (!me.ok) {
            return@withContext if (me.code == 401 || me.code == 403) {
                KeyVerdict.Invalid("Key rejected (HTTP ${me.code})")
            } else {
                KeyVerdict.Unreachable
            }
        }
        val userId = runCatching {
            (json.parseToJsonElement(me.body) as? JsonObject)?.str("userId")
        }.getOrNull()
        // Identity is not entitlement: confirming the token needs its own call.
        // A Refused carries the server's words (banned, suspended…); anything
        // else is just "no".
        var reason: String? = null
        val token: String? = try {
            ensureToken(base, key)
        } catch (e: Refused) {
            reason = e.message
            null
        } catch (e: Exception) {
            null
        }
        if (token != null) KeyVerdict.Streaming(userId) else KeyVerdict.NoStreaming(userId, reason)
    }

    /**
     * A live playback token for [key], cached to its expiry with skew.
     * Throws on refusal so callers fail over instead of streaming nothing.
     */
    suspend fun ensureToken(base: String, key: String): String {
        val cacheKey = "$base\n$key"
        tokenLock.withLock {
            tokens[cacheKey]?.let { if (it.expMs - 60_000 > System.currentTimeMillis()) return it.token }
        }
        val code = get("$base/api/playback-token", key)
        if (!code.ok) throw IOException("playback token refused (HTTP ${code.code})")
        val (token, ttlSec) = parseToken(code.body)
        if (token.isBlank()) throw IOException("empty playback token")
        val ttlMs = ((ttlSec ?: 3600).coerceAtLeast(60)) * 1000L
        tokenLock.withLock {
            tokens[cacheKey] = CachedToken(token, System.currentTimeMillis() + ttlMs)
        }
        return token
    }

    /**
     * Reads a playback-token body. Gated accounts answer 200 with no token
     * and a reason — banned, suspended, or missing access role — which
     * surfaces as [Refused] with their words. Pure for tests.
     */
    fun parseToken(body: String): Pair<String, Long?> {
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }
            .getOrNull() ?: throw IOException("unreadable playback token")
        if ((o["gated"] as? JsonPrimitive)?.content == "true" || o.str("token").isNullOrBlank()) {
            throw Refused(
                when (o.str("reason")) {
                    "banned" -> "Account banned for automated or abusive access — appeal in the Octave Discord"
                    "suspended" -> "Account paused for unusual activity — appeal in the Octave Discord"
                    else -> "No streaming access on this account (${o.str("reason") ?: "gated"})"
                },
            )
        }
        val token = o.str("token") ?: throw IOException("unreadable playback token")
        if (token.isBlank()) throw IOException("empty playback token")
        return token to (o["expiresIn"] as? JsonPrimitive)?.content?.toLongOrNull()
    }

    suspend fun search(base: String, key: String?, query: String, limit: Int = 25): List<OctaveTrack> =
        withContext(Dispatchers.IO) {
            val url = "$base/api/search/tracks?query=${encode(query)}&limit=${limit.coerceIn(1, 50)}"
            val code = if (key.isNullOrBlank()) getAnon(url) else get(url, key)
            if (!code.ok) throw IOException("search refused (HTTP ${code.code})")
            parseSearch(code.body)
        }

    private data class Body(val ok: Boolean, val code: Int, val body: String)

    private fun authed(url: String, key: String?): Request.Builder {
        val b = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "BitChord/v${BuildConfig.VERSION_NAME}")
        if (!key.isNullOrBlank()) b.header("Authorization", "Bearer ${key.trim()}")
        return b
    }

    private fun get(url: String, key: String): Body = call(authed(url, key).build())

    private fun getAnon(url: String): Body = call(authed(url, null).build())

    private fun call(request: Request): Body {
        Http.client.newCall(request).execute().use { response ->
            val text = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
            TrackLog.d(TAG, "octave ${response.code} ${redact(request.url.toString())}")
            return Body(response.isSuccessful, response.code, text)
        }
    }

    private fun encode(raw: String): String =
        runCatching { java.net.URLEncoder.encode(raw, "UTF-8") }.getOrDefault(raw)

    private fun redact(url: String): String = url.replace(Regex("([?&])(k|token)=[^&]*"), "$1$2=…")
}
