package com.music.bitchord.data.artwork

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Saves a track's cover at full quality to the gallery.
 *
 * List rows carry small thumbs (`w60-h60` hints, 640px Tidal covers), so the
 * URL is first upgraded to the largest rendition the host serves before a
 * single byte is fetched — saving the row's own thumbnail would keep a
 * blurry copy forever. Bytes are stored as-is (no re-encode), through
 * MediaStore like the Replay poster: permission-free on API 29+, best
 * effort below it.
 */
object ArtworkDownload {

    private const val TAG = "BitChord"

    /** Largest rendition for a thumbnail URL, or null when there is no art. */
    fun fullUrl(thumbnailUrl: String?): String? {
        if (thumbnailUrl.isNullOrBlank()) return null
        // YouTube-style size hint: ask for the source rung (~1400px).
        val sized = Regex("""w\d+-h\d+""").replace(thumbnailUrl, "w1400-h1400")
        if (sized != thumbnailUrl) return sized
        // Tidal resources: step up to the 1280 rung.
        if ("/640x640.jpg" in thumbnailUrl) {
            return thumbnailUrl.replace("/640x640.jpg", "/1280x1280.jpg")
        }
        return thumbnailUrl
    }

    /** Fetches full-quality bytes and files them in Pictures/BitChord Next. */
    suspend fun save(context: Context, song: Song): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = fullUrl(song.thumbnailUrl) ?: error("no artwork")
            val bytes = fetch(url)
            val ext = mimeExt(bytes.contentType)
            val name = fileName(song, ext)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, bytes.contentType)
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/BitChord Next",
                    )
                }
                val uri = context.contentResolver
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("no row")
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes.data) }
                    ?: error("no stream")
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "BitChord Next",
                ).also { it.mkdirs() }
                FileOutputStream(File(dir, name)).use { it.write(bytes.data) }
            }
            TrackLog.d(TAG, "cover saved: $name (${bytes.data.size / 1024} KB)")
            name
        }
    }

    private data class Fetched(val data: ByteArray, val contentType: String)

    private fun fetch(url: String): Fetched {
        val request = Request.Builder().url(url)
            .header("User-Agent", "BitChord")
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val type = response.header("Content-Type")?.substringBefore(';')?.trim().orEmpty()
            if (!type.startsWith("image/")) error("not an image")
            val data = response.body?.bytes()?.takeIf { it.isNotEmpty() } ?: error("empty")
            return Fetched(data, type)
        }
    }

    private fun mimeExt(mime: String): String = when (mime.lowercase(Locale.ROOT)) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> "jpg"
    }

    private fun fileName(song: Song, ext: String): String {
        val safe = { s: String ->
            s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60)
        }
        val base = "${safe(song.artist)}-${safe(song.title)}".trim('-').ifBlank { "cover" }
        return "$base.$ext"
    }
}
