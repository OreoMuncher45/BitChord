package com.music.bitchord.ui.classipod

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song

/**
 * Session playlist cache: every long list loads once per process, in
 * feed pages (~100 rows each), and stays loaded while the app lives.
 * Reopening a playlist shows what is already there instantly and keeps
 * paging behind it — never the full reload from zero the lists used to
 * do on every open.
 */
object PlaylistCache {

    class Entry(val browseId: String) {
        val songs: SnapshotStateList<Song> = mutableStateListOf()
        var token: String? by mutableStateOf(null)
        var started: Boolean by mutableStateOf(false)
        var loading: Boolean by mutableStateOf(false)
        /** The feed's own total when it names one, else the exact count at the end. */
        var total: Int? by mutableStateOf(null)
        var failedFirst: Boolean by mutableStateOf(false)
    }

    private val entries = HashMap<String, Entry>()

    fun entry(browseId: String): Entry = synchronized(this) {
        entries.getOrPut(browseId) { Entry(browseId) }
    }

    /** Seed from an already-known first page (Liked arrives in the library feed). */
    fun seed(entry: Entry, page: YtMusicRepository.SongPage) {
        if (entry.started) return
        entry.started = true
        append(entry, page)
    }

    /** First page over the network. False when nothing could be read. */
    suspend fun loadFirst(entry: Entry): Boolean {
        if (entry.started) return entry.songs.isNotEmpty()
        entry.started = true
        entry.loading = true
        return try {
            val page = YtMusicRepository.browseSongs(entry.browseId).getOrNull()
            if (page == null) {
                entry.failedFirst = true
                false
            } else {
                append(entry, page)
                true
            }
        } finally {
            entry.loading = false
        }
    }

    /** Next page behind [Entry.token]. False when exhausted, busy or failed. */
    suspend fun loadMore(entry: Entry): Boolean {
        val t = entry.token ?: return false
        if (entry.loading) return false
        entry.loading = true
        return try {
            val page = YtMusicRepository.moreSongs(t).getOrNull() ?: return false
            append(entry, page)
            true
        } finally {
            entry.loading = false
        }
    }

    private fun append(entry: Entry, page: YtMusicRepository.SongPage) {
        val known = entry.songs.mapTo(HashSet()) { it.videoId }
        entry.songs.addAll(page.songs.filterNot { it.videoId in known })
        entry.token = page.continuation
        page.header?.subtitle?.let { parsePlaylistCount(it)?.let { n -> entry.total = n } }
        if (page.continuation == null) entry.total = entry.songs.size
    }

    /** Header figure: feed total, exact count, or a growing floor with "+". */
    fun countLabel(entry: Entry): String = when {
        entry.total != null -> entry.total.toString()
        entry.token == null -> entry.songs.size.toString()
        else -> "${entry.songs.size}+"
    }

    /** Exact count for badges that must never show the cap. Null until known. */
    fun exactTotal(entry: Entry): Int? =
        entry.total?.takeIf { entry.token == null }
}

/** "Playlist · 1,247 songs" → 1247. Null when the header names no count. */
internal fun parsePlaylistCount(subtitle: String): Int? =
    Regex("""(\d[\d,]*)\s+songs?\b""", RegexOption.IGNORE_CASE)
        .find(subtitle)
        ?.groupValues
        ?.get(1)
        ?.filter(Char::isDigit)
        ?.toIntOrNull()

