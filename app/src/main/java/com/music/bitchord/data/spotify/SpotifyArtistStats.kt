package com.music.bitchord.data.spotify

import com.music.bitchord.data.Http
import com.music.bitchord.data.canvas.SpotifyToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/** What Spotify's artist page publishes, and its API does not. */
data class SpotifyArtistStats(
    val monthlyListeners: Long,
    val followers: Long?,
)

/**
 * Monthly listeners, scraped from the public artist page.
 *
 * Spotify's Web API artist object has followers but no monthly-listener count;
 * the figure exists only on the page itself, in the Open Graph description and
 * the page's bootstrap JSON. There is no supported endpoint to ask, so this is
 * deliberately best-effort: any failure returns null and the artist page keeps
 * showing YouTube's own counts.
 */
object SpotifyArtistStatsProvider {
    private data class Cached(val stats: SpotifyArtistStats, val at: Long)

    private const val TTL_MS = 6 * 60 * 60 * 1000L
    private val cache = ConcurrentHashMap<String, Cached>()

    suspend fun load(artistName: String): SpotifyArtistStats? = withContext(Dispatchers.IO) {
        val key = artistName.trim().lowercase()
        if (key.isEmpty()) return@withContext null
        cache[key]?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }
            ?.let { return@withContext it.stats }

        val id = runCatching { searchArtistId(artistName) }.getOrNull()
            ?: return@withContext null
        val stats = runCatching { scrapedStats(id) }.getOrNull()
            ?: return@withContext null
        cache[key] = Cached(stats, System.currentTimeMillis())
        stats
    }

    private suspend fun searchArtistId(name: String): String? {
        val token = runCatching { SpotifyToken.accessToken() }.getOrNull()
            ?: anonymousToken()
            ?: return null
        val query = URLEncoder.encode(name, "UTF-8")
        val request = Request.Builder()
            .url("https://api.spotify.com/v1/search?q=$query&type=artist&limit=1")
            .header("Authorization", "Bearer $token")
            .build()
        return Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val root = response.body?.string()?.let {
                runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject }
                    .getOrNull()
            } ?: return null
            root["artists"]?.jsonObject?.get("items")
                ?.let { it as? kotlinx.serialization.json.JsonArray }
                ?.firstOrNull()
                ?.jsonObject
                ?.get("id")
                ?.jsonPrimitive
                ?.contentOrNull
        }
    }

    /** The web player's unauthenticated token, tried only when no session exists. */
    private fun anonymousToken(): String? = runCatching {
        val request = Request.Builder()
            .url("https://open.spotify.com/get_access_token?reason=transport&productType=web_player")
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
                .get("accessToken")?.jsonPrimitive?.contentOrNull
        }
    }.getOrNull()

    private fun scrapedStats(id: String): SpotifyArtistStats? {
        val request = Request.Builder()
            .url("https://open.spotify.com/artist/$id")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        return Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val html = response.body?.string() ?: return null
            val listeners = MONTHLY_JSON.find(html)?.groupValues?.getOrNull(1)?.toLongOrNull()
                ?: MONTHLY_META.find(html)?.groupValues?.getOrNull(1)
                    ?.filter { it.isDigit() }?.toLongOrNull()
                ?: return null
            val followers = FOLLOWERS_JSON.find(html)?.groupValues?.getOrNull(1)?.toLongOrNull()
                ?: FOLLOWERS_META.find(html)?.groupValues?.getOrNull(1)
                    ?.filter { it.isDigit() }?.toLongOrNull()
            SpotifyArtistStats(listeners, followers)
        }
    }

    private val MONTHLY_JSON = Regex(""""monthlyListeners"\s*:\s*(\d+)""")
    private val MONTHLY_META = Regex("""([\d,.]+)\s+monthly listeners""", RegexOption.IGNORE_CASE)
    private val FOLLOWERS_JSON = Regex(""""followers"\s*:\s*\{\s*"total"\s*:\s*(\d+)""")
    private val FOLLOWERS_META = Regex("""([\d,.]+)\s+followers""", RegexOption.IGNORE_CASE)
}
