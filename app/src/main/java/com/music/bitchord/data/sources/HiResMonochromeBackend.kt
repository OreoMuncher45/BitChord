package com.music.bitchord.data.sources

import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max

/**
 * Direct-FLAC backend owned by the Hi-Res source — not a source itself.
 *
 * The sealed Tidal/Monochrome source already speaks `tracks.monochrome.st`;
 * this backend gives the same host to Hi-Res, whose entire catalogue and
 * stream path is these direct files. No [SourceKind], no registry row, no
 * UI: the Hi-Res source is the only caller.
 *
 * Search and stream state live here rather than on the source instance:
 * instances are rebuilt when configs change, and an instance memo would
 * strand every search row it minted the moment the registry rebuilds.
 */
object HiResMonochromeBackend {

    const val BASE = "https://tracks.monochrome.st"
    /** The host files resolve under — the key stream refusals are recorded by. */
    const val BASE_HOST = "tracks.monochrome.st"
    private const val TAG = "BitChord"
    private const val PACK_PREFIX = "m:"

    /** Test hook: point the backend at a MockWebServer; reset after each case. */
    internal var baseOverride: String? = null
    private val base: String get() = baseOverride?.trimEnd('/') ?: BASE

    /** 5xx backoff: one unreachable origin is routinely gone on retry. */
    private val RETRY_DELAYS_MS = listOf(1_000L, 2_000L, 4_000L)

    /**
     * Uncompressed stereo PCM floors: a FLAC never averages above the PCM it
     * encodes, so a file past the first cannot be 16/44.1 and one past the
     * second cannot be 44.1kHz at all.
     */
    const val CD_PCM_BITRATE = 1_411_200
    const val HIRES_PCM_BITRATE = 2_116_800

    /** Below this a "FLAC" is a transcode in a lossless container: refuse it. */
    const val MIN_GENUINE_KBPS = 300

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

    @Serializable
    data class SearchResponse(
        @SerialName("tracks") val tracks: List<MonoTrack> = emptyList(),
    )

    /**
     * The catalogue quotes large ids (`"id":"154032361934163968"`) while
     * older rows arrive bare (`"id":777`): one shape per era, both live.
     * A strict `Long` parses the second and throws on the first, which is
     * how the whole direct catalogue read empty in production while every
     * unit test — all written with bare ids — stayed green. The sibling
     * client for this host family ([TidalApi][com.music.bitchord.data.tidal.TidalApi])
     * reads ids as strings for the same reason; this matches it.
     */
    private object FlexibleId : KSerializer<String> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("FlexibleId", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): String {
            val element = (decoder as? JsonDecoder)?.decodeJsonElement()
            return (element as? JsonPrimitive)?.content.orEmpty()
        }

