package com.music.bitchord.data.liked

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Liked Music list as plain metadata rows — the "CSV" that survives
 * force-stops: videoId + display fields per track, capped so the prefs
 * file stays small. No audio, no verification, no network: opening Liked
 * paints from here instantly, and the network fetch replaces it when it
 * lands. Audio still resolves only when a row is tapped.
 *
 * Account-scoped: rows belong to whoever liked them, and a switcher
 * mid-sync must never paint the old account's likes into the new one's
 * list. Single-song mutations patch in place (like prepends, unlike
 * drops) so one tap never costs a refetch.
 */
object LikedCache {

    private lateinit var prefs: SharedPreferences

    private const val FILE = "bitchord_liked"
    private const val KEY_ACCOUNT = "liked_account"
    private const val KEY_ROWS = "liked_rows"

    /** Hard ceiling: 5,000 rows is ~1MB of text, plenty for any library. */
    const val MAX_ROWS = 5000

    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    fun init(context: Context) {
        if (this::prefs.isInitialized) return
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    /** Instant rows for [account], or null when there are none to show. */
    fun read(account: String?): List<Song>? {
        if (!this::prefs.isInitialized || account.isNullOrBlank()) return null
        if (prefs.getString(KEY_ACCOUNT, null) != account) return null
        return decodeLikedRows(prefs.getString(KEY_ROWS, null).orEmpty())
            .takeIf { it.isNotEmpty() }
    }

    /** Full replace — the end of a successful fetch or background walk. */
    fun write(account: String?, songs: List<Song>) {
        if (!this::prefs.isInitialized || account.isNullOrBlank()) return
        prefs.edit()
            .putString(KEY_ACCOUNT, account)
            .putString(KEY_ROWS, encodeLikedRows(songs))
            .apply()
        _generation.value += 1
    }

    /** A fresh like lands on top without a refetch. */
    fun mergeTop(account: String?, rows: List<Song>) {
        if (!this::prefs.isInitialized || account.isNullOrBlank() || rows.isEmpty()) return
        val current = read(account).orEmpty()
        if (prefs.getString(KEY_ACCOUNT, null) != account) {
            write(account, rows)
            return
        }
        val known = current.mapTo(HashSet()) { it.videoId }
        val merged = (rows.filter { known.add(it.videoId) } + current).take(MAX_ROWS)
        prefs.edit().putString(KEY_ROWS, encodeLikedRows(merged)).apply()
        _generation.value += 1
    }

    /** Rows trickling in from a background walk append underneath. */
    fun mergeBottom(account: String?, rows: List<Song>) {
        if (!this::prefs.isInitialized || account.isNullOrBlank() || rows.isEmpty()) return
        if (prefs.getString(KEY_ACCOUNT, null) != account) {
            write(account, rows)
            return
        }
        val current = read(account).orEmpty()
        val known = current.mapTo(HashSet()) { it.videoId }
        val merged = (current + rows.filter { known.add(it.videoId) }).take(MAX_ROWS)
        prefs.edit().putString(KEY_ROWS, encodeLikedRows(merged)).apply()
        _generation.value += 1
    }

    /** An unlike leaves instantly, here and on screen. */
    fun drop(account: String?, videoId: String) {
        if (!this::prefs.isInitialized || account.isNullOrBlank() || videoId.isBlank()) return
        val current = read(account) ?: return
        if (current.none { it.videoId == videoId }) return
        prefs.edit()
            .putString(KEY_ROWS, encodeLikedRows(current.filterNot { it.videoId == videoId }))
            .apply()
        _generation.value += 1
    }
}

/**
 * Liked rows as plain text, split from the prefs IO so it unit tests on
 * the JVM: control separators can never appear in a field because
 * encoding blanks them, so splitting never misparses.
 */
fun encodeLikedRows(songs: List<Song>): String =
    songs.take(LikedCache.MAX_ROWS).joinToString("\u001e") { s ->
        listOf(
            s.videoId, s.title, s.artist,
            s.albumName.orEmpty(), s.thumbnailUrl.orEmpty(),
            s.durationText.orEmpty(), if (s.isExplicit == true) "1" else "",
        ).joinToString("\u001f") { it.replace("\u001e", " ").replace("\u001f", " ") }
    }

/** Inverse of [encodeLikedRows]: rows without an id are dropped. */
fun decodeLikedRows(text: String): List<Song> {
    if (text.isBlank()) return emptyList()
    return text.split("\u001e").mapNotNull { row ->
        val f = row.split("\u001f")
        val id = f.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Song(
            videoId = id,
            title = f.getOrNull(1).orEmpty(),
            artist = f.getOrNull(2).orEmpty(),
            albumName = f.getOrNull(3)?.takeIf { it.isNotBlank() },
            thumbnailUrl = f.getOrNull(4)?.takeIf { it.isNotBlank() },
            durationText = f.getOrNull(5)?.takeIf { it.isNotBlank() },
            isExplicit = if (f.getOrNull(6) == "1") true else null,
        )
    }
}
