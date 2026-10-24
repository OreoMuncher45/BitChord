package com.music.bitchord.data

import android.content.Context
import com.music.bitchord.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request

/**
 * Server-driven popups that are not updates: maintenance notices, a broken
 * source, a new addon the app can use without a new build.
 *
 * The feed is a plain JSON file in the repo, so posting one is an edit and a
 * commit rather than a release:
 *
 * ```json
 * {
 *   "announcements": [
 *     {
 *       "id": "2026-10-addon-lock",
 *       "title": "Addon changes",
 *       "body": "The Auris addon now refuses other hosts.",
 *       "url": "https://auris.shehriyar.ink",
 *       "action": "Read more",
 *       "once": true,
 *       "active": true,
 *       "minVersion": "2.0.0",
 *       "maxVersion": "2.2.17"
 *     }
 *   ]
 * }
 * ```
 *
 * `id` is the memory key, so editing an announcement's text under the same id
 * does not show it again to people who already dismissed it — change the id to
 * re-announce. `once: false` shows on every launch (still dismissible).
 * `minVersion` / `maxVersion` are inclusive and optional, which is how a notice
 * aimed only at builds that lack a fix avoids nagging the ones that have it.
 *
 * Fetching is best-effort: a missing file, offline device or malformed entry
 * is silence, never an error the user has to dismiss.
 */
object AppAnnouncements {

    data class Announcement(
        val id: String,
        val title: String,
        val body: String,
        val url: String?,
        /** The feed's own action label; the UI supplies a default when blank. */
        val action: String?,
    )

    private const val FEED_URL =
        "https://raw.githubusercontent.com/OreoMuncher45/BitChordNext/main/announcements.json"

    private const val FILE = "bitchord_announcements"
    private const val KEY_SEEN = "seen_ids"

    private val json = Json { ignoreUnknownKeys = true }

    private val _current = MutableStateFlow<Announcement?>(null)
    val current = _current.asStateFlow()

    /** Looks for the first live, unseen announcement aimed at this build. */
    suspend fun check(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(FEED_URL)
                .header("Accept", "application/json")
                .build()
            val text = Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            } ?: return@runCatching
            val entries = (json.parseToJsonElement(text) as? JsonObject)
                ?.get("announcements") as? JsonArray
                ?: return@runCatching

            val version = BuildConfig.VERSION_NAME
            val seen = seenIds(context)
            for (element in entries) {
                val entry = element as? JsonObject ?: continue
                if (entry.bool("active") == false) continue
                val id = entry.text("id") ?: continue
                val title = entry.text("title") ?: continue
                val body = entry.text("body") ?: continue
                if (!entry.targets(version)) continue
                val once = entry.bool("once") != false
                if (once && id in seen) continue
                _current.value = Announcement(
                    id = id,
                    title = title,
                    body = body,
                    url = entry.text("url"),
                    action = entry.text("action"),
                )
                return@runCatching
            }
        }
    }

    /**
     * Records the announcement as read and clears it. Called from the popup's
     * only exits, so closing by the scrim counts the same as pressing OK.
     */
    fun dismiss(context: Context, id: String) {
        _current.value = null
        runCatching {
            prefs(context).edit()
                .putStringSet(KEY_SEEN, seenIds(context) + id)
                .apply()
        }
    }

    private fun seenIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SEEN, emptySet())?.toSet().orEmpty()

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.targets(version: String): Boolean {
        val min = text("minVersion")
        val max = text("maxVersion")
        if (min != null && AppUpdateChecker.compareVersions(version, min) < 0) return false
        if (max != null && AppUpdateChecker.compareVersions(version, max) > 0) return false
        return true
    }
}
