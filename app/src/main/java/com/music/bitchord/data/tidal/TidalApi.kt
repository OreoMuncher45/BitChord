package com.music.bitchord.data.tidal

import com.music.bitchord.BuildConfig
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Talk to a hifi-api-compatible Tidal instance (binimum protocol v2).
 *
 * The instance holds its own Tidal tokens; this device needs no account, no
 * keys, no login — it just asks for search rows and stream URLs. Search and
 * manifests come from Tidal's catalogue through the instance, and the audio
 * bytes come straight from Tidal's CDN to ExoPlayer.
 *
 * Every parse is defensive ([JsonObject] walks, never strict models): Tidal's
 * rows vary by release and forks drift, and a strict model turns one odd row
 * into zero results. Network shape is per-call clients off the shared pool
 * with tight timeouts — a dead instance must fail fast so failover stays
 * invisible.
 */
object TidalApi {

    private const val TAG = "BitChord"

    const val HI_RES = "HI_RES_LOSSLESS"
    const val LOSSLESS = "LOSSLESS"
    const val HIGH = "HIGH"
    const val LOW = "LOW"

    /** Qualities worth asking for, best first. */
    val LOSSLESS_TIERS = listOf(HI_RES, LOSSLESS)

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private val quickClient by lazy {
        Http.client.newBuilder()
            .callTimeout(10, TimeUnit.SECONDS)
            .connectTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    private val streamClient by lazy {
        Http.client.newBuilder()
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    data class TidalTrack(
        val id: String,
        val title: String,
        val artist: String,
        val album: String?,
        val coverSlug: String?,
        val durationSec: Int?,
        val explicit: Boolean?,
        val audioQuality: String?,
    )

    data class TidalStream(
        val url: String,
        val quality: String,
        val mimeType: String?,
    )

    /** Refused: reachable, but not a hifi-api instance (or not v2). */
    class Incompatible(message: String) : IOException(message)

    /** The instance root, normalised — scheme + host, no trailing slash. */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val parsed = trimmed.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "https" && parsed.scheme != "http") return null
        val port = if (parsed.port != defaultPort(parsed.scheme)) ":${parsed.port}" else ""
        val path = parsed.encodedPath.trimEnd('/').takeIf { it.isNotEmpty() && it != "/" }.orEmpty()
        return "${parsed.scheme}://${parsed.host}$port$path"
    }

    private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80

    /** A string field, or null when absent, blank, or not a string. */
    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    /**
     * The instance's protocol version, or null when the body is not a
     * hifi-api root document. Pure so the gate is unit-testable.
     */
    fun parseVersion(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return null
        return root.str("version")?.takeIf { it.startsWith("2.") }
    }

    /** One GET against the instance root; the version string or null. Throws on transport failure. */
    suspend fun probe(base: String): String? = withContext(Dispatchers.IO) {
        val body = get("$base/", quickClient)
        parseVersion(body)
    }

    /** Best-effort probe for identification: any failure reads as "not Tidal". */
    suspend fun probeBestEffort(rawUrl: String): String? {
        val base = normalize(rawUrl) ?: return null
        return runCatching { probe(base) }.getOrNull()
    }