        override fun serialize(encoder: Encoder, value: String) {
            encoder.encodeString(value)
        }
    }

    @Serializable
    data class MonoTrack(
        @SerialName("id") @Serializable(with = FlexibleId::class) val id: String = "",
        @SerialName("title") val title: String = "",
        @SerialName("artistNames") val artistNames: List<String> = emptyList(),
        /** Usually milliseconds; normalized rather than trusted. */
        @SerialName("duration") val durationMs: Long? = null,
        @SerialName("artwork") val artwork: String? = null,
        @SerialName("albumTitle") val albumTitle: String? = null,
        @SerialName("releaseTitle") val releaseTitle: String? = null,
        @SerialName("isrc") val isrc: String? = null,
        @SerialName("explicit") val explicit: Boolean? = null,
        /** A catalogue entry with no file behind it. */
        @SerialName("playable") val playable: Boolean? = null,
    ) {
        val credits: String
            get() = artistNames.mapNotNull { it.takeIf(String::isNotBlank) }
                .distinct().joinToString(", ").ifBlank { "Unknown artist" }
        val album: String?
            get() = albumTitle?.takeIf { it.isNotBlank() }
                ?: releaseTitle?.takeIf { it.isNotBlank() }
    }

    /** What a Hi-Res search row needs later at queue time, by its packed id. */
    data class Origin(val id: String, val durationMs: Long?)

    private val origins = Collections.synchronizedMap(
        object : LinkedHashMap<String, Origin>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Origin>) = size > 128
        },
    )

    fun packId(id: String): String = "$PACK_PREFIX$id"

    fun memoizeOrigin(packed: String, origin: Origin) {
        origins[packed] = origin
    }

    fun originOf(packed: String): Origin? = origins[packed]

    internal fun clearOrigins() {
        origins.clear()
    }

    internal fun resetForTests() {
        baseOverride = null
        origins.clear()
    }

    /** A verified file: its URL, measured bitrate, and implied resolution. */
    data class ResolvedFile(
        val url: String,
        val kbps: Int,
        val bitDepth: Int,
        val sampleRateHz: Int,
    )

    /** True when the host answers a shape-checked search. Swallows, never throws. */
    suspend fun probe(): Boolean {
        return try {
            get("$base/search/tracks?q=a&limit=1")
            true
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            false
        }
    }

    suspend fun searchTracks(query: String, limit: Int = 25): List<MonoTrack> {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        return try {
            val body = get("$base/search/tracks?q=${encode(q)}&limit=${limit.coerceIn(1, 50)}")
            json.decodeFromString<SearchResponse>(body).tracks
                .filter { it.playable != false && it.id.isNotBlank() }
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            TrackLog.d(TAG, "  ✗ hires direct catalogue search failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Verifies the file answers and measures it, following 5xx backoff. Null
     * when the host lacks the file (4xx), stays down, or hands over a
     * below-lossless file in a FLAC container: all are misses to walk past.
     */
    suspend fun resolveFile(id: String, durationMs: Long?): ResolvedFile? {
        val url = "$base/track/$id"
        val duration = normalizeDurationMs(durationMs)
        repeat(RETRY_DELAYS_MS.size + 1) { attempt ->
            coroutineContext.ensureActive()
            val head = try {
                head(url)
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                return null
            }
            if (head.code == 200 || head.code == 206) {
                val measured = measuredKbps(head.lengthBytes, duration)
                if (measured != null && measured < MIN_GENUINE_KBPS) {
                    TrackLog.w(TAG, "  ✗ hires direct file below lossless floor; refusing")
                    return null
                }
                return fileFrom(url, head.lengthBytes, duration)
            }
            if (head.code < 500 || attempt >= RETRY_DELAYS_MS.size) return null
            delay(RETRY_DELAYS_MS[attempt])
        }
        return null
    }

    private data class Head(val code: Int, val lengthBytes: Long)

    private fun head(url: String): Head {
        val request = Request.Builder().url(url).head()
            .header("User-Agent", BROWSER_UA)
            .build()
        client.newCall(request).execute().use { response ->
            val length = response.header("Content-Length")?.toLongOrNull() ?: 0L
            return Head(response.code, length)
        }
    }

    /** Seconds and milliseconds arrive interchangeably; milliseconds win. */
    fun normalizeDurationMs(durationMs: Long?): Long? {
        val duration = durationMs?.takeIf { it > 0 } ?: return null
        return if (duration <= 10_000) duration * 1000 else duration
    }

    /** Average bitrate from measured size, or null when either side is unknown. */
    fun measuredKbps(sizeBytes: Long, durationMs: Long?): Int? {
        val duration = durationMs?.takeIf { it > 0 } ?: return null
        if (sizeBytes <= 0) return null
        return (((sizeBytes * 8L * 1000L) / duration) / 1000L).toInt()
    }

    /**
     * File size over duration to average bitrate, then the least resolution
     * that bitrate implies. Unknown size or duration falls back to CD figures
     * rather than refusing a file the catalogue says is playable.
     */
    fun fileFrom(url: String, sizeBytes: Long, durationMs: Long?): ResolvedFile {
        val kbps = measuredKbps(sizeBytes, durationMs) ?: (CD_PCM_BITRATE / 1000)
        return ResolvedFile(
            url = url,
            kbps = kbps,
            bitDepth = if (kbps > CD_PCM_BITRATE / 1000) 24 else 16,
            sampleRateHz = if (kbps > HIRES_PCM_BITRATE / 1000) 96_000 else 44_100,
        )
    }

    /** Whether two runtimes name the same recording within tolerance. */
    fun durationsMatch(expectedSec: Int?, actualMs: Long?, toleranceSec: Int = 5): Boolean {
        if (expectedSec == null || expectedSec <= 0) return true
        val actual = normalizeDurationMs(actualMs)?.div(1000)?.toInt() ?: return true
        return abs(expectedSec - actual) <= max(toleranceSec, expectedSec / 10)
    }

    // ── Matching ──────────────────────────────────────────────────────
    // ISRC-exact first; anything else must share an artist credit, because
    // the search returns covers next to originals.
    //
    // Title matching lives here rather than in the source so every caller
    // ranks rows the same way: strip featured/remaster/version noise and
    // refuse a result carrying a modifier the request lacks, so a live cut
    // never stands in for the studio recording.

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

    private const val KEEP = "abcdefghijklmnopqrstuvwxyz0123456789 "

    fun simplify(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val kept = StringBuilder()
        for (ch in value.lowercase()) {
            if (ch in KEEP) kept.append(ch)
        }
        return kept.toString().trim()
    }

    /** ISRC hits first, everything else in catalogue order. */
    fun rankByIsrc(items: List<MonoTrack>, isrc: String?): List<MonoTrack> {
        if (items.isEmpty()) return items
        val wanted = isrc?.takeIf { it.isNotBlank() }?.uppercase() ?: return items
        return items.filter { it.isrc?.uppercase() == wanted } +
            items.filter { it.isrc?.uppercase() != wanted }
    }

    /**
     * Drops results that only share the title: an ISRC hit always survives,
     * anything else has to be credited to one of the track's artists.
     */
    fun plausible(items: List<MonoTrack>, artists: List<String>, isrc: String?): List<MonoTrack> {
        if (items.isEmpty()) return items
        val wanted = artists.map { simplify(it) }.filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return items
        val exact = isrc?.takeIf { it.isNotBlank() }?.uppercase().orEmpty()
        return items.filter { item ->
            if (exact.isNotEmpty() && item.isrc?.uppercase() == exact) return@filter true
            item.artistNames.any { credit ->
                val simplified = simplify(credit)
                simplified.isNotEmpty() && wanted.any { simplified.contains(it) || it.contains(simplified) }
            }
        }
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("User-Agent", BROWSER_UA)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IOException("Empty response")
        }
    }

    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