    fun parseSearch(body: String): List<TidalTrack> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return emptyList()
        val data = root["data"] as? JsonObject ?: return emptyList()
        val items = data["items"] as? JsonArray ?: return emptyList()
        return items.mapNotNull { it as? JsonObject }.mapNotNull { parseTrack(it) }
    }

    private fun parseTrack(o: JsonObject): TidalTrack? {
        // Tidal ids are ints; read the raw content so both `123` and `"123"` work.
        val id = (o["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return null
        val title = o.str("title") ?: return null
        val artistObj = o["artist"] as? JsonObject
        val artistNames = (o["artists"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.str("name") }
            .orEmpty()
        val artist = (artistNames + listOfNotNull(artistObj?.str("name")))
            .distinct().joinToString(", ").ifBlank { "Unknown Artist" }
        val album = o["album"] as? JsonObject
        return TidalTrack(
            id = id,
            title = title,
            artist = artist,
            album = album?.str("title"),
            coverSlug = album?.str("cover"),
            durationSec = (o["duration"] as? JsonPrimitive)?.content?.toIntOrNull(),
            explicit = (o["explicit"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull(),
            audioQuality = o.str("audioQuality"),
        )
    }

    suspend fun search(base: String, query: String, limit: Int = 25): List<TidalTrack> =
        withContext(Dispatchers.IO) {
            val url = checkNotNull("$base/search/?s=${encode(query)}&limit=${limit.coerceIn(1, 50)}"
                .toHttpUrlOrNull()) { "bad instance url" }.toString()
            parseSearch(get(url, streamClient))
        }

    /**
     * The playable stream inside a `/track/` response, or null when the
     * instance has no playable rendition (DRM, empty manifest). Pure.
     */
    fun pickStream(body: String): TidalStream? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
            ?: return null
        val data = root["data"] as? JsonObject ?: return null
        val encoded = data.str("manifest") ?: return null
        // java.util (not android.util) so this stays plain-JVM-testable;
        // minSdk 26 carries it on device too.
        val decoded = runCatching {
            java.util.Base64.getMimeDecoder().decode(encoded).toString(Charsets.UTF_8)
        }.getOrNull() ?: return null
        val manifest = runCatching { json.parseToJsonElement(decoded) }.getOrNull() as? JsonObject
            ?: return null
        val encryption = manifest.str("encryptionType")
        if (encryption != null && encryption != "NONE") return null
        val urls = (manifest["urls"] as? JsonArray)
            ?.mapNotNull { ((it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.takeIf(String::isNotBlank)) }
            .orEmpty()
        val first = urls.firstOrNull() ?: return null
        return TidalStream(
            url = first,
            quality = data.str("audioQuality").orEmpty(),
            mimeType = manifest.str("mimeType") ?: manifest.str("codecs"),
        )
    }

    /**
     * A stream URL for [tidalId] at the first playable tier in [qualities].
     * Null when the catalogue has no playable rendition (a miss, not an
     * error — the resolver moves on). Transport failures and instance-side
     * overload (429/502/503/504) throw so the caller fails over.
     */
    suspend fun streamUrl(base: String, tidalId: String, qualities: List<String>): TidalStream? =
        withContext(Dispatchers.IO) {
            for (quality in qualities) {
                val url = "$base/track/?id=${encode(tidalId)}&quality=$quality"
                val code = getWithCode(url, streamClient)
                if (code.code == 429 || code.code == 502 || code.code == 503 || code.code == 504) {
                    throw IOException("instance overloaded (HTTP ${code.code})")
                }
                if (!code.ok) continue
                pickStream(code.body)?.let { return@withContext it }
            }
            null
        }

    fun coverUrl(slug: String, px: Int = 640): String =
        "https://resources.tidal.com/images/${slug.replace("-", "/")}/${px}x${px}.jpg"

    private data class Body(val ok: Boolean, val code: Int, val body: String)

    private fun get(url: String, client: okhttp3.OkHttpClient): String {
        val body = getWithCode(url, client)
        if (!body.ok) throw IOException("HTTP ${body.code}")
        return body.body
    }

    private fun getWithCode(url: String, client: okhttp3.OkHttpClient): Body {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "BitChord/v${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { response ->
            val text = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
            TrackLog.d(TAG, "tidal ${response.code} ${redact(url)}")
            return Body(response.isSuccessful, response.code, text)
        }
    }

    private fun encode(raw: String): String =
        runCatching { java.net.URLEncoder.encode(raw, "UTF-8") }.getOrDefault(raw)

    private fun redact(url: String): String = url.replace(Regex("([?&])(token|secret)=[^&]*"), "$1$2=…")
}
