package com.music.bitchord.download

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.music.bitchord.data.DebugLog as Log
import androidx.core.content.ContextCompat
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.DownloadQuality
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.OutputStream
import java.io.File
import java.util.Locale

/** Where a track is between "not on this device" and "on it". */
sealed interface DownloadState {

    /** Accepted, waiting for the one in front of it. */
    data object Queued : DownloadState

    /** [fraction] is 0f until the length is known, which is the first thing asked for. */
    data class Running(val fraction: Float) : DownloadState

    data class Failed(val reason: String) : DownloadState
}

/**
 * The download queue, and the record of what came out of it.
 *
 * Split deliberately into two pieces of state that look similar and behave
 * nothing alike:
 *
 *  - [active] is what is happening now — queued, running, just failed. It lives
 *    in memory, is driven by [DownloadService], and is empty on a cold start
 *    because a download interrupted by the process dying did not happen.
 *  - [saved] is what exists on disk, keyed by videoId and remembered across
 *    launches. It is the only way the app can answer "do I already have this?"
 *    without a media-store query per row, and the only way it knows *which*
 *    file a track corresponds to when asked to delete it.
 *
 * [saved] is a claim about a folder this app does not own. The user is expected
 * to manage Downloads with a file manager, so an entry here can outlive the
 * file it names — which is why every read of it goes through [savedUri], and
 * why that verifies before it answers.
 */
object Downloads {

    private const val TAG = "BitChord"
    private const val KEY_SAVED_METADATA = "downloaded_tracks_metadata"
    private const val KEY_SAVED_COLLECTIONS = "downloaded_collections"

    /**
     * The index lives here, not in the settings file.
     *
     * See [migrateLegacy]. The only thing worth keeping on disk that is not
     * rewritten on every download is the record of which downloads exist.
     */
    private const val PREFS_FILE = "bitchord_downloads"

    /** Where it lived until this release; read once, then cleared. */
    private const val LEGACY_PREFS_FILE = "bitchord_settings"

    /**
     * Bumped when a blob's shape changes. A future field that cannot be
     * defaulted should refuse to load loudly rather than silently emptying a
     * library that exists on disk.
     */
    private const val INDEX_VERSION = 1
    private const val KEY_INDEX_VERSION = "downloaded_index_version"

    private lateinit var prefs: SharedPreferences

    /**
     * Set in [init] so the *synchronous* existence check can verify a
     * `content://` record without a [Context] in hand — see [existsFor]. The
     * app is single-process, so this is set before anything can ask.
     */
    private lateinit var appContext: Context

    /**
     * Whether a recorded uri still resolves to a file, remembered for the life
     * of a playback decision.
     *
     * Verifying a `content://` download costs a binder round trip, which is why
     * [isMissingLocalFile] used to refuse to answer for anything but `file://`
     * and leave `content://` records permanently unverified — one code path
     * checking, four others not, so the same track could be reported as
     * downloaded by the song sheet and unplayable by the player. The check is
     * cheap enough once per uri, so the refusal is not needed: answer it, and
     * remember the answer.
     *
     * Invalidated wholesale by any mutation of the record ([record], [forget],
     * [recordCollections]) and by [invalidateExistence], so a file deleted
     * behind the app's back is re-checked on the next play. Bounded, and
     * cleared rather than evicted, because a library is small enough that the
     * whole working set fits and a partial eviction buys nothing.
     */
    private val existsMemo = ConcurrentHashMap<String, Boolean>()

    /** Drops every remembered existence answer. Forgets are rare; this is cheap. */
    fun invalidateExistence() = existsMemo.clear()

    private const val EXISTS_MEMO_LIMIT = 8_000
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val metadataSerializer = MapSerializer(String.serializer(), SavedSongMetadata.serializer())
    private val collectionSerializer = MapSerializer(String.serializer(), SavedCollection.serializer())

    private val _active = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val active: StateFlow<Map<String, DownloadState>> = _active.asStateFlow()

    /**
     * Ids asked for as part of a release's own download tap, by the browseId
     * that was tapped.
     *
     * [active] is one flat map for the whole app — a track queued from one
     * release is still the same row if it happens to sit in another release
     * too, and correctly so. But that means a release page can't tell "one of
     * my tracks is queued" apart from "one of my tracks is queued *because I
     * was asked for*" just by scanning [active] for its own ids: two releases
     * that happen to share a track would both read as downloading the moment
     * either one is. This is what lets a release's header ask the narrower
     * question instead — never pruned explicitly, since a stale id here is
     * harmless once it drops out of [active].
     */
    private val _requested = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val requested: StateFlow<Map<String, Set<String>>> = _requested.asStateFlow()

    /** Record that [videoIds] were asked for as [browseId]'s own release. */
    fun markRequested(browseId: String, videoIds: Collection<String>) {
        if (videoIds.isEmpty()) return
        _requested.update { it + (browseId to (it[browseId].orEmpty() + videoIds)) }
    }

    private val _saved = MutableStateFlow<Map<String, String>>(emptyMap())

    /** videoId to the uri of the file saved for it. */
    val saved: StateFlow<Map<String, String>> = _saved.asStateFlow()

    private val _savedMetadata = MutableStateFlow<Map<String, SavedSongMetadata>>(emptyMap())

    /**
     * The same records with their display metadata — title, artist, artwork,
     * format. Read for the Liked cache join, which needs a row's file *and* its
     * badge from one map rather than two.
     */
    internal val savedMetadata: Map<String, SavedSongMetadata> get() = _savedMetadata.value

    private val _collections = MutableStateFlow<Map<String, SavedCollection>>(emptyMap())

    /**
     * The releases that were downloaded *as* releases, by the id they were asked
     * for under.
     *
     * Read by the Downloads page so a batch download reads back as the thing
     * that was tapped. Exposed rather than kept private because the page is a
     * snapshot and has to be retaken when this changes — see
     * [collectionsAmong], which is what turns it into something drawable.
     */
    val collections: StateFlow<Map<String, SavedCollection>> = _collections.asStateFlow()

    /** Waiting, in the order asked for. Guarded by [lock]. */
    private val pending = LinkedHashMap<String, Song>()

    private val lock = Any()

    /**
     * The tracks taken off the queue and not yet finished, to the job fetching
     * each — null in the gap between a worker claiming a track and its job
     * existing.
     *
     * A map rather than the single slot this used to be, because several
     * downloads run at once now. That plurality is the only reason it is here:
     * [cancel] has to find *this* track's job among several, and a worker
     * claiming the next track must not be able to step on another worker's.
     * Guarded by [lock].
     */
    private val running = LinkedHashMap<String, Job?>()

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val legacy = context.getSharedPreferences(LEGACY_PREFS_FILE, Context.MODE_PRIVATE)
        // Moved out of the shared settings file. It was three JSON blobs, the
        // largest of which reached ~700 KB for a 1,600-track library, living in
        // the same XML as every setting the app has — so each completed download
        // rewrote the entire settings file atomically, temp file and fsync and
        // all, carrying the whole library with it, four times over on a batch.
        migrateLegacy(legacy)
        // These blobs reach hundreds of kilobytes for a large library, and
        // parsing them ran before the first frame. Loaded off the main thread
        // and merged *under* the live flows, so an edit that somehow lands
        // first wins instead of being overwritten by this snapshot.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val saved = decode(KEY_SAVED, serializer) { prefs.getString(it, null) }
            val metadata = decode(KEY_SAVED_METADATA, metadataSerializer) { prefs.getString(it, null) }
            val collections = decode(KEY_SAVED_COLLECTIONS, collectionSerializer) { prefs.getString(it, null) }
            _saved.updateAndGet { saved + it }
            _savedMetadata.updateAndGet { metadata + it }
            _collections.updateAndGet { collections + it }
        }
    }

    /**
     * Carry the index across from the shared settings file, once.
     *
     * Copy-then-clear, and the clear only happens after the copy has been
     * committed — a half-finished migration that emptied the record would look
     * exactly like a library the user never downloaded, and there is no backup
     * of this index anywhere. Leaving the old keys in place instead of erasing
     * them is deliberately not done: they would keep costing a rewrite of the
     * settings file on every single setting change.
     */
    private fun migrateLegacy(legacy: SharedPreferences) {
        if (prefs.getString(KEY_INDEX_VERSION, null) != null) return
        val moves: List<Pair<String, (String) -> Map<String, *>>> = listOf(
            KEY_SAVED to { raw: String -> json.decodeFromString(serializer, raw) },
            KEY_SAVED_METADATA to { raw: String -> json.decodeFromString(metadataSerializer, raw) },
            KEY_SAVED_COLLECTIONS to { raw: String -> json.decodeFromString(collectionSerializer, raw) },
        )
        val editor = prefs.edit()
        var moved = 0
        for ((key, decodeFn) in moves) {
            val raw = legacy.getString(key, null) ?: continue
            editor.putString(key, raw)
            moved++
            // A blob that will not parse is left where it is rather than
            // carried over as "{}": an unreadable record is worth keeping for a
            // future hand, and is reported below.
            runCatching { decodeFn(raw) }.onFailure {
                Log.w(TAG, "legacy $key unreadable, left in place: ${it.message}")
            }
        }
        if (moved == 0) {
            editor.putString(KEY_INDEX_VERSION, INDEX_VERSION.toString())
            editor.commit()
            return
        }
        if (!editor.commit()) {
            Log.w(TAG, "download index migration did not commit; keeping the legacy keys")
            return
        }
        val clear = legacy.edit()
        moves.forEach { (key, _) -> clear.remove(key) }
        clear.commit()
        editor.putString(KEY_INDEX_VERSION, INDEX_VERSION.toString()).commit()
        Log.i(TAG, "moved $moved download index blobs into $PREFS_FILE")
    }

    /**
     * Parse one blob, or report the failure and answer empty.
     *
     * The silent `.getOrDefault(emptyMap())` this replaced turned a corrupt or
     * schema-broken record into a library that had mysteriously never been
     * downloaded — the app then streamed every one of those tracks and offered
     * to download them all again. A decode failure is now logged at warning
     * level with the key named, which is the difference between a bug report
     * that can be acted on and one that cannot.
     */
    private fun <T> decode(key: String, serializer: KSerializer<Map<String, T>>, read: (String) -> String?): Map<String, T> {
        val raw = read(key) ?: return emptyMap()
        return runCatching { json.decodeFromString(serializer, raw) }
            .getOrElse {
                Log.w(TAG, "download index '$key' unreadable (${it.message}); treating as empty")
                emptyMap()
            }
    }

    // ---- Asking -------------------------------------------------------------

    /**
     * Queue [song], and make sure something is draining the queue.
     *
     * A track already saved, queued or running is left alone rather than
     * doubled — the menu row shows which of those it is, but a second tap
     * before the sheet updates should still be a no-op.
     *
     * The Wi-Fi-only check is here rather than only at the tap because this is
     * the one door into the queue, and a setting that can be bypassed by a
     * caller that forgot about it is not a setting. Callers that can say
     * something better than a failed row — a single toast for a whole album, say
     * — check [AppSettings.downloadsAllowedNow] themselves first; this is what
     * catches the rest.
     *
     * @param from what release this track was asked for as part of, when it was
     *   one of many. Carried no further than [DownloadSession], which is the
     *   only thing that has to say *why* forty tracks are in the queue.
     */
    fun enqueue(context: Context, song: Song, from: String? = null) {
        val id = song.videoId
        if (!AppSettings.downloadsAllowedNow) {
            // Distinct from the duplicate-tap no-op below: nothing is in flight
            // here to leave alone, and a refusal nobody is told about reads as a
            // dead button. A download already queued or running started on a
            // connection that allowed it and is none of this check's business.
            val inFlight = _active.value[id]
            if (inFlight !is DownloadState.Queued && inFlight !is DownloadState.Running) {
                DownloadSession.queued(song, from)
                fail(id, WIFI_ONLY_REFUSAL)
            }
            return
        }
        synchronized(lock) {
            if (id in pending || id in running) return
            pending[id] = song
        }
        _active.update { it + (id to DownloadState.Queued) }
        DownloadSession.queued(song, from)

        val app = context.applicationContext
        runCatching {
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        }.onFailure {
            // Refused only when the app has no window and no exemption, which
            // means the queue has nothing to drain it and would sit there
            // looking accepted forever.
            Log.w(TAG, "could not start the download service: ${it.message}")
            synchronized(lock) { pending.remove(id) }
            fail(id, "Downloads can't start right now")
        }
    }

    /**
     * Drop [videoId] from the queue, or stop it if it is one of the ones
     * running.
     *
     * Dropping it from [running] is what makes this safe in the gap between a
     * track being dequeued and its job existing: a cancel landing in that
     * window finds no job to stop, but [onRunning] then finds the id it was
     * told to run is no longer wanted, and stops it on arrival.
     */
    fun cancel(videoId: String) {
        val job = synchronized(lock) {
            pending.remove(videoId)
            if (videoId !in running) return@synchronized null
            running.remove(videoId)
        }
        job?.cancel()
        clear(videoId)
        // A download the user called off is not something they need reminding to
        // check on, so it leaves the manager rather than sitting in it as a
        // permanent "cancelled" row.
        DownloadSession.forget(videoId)
    }

    // ---- The record ---------------------------------------------------------

    /**
     * The file saved for [videoId], or null — pruning the record if the file
     * has been deleted from under it.
     *
     * Touches the filesystem, so call it off the main thread.
     */
    suspend fun savedUri(context: Context, videoId: String): Uri? =
        resolveLocal(context, videoId)

    /**
     * The single answer to "is this track on the device, and where".
     *
     * There used to be five: a `file://`-only stat in [verifiedSavedUri], the
     * verifying [savedUri], bare record membership read straight out of
     * [saved], the unverified [snapshotSongs], and
     * [PlaybackService.isLocalPlayback]'s own guess. They disagreed — most
     * visibly for `content://` downloads, which the stat path could never
     * check — so the song sheet would say "saved" about a file the player had
     * already failed to open. Everything that needs the answer now comes here,
     * so the UI, the queue builder and the player's own recovery cannot
     * contradict each other.
     *
     * Verifies, and prunes a record it has just disproved, so a claim that has
     * been shown false is not kept around to be shown false again.
     */
    suspend fun resolveLocal(context: Context, videoId: String): Uri? = withContext(Dispatchers.IO) {
        val recorded = _saved.value[videoId] ?: return@withContext null
        val uri = recorded.toUri()
        // Remembered per uri, so asking for a track twice in a queue build
        // costs one check rather than two.
        if (existsRemembered(context, recorded, uri)) return@withContext uri
        Log.d(TAG, "$videoId was downloaded but the file is gone; forgetting it")
        forget(videoId)
        null
    }

    /**
     * [DownloadStore.exists], answered through the memo.
     *
     * Every verification pass this file runs — the whole-library scan and the
     * per-collection one — already opens each recorded file once. Routing those
     * answers through [existsMemo] hands them to playback for free, which is
     * what makes [verifiedSavedUri] affordable on a `content://` library: the
     * binder round trip has already been paid by whoever opened the Downloads
     * page, instead of once per row of every queue built afterwards.
     */
    private fun existsRemembered(context: Context, uriString: String, uri: Uri): Boolean {
        existsMemo[uriString]?.let { return it }
        val answer = DownloadStore.exists(context, uri)
        if (existsMemo.size >= EXISTS_MEMO_LIMIT) existsMemo.clear()
        existsMemo[uriString] = answer
        return answer
    }

    /**
     * Whether [uriString] resolves to a file right now, or null when this
     * answer cannot be had (unparseable, or a `content://` record asked before
     * [init] captured the app context).
     *
     * `file://` is answered by a `stat`, which is safe at the rate
     * [Song.toMediaItem] asks — once per item, for a whole queue. Anything else
     * needs the content resolver, so it goes through [existsMemo] and costs one
     * binder round trip per uri rather than per question.
     */
    private fun existsFor(uriString: String): Boolean? {
        if (uriString.startsWith("file://")) {
            val path = runCatching { uriString.toUri().path }.getOrNull() ?: return null
            return File(path).exists()
        }
        if (!::appContext.isInitialized) return null
        return existsRemembered(appContext, uriString, uriString.toUri())
    }

    /**
     * True only when [uriString] is known to be gone.
     *
     * "I could not tell" is false, never true: the caller drops a uri on a
     * true here, so an unanswerable question must not cost the user their
     * file. Stale records that do answer true are pruned by
     * [verifiedSavedUri], and the player's recovery catches whatever slips past.
     */
    fun isMissingLocalFile(uriString: String): Boolean = existsFor(uriString) == false

    /**
     * As [savedUri], but synchronous and without a [Context] parameter — for
     * [Song.toMediaItem], which builds a [MediaItem] on whatever thread that
     * happens to run on and has neither a suspend context nor a [Context] in
     * hand to reach [DownloadStore.exists] with.
     *
     * Without this, a record surviving the file it names — deleted by a file
     * manager, or a folder wiped out from under the app — sent the player a
     * `file://` uri to a path that is simply not there. Nothing downstream
     * checks that either: [AudioCache.playbackFactory] hands `file://` and
     * `content://` uris straight to [androidx.media3.datasource.FileDataSource],
     * which fails with `ERROR_CODE_IO_FILE_NOT_FOUND` — retried a handful of
     * times and then given up on, so the track just refuses to play, with
     * nothing to say why.
     *
     * Prunes the record on the way past, the same as [savedUri]: a claim that
     * has just been shown to be false is not worth keeping to be shown false
     * again on the next play.
     */
    fun verifiedSavedUri(videoId: String): String? {
        val recorded = _saved.value[videoId] ?: return null
        if (!isMissingLocalFile(recorded)) return recorded
        Log.d(TAG, "$videoId was downloaded but the file is gone; forgetting it")
        forget(videoId)
        return null
    }

    /**
     * The quality badge recorded when [videoId] was saved ("FLAC",
     * "AAC · 320 kbps", …), or null when unknown. Synchronous for
     * [Song.toMediaItem][com.music.bitchord.playback.toMediaItem], which
     * decides file-vs-stream on the calling thread.
     */
    fun savedFormat(videoId: String): String? = _savedMetadata.value[videoId]?.downloadFormat

    /** Delete the file saved for [videoId] and forget it. */
    suspend fun delete(context: Context, videoId: String): Boolean = withContext(Dispatchers.IO) {
        val uri = _saved.value[videoId]?.toUri() ?: return@withContext false
        val deleted = DownloadStore.delete(context, uri)
        forget(videoId)
        deleted
    }

    private fun forget(videoId: String) {
        record(saved = { it - videoId }, meta = { it - videoId })
        // The generation just moved, so a stale cache cannot survive this —
        // patch the rows and re-stamp rather than pay a rescan for one id.
        // Called for aliases too, so only the named id leaves; the scan that
        // found a file missing rebuilds the rest on its own pass.
        val cached = allSongsCache.get() ?: return
        allSongsCache.set(
            listingGeneration.get() to cached.second.filterNot { it.videoId == videoId },
        )
    }

    /**
     * Drop the record for [videoId] because a read of the file it names has
     * just failed.
     *
     * The public counterpart to [forget], for [PlaybackService.recoverFrom] —
     * the one caller that does not need to check anything first, because the
     * player has already done better than a check: it opened the file and got
     * `ENOENT`. That covers the `content://` records [isMissingLocalFile]
     * deliberately declines to answer for, which is the whole reason this is
     * reachable from outside.
     *
     * Named for what it asserts rather than what it does, so a caller that has
     * *not* established the file is missing has no business calling it.
     */
    fun forgetMissing(videoId: String) {
        if (videoId !in _saved.value) return
        Log.d(TAG, "$videoId could not be opened; forgetting the download")
        forget(videoId)
    }

    // ---- Releases -----------------------------------------------------------

    /**
     * Remember that [songs] were asked for as one release rather than one at a
     * time.
     *
     * The reason this exists at all: a batch download used to be indistinguishable
     * from forty separate ones the moment it finished. What reached the Downloads
     * page was forty rows, and the only thing that could group them back up was
     * whatever album tag each row happened to carry — which an album page's rows
     * don't carry at all (the release is billed once, in the header) and a
     * playlist's rows *never* can, because a playlist is not an album and its
     * tracks are off forty different ones. So the thing the user tapped was the
     * one thing not written down anywhere.
     *
     * Recorded at the tap rather than on completion, and keyed by the id that
     * was tapped, so re-downloading the same release updates one entry instead
     * of accumulating near-duplicates. The order is the running order the page
     * had, which is what makes the entry read back as the release rather than as
     * a bag of tracks.
     *
     * Nothing here asserts the files exist. That is deliberate and matches
     * [saved]: this is a record of what was *asked* for, and which of those
     * tracks is actually on disk is answered where it is read — see
     * [collectionsAmong].
     */
    fun rememberCollection(target: DownloadTarget, songs: List<Song>) {
        if (songs.isEmpty()) return
        val ids = songs.map { it.videoId }.distinct()
        recordCollections { current ->
            val existing = current[target.id]
            val record = SavedCollection(
                id = target.id,
                title = target.title,
                subtitle = target.subtitle,
                // Once the remote cover has been cached, do not replace it with
                // the YouTube URL when the same collection is downloaded again.
                thumbnailUrl = existing?.thumbnailUrl?.takeIf(::isLocalArtwork)
                    ?: target.thumbnailUrl
                    ?: existing?.thumbnailUrl,
                playlist = target.playlist,
                // A release fetched in pages can be downloaded twice from two
                // different depths of the same page, so the two asks are merged
                // rather than the second replacing the first — but the new order
                // leads, since it is the one just seen on screen.
                videoIds = (ids + (existing?.videoIds ?: emptyList())).distinct(),
            )
            current + (target.id to record)
        }
    }

    /** Replace a collection's remote artwork URL with its durable local copy. */
    fun rememberCollectionArtwork(id: String, thumbnailUrl: String) {
        recordCollections { current ->
            val existing = current[id] ?: return@recordCollections current
            if (existing.thumbnailUrl == thumbnailUrl) current
            else current + (id to existing.copy(thumbnailUrl = thumbnailUrl))
        }
    }

    private fun isLocalArtwork(url: String): Boolean =
        url.startsWith("file:") || url.startsWith("content:")

    /** Drop a release from the record without touching the files under it. */
    fun forgetCollection(id: String) {
        if (id !in _collections.value) return
        recordCollections { it - id }
    }

    /**
     * Delete every file downloaded for release [id] and drop the record of it.
     *
     * The counterpart to [forgetCollection]: that one is for a record whose
     * files are already gone, this one is what actually takes them off the
     * device — the "delete download" a whole album or playlist card offers,
     * where a single track only ever offers [delete].
     */
    suspend fun deleteCollection(context: Context, id: String): Boolean {
        val record = _collections.value[id] ?: return false
        var any = false
        record.videoIds.forEach { videoId -> if (delete(context, videoId)) any = true }
        forgetCollection(id)
        return any
    }

    /**
     * How one downloaded playlist is addressed as a page of its own.
     *
     * Under `local:` deliberately: that prefix is how the rest of the app asks
     * "is this already on the device?", and it is what keeps the download button
     * off such a page's header and out of its menu. What it must not be taken
     * for is one of the two device *folders* — `local:downloads` and `local:all`
     * open the tabbed Songs / Artists / Albums view, and this opens a plain
     * track listing — so it gets a segment of its own rather than an id in the
     * same namespace.
     *
     * Built for playlists, which is why the word is in the prefix: a playlist's
     * tracks are off forty different releases and no tag on any of them names
     * it, so it is the one that needs a page. A downloaded album opens through
     * it as well, from its card on the Library's On Device shelf — the record
     * is the same either way — though the Albums tab still groups one up on
     * its own.
     */
    const val PLAYLIST_PREFIX = "local:playlist:"

    /** The page id for the release recorded under [id]. */
    fun pageIdFor(id: String): String = PLAYLIST_PREFIX + id

    /** The release [pageIdFor] built [browseId] from, or null if it didn't. */
    fun recordIdOf(browseId: String): String? =
        browseId.removePrefix(PLAYLIST_PREFIX).takeIf { it != browseId && it.isNotEmpty() && !it.startsWith("sp_local_") }

    /**
     * The playlists and albums downloaded whole, in name order, without their
     * tracks.
     *
     * What the Library page's On Device shelf draws a card from. Unlike
     * [collectionsAmong] there is no track list here to prune against — that
     * page never reads the folder — so this prunes against [onDisk] instead,
     * which drops a playlist once the last file recorded for it has been deleted
     * *through this app* and keeps the rest. That is the same claim [saved] makes
     * everywhere else, and opening the card is what settles it either way.
     *
     * It is also what keeps a playlist off the shelf between the tap that queues
     * it and the first track landing: [rememberCollection] writes the record at
     * the tap, and a playlist with nothing downloaded yet is not on the device.
     *
     * [onDisk] is a parameter for the same reason [collectionsAmong] takes its
     * songs: the rule is worth stating on a known folder rather than only on
     * whatever this process happens to have recorded.
     */
    fun savedReleases(onDisk: Map<String, String> = _saved.value): List<SavedCollection> {
        if (_collections.value.isEmpty()) return emptyList()
        return _collections.value.values
            .filter { record -> record.videoIds.any { it in onDisk } }
            .sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    /**
     * The releases at least one of [songs] belongs to, each with its own tracks
     * picked out of that list.
     *
     * Given the page's own songs rather than reading the disk itself, because
     * the page has already done that work — every row in it is a file that was
     * there when it was taken — and a release is only worth drawing for the
     * tracks that survived. A release whose files have all been deleted from a
     * file manager therefore disappears from the page without anything having to
     * notice it went.
     *
     * The lookup goes through [saved] as well as by id because one file answers
     * to two of them: a music video is swapped for its catalogue track on the
     * way down (see [remember]) and the page keeps whichever of the pair it
     * listed first, which is not necessarily the id the release named.
     */
    fun collectionsAmong(songs: List<Song>): List<DownloadedCollection> {
        if (songs.isEmpty() || _collections.value.isEmpty()) return emptyList()
        val byId = songs.associateBy { it.videoId }
        val byUri = songs.mapNotNull { song -> song.localUri?.let { it to song } }.toMap()
        val uris = _saved.value
        return _collections.value.values
            .mapNotNull { record ->
                val tracks = record.videoIds
                    .mapNotNull { id -> byId[id] ?: uris[id]?.let(byUri::get) }
                    .distinctBy { it.localUri ?: it.videoId }
                if (tracks.isEmpty()) {
                    null
                } else {
                    DownloadedCollection(
                        id = record.id,
                        title = record.title,
                        subtitle = record.subtitle,
                        thumbnailUrl = record.thumbnailUrl ?: tracks.firstNotNullOfOrNull { it.thumbnailUrl },
                        playlist = record.playlist,
                        songs = tracks,
                    )
                }
            }
            .sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    private fun recordCollections(update: (Map<String, SavedCollection>) -> Map<String, SavedCollection>) {
        synchronized(collectionLock) {
            val map = update(_collections.value)
            _collections.value = map
            if (::prefs.isInitialized) {
                synchronized(recordLock) {
                    persistLocked(
                        mapOf(KEY_SAVED_COLLECTIONS to json.encodeToString(collectionSerializer, map)),
                    )
                }
            }
        }
    }

    private val collectionLock = Any()

    /**
     * Record one file under every id it could be asked about.
     *
     * [asked] is the row the user tapped and [fetched] is what was actually
     * downloaded, and for a music video those are two different tracks. Filing
     * it under both is what lets the same song, found later through search,
     * still know it is already on the device. A stale id costs nothing: the
     * verification in [savedUri] prunes whichever one stops resolving.
     */
    private fun remember(
        asked: Song,
        fetched: Song,
        uri: Uri,
        downloadFormat: String? = null,
        artworkUri: String? = null,
    ) {
        val ids = setOf(asked.videoId, fetched.videoId)
        // MediaStore supplies this for exported downloads, but app-private and
        // legacy file:// downloads never pass through that index. Keep the
        // creation time in our own record so "Date added" remains stable if a
        // file is subsequently retagged or otherwise modified. An adopted file
        // predating this field falls back to its last-modified time.
        val fileModifiedMillis = uri.takeIf { it.scheme == "file" }
            ?.path
            ?.let(::File)
            ?.lastModified()
            ?.takeIf { it > 0 }
        val existingAdded = ids.firstNotNullOfOrNull { _savedMetadata.value[it]?.dateAddedSeconds }
        val dateAddedSeconds = resolvedDownloadDates(existingAdded, fileModifiedMillis).first
            ?: System.currentTimeMillis() / 1_000
        // HLS packages cannot carry MP4 tags. Point the app's own metadata at
        // the cover saved beside their playlist so Downloads remains fully
        // offline even though another player cannot open that package.
        val savedArtwork = uri.takeIf { it.scheme == "file" && it.lastPathSegment == "playlist.m3u8" }
            ?.path?.let(::File)?.parentFile
            ?.listFiles()?.firstOrNull { it.nameWithoutExtension == "cover" }
            ?.let(Uri::fromFile)?.toString()
        // Either row may be the one that knew the release: a music video is
        // swapped for the catalogue track before this, and it is the catalogue
        // row that usually carries the album — but a search hit tapped directly
        // is both, and an album page's rows are neither.
        val album = fetched.albumName?.takeIf { it.isNotBlank() }
            ?: asked.albumName?.takeIf { it.isNotBlank() }
        val metaAsked = SavedSongMetadata(
            videoId = asked.videoId,
            title = asked.title,
            artist = asked.artist,
            thumbnailUrl = savedArtwork ?: artworkUri ?: asked.thumbnailUrl,
            durationText = asked.durationText,
            albumName = album,
            uri = uri.toString(),
            downloadFormat = downloadFormat,
            dateAddedSeconds = dateAddedSeconds,
        )
        val metaFetched = SavedSongMetadata(
            videoId = fetched.videoId,
            title = fetched.title,
            artist = fetched.artist,
            thumbnailUrl = savedArtwork ?: artworkUri ?: fetched.thumbnailUrl,
            durationText = fetched.durationText,
            albumName = album,
            uri = uri.toString(),
            downloadFormat = downloadFormat,
            dateAddedSeconds = dateAddedSeconds,
        )
        record(
            saved = { it + ids.associateWith { id -> uri.toString() } },
            meta = {
                it + mapOf(asked.videoId to metaAsked, fetched.videoId to metaFetched)
            },
        )
        patchCacheFor(asked.videoId, fetched.videoId)
    }

    /**
     * Folds freshly recorded ids into the memoized listing without a rescan.
     *
     * The file was just written by this device, so no existence check is
     * owed: the row is built from the record exactly as the verifier would
     * build it. New ids land at the end, matching date-added order.
     */
    private fun patchCacheFor(vararg videoIds: String) {
        val cached = allSongsCache.get() ?: return
        val metaMap = _savedMetadata.value
        val rows = videoIds.mapNotNull { id ->
            metaMap[id]?.let { meta ->
                Song(
                    videoId = meta.videoId,
                    title = meta.title,
                    artist = meta.artist,
                    thumbnailUrl = meta.thumbnailUrl,
                    durationText = meta.durationText,
                    albumName = meta.albumName,
                    localUri = meta.uri,
                    downloadFormat = meta.downloadFormat,
                    localDateAddedSeconds = meta.dateAddedSeconds,
                    localDateModifiedSeconds = null,
                )
            }
        }.associateBy { it.videoId }
        if (rows.isEmpty()) return
        allSongsCache.set(
            listingGeneration.get() to (cached.second.filterNot { it.videoId in rows } + rows.values),
        )
    }

    /**
     * Apply [saved] and [meta] to the two records and write the result down.
     *
     * Takes transforms rather than finished maps because several downloads
     * finish at once now, and "read the map, add my track, store it back" run
     * from two threads loses one of the two tracks — silently, and permanently,
     * since this is the only record that a file was written. Both flows are
     * updated compare-and-set, and the persist is serialised so the copy that
     * reaches disk is never older than one already written.
     */
    private fun record(
        saved: (Map<String, String>) -> Map<String, String>,
        meta: (Map<String, SavedSongMetadata>) -> Map<String, SavedSongMetadata>,
    ) {
        if (!::prefs.isInitialized) {
            // Nothing to write to yet: keep the in-memory update, which the
            // next init will not know about, but do not pretend it was saved.
            _saved.updateAndGet(saved)
            _savedMetadata.updateAndGet(meta)
            return
        }
        // The lock covers the read as well as the write. It used to start after
        // both snapshots were taken, which is precisely the window the comment
        // above this function claimed to close: two downloads finishing together
        // could each build a complete map, and whichever wrote last won — so the
        // other track's row reached neither the file nor the next launch, while
        // memory kept claiming it was downloaded. Four download workers made it
        // routine. That is the "sometimes it streams a song I downloaded"
        // report, and it survives every fix that only touches playback policy.
        synchronized(recordLock) {
            listingGeneration.incrementAndGet()
            invalidateExistence()
            // One entry per collection opened, never removed. Cheap to keep
            // until the generation moves, which is every mutation.
            collectionSongsCache.clear()
            val savedMap = _saved.updateAndGet(saved)
            val metaMap = _savedMetadata.updateAndGet(meta)
            persistLocked(
                buildMap {
                    put(KEY_SAVED, json.encodeToString(serializer, savedMap))
                    put(KEY_SAVED_METADATA, json.encodeToString(metadataSerializer, metaMap))
                },
            )
        }
    }

    /**
     * The single write path.
     *
     * Coalesced and *committed*, not applied. `apply()` hands the write to
     * `QueuedWork`, which a force-stop, a low-memory kill or a crash between
     * the call and the write discards — and this index is the only record that
     * a file was ever downloaded, so losing its tail means re-downloading a
     * library the user is told they have. Committing off the calling thread
     * costs a synchronous write but never lies about durability.
     *
     * Coalescing matters more than it looks: a batch download finishes dozens of
     * tracks in a row, and each one used to encode and write the entire library
     * on its own. Now the pending payload is replaced rather than queued, so a
     * burst costs one write of the final state.
     */
    private val indexWriter = CoalescingWriter { payload ->
        prefs.edit().apply {
            payload.forEach { (key, value) -> putString(key, value) }
        }.commit()
    }

    private fun persistLocked(payload: Map<String, String>) = indexWriter.submit(payload)

    private val recordLock = Any()

    /**
     * Mutation counter for everything below that reads the disk. Verifying
     * every recorded file means opening every one of them — a 1,600-track
     * folder costs ten seconds of file opens — so verified listings are
     * memoized per generation: reopening is instant, and any mutation
     * (save, delete, prune) transparently rebuilds on next read. Single-song
     * mutations additionally patch the cache in place (see [remember] and
     * [forget]) so one new like never costs a rescan.
     */
    private val listingGeneration = AtomicInteger(0)
    private val allSongsCache = AtomicReference<Pair<Int, List<Song>>?>(null)
    private val collectionSongsCache = ConcurrentHashMap<String, Pair<Int, List<Song>>>()

    /** Chunk size for progressive verification; see [verifiedDownloadedSongs]. */
    private const val VERIFY_CHUNK_SIZE = 200

    /** Returns all downloaded songs whose files still exist on disk. */
    suspend fun getDownloadedSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
        // Captured before the work and stamped with *that*, not with whatever
        // the counter happens to say afterwards. Reading it after meant a
        // download finishing mid-scan was recorded as already included, and the
        // result was then served as fresh for the rest of the process — a
        // listing that silently never gains a track.
        val gen = listingGeneration.get()
        allSongsCache.get()?.takeIf { it.first == gen }?.let { return@withContext it.second }
        verifiedDownloadedSongs(context).also { allSongsCache.set(gen to it) }
    }

    /**
     * Rows straight from the record, no disk I/O.
     *
     * Instant on any size of library — including a cold start, where the
     * record is already on disk from the last session. A file deleted behind
     * the app's back can linger here until the background verification pass
     * corrects it; that pass runs on every cold open, so the window is one
     * screen, not one session.
     */
    fun snapshotSongs(): List<Song> {
        val metaMap = _savedMetadata.value
        val seenUris = mutableSetOf<String>()
        return metaMap.values.mapNotNull { meta ->
            if (!seenUris.add(meta.uri)) return@mapNotNull null
            Song(
                videoId = meta.videoId,
                title = meta.title,
                artist = meta.artist,
                thumbnailUrl = meta.thumbnailUrl,
                durationText = meta.durationText,
                albumName = meta.albumName,
                localUri = meta.uri,
                downloadFormat = meta.downloadFormat,
                localDateAddedSeconds = meta.dateAddedSeconds,
                localDateModifiedSeconds = null,
            )
        }
    }

    private suspend fun verifiedDownloadedSongs(
        context: Context,
        onChunk: ((List<Song>) -> Unit)? = null,
    ): List<Song> = withContext(Dispatchers.IO) {
        var sinceChunk = 0
        val metaMap = _savedMetadata.value
        val result = mutableListOf<Song>()
        val seenUris = mutableSetOf<String>()
        val migratedAddedByUri = mutableMapOf<String, Long>()
        val migratedFormatByUri = mutableMapOf<String, String>()

        for ((_, meta) in metaMap) {
            // A downloaded music video and the catalogue track it resolved to
            // are deliberately recorded as two ids for one file. Verify and
            // materialise that file once, not once per alias.
            if (!seenUris.add(meta.uri)) continue
            val uri = meta.uri.toUri()
            if (existsRemembered(context, meta.uri, uri)) {
                val fileModifiedMillis = uri.takeIf { it.scheme == "file" }
                    ?.path
                    ?.let(::File)
                    ?.lastModified()
                    ?.takeIf { it > 0 }
                val (dateAddedSeconds, dateModifiedSeconds) = resolvedDownloadDates(
                    persistedAddedSeconds = meta.dateAddedSeconds,
                    fileModifiedMillis = fileModifiedMillis,
                )
                if (meta.dateAddedSeconds == null && dateAddedSeconds != null) {
                    migratedAddedByUri[meta.uri] = dateAddedSeconds
                }
                val downloadFormat = meta.downloadFormat
                    ?: legacyYoutubeDownloadBadge(context, uri)?.also {
                        migratedFormatByUri[meta.uri] = it
                    }
                result.add(
                    Song(
                        videoId = meta.videoId,
                        title = meta.title,
                        artist = meta.artist,
                        thumbnailUrl = meta.thumbnailUrl,
                        durationText = meta.durationText,
                        albumName = meta.albumName,
                        localUri = meta.uri,
                        downloadFormat = downloadFormat,
                        localDateAddedSeconds = dateAddedSeconds,
                        localDateModifiedSeconds = dateModifiedSeconds,
                    )
                )
            } else {
                // Clear every id for the shared file. Leaving its second alias
                // behind would make a later menu claim the missing download
                // still exists.
                metaMap.filterValues { it.uri == meta.uri }.keys.forEach(::forget)
            }
            if (++sinceChunk >= VERIFY_CHUNK_SIZE) {
                sinceChunk = 0
                onChunk?.invoke(result.toList())
            }
        }
        onChunk?.invoke(result.toList())
        if (migratedAddedByUri.isNotEmpty() || migratedFormatByUri.isNotEmpty()) {
            record(
                saved = { it },
                meta = { current ->
                    current.mapValues { (_, saved) ->
                        saved.copy(
                            dateAddedSeconds = saved.dateAddedSeconds
                                ?: migratedAddedByUri[saved.uri],
                            downloadFormat = saved.downloadFormat
                                ?: migratedFormatByUri[saved.uri],
                        )
                    }
                },
            )
        }
        result
    }

    /**
     * Returns the available files for one downloaded collection, in its saved
     * running order.
     *
     * Opening one downloaded playlist used to call [getDownloadedSongs] and
     * then build every saved collection merely to discard all but this one.
     * That makes the cost of opening a ten-track playlist proportional to the
     * listener's entire Downloads folder, including a ContentResolver open for
     * every recorded id.  The collection record already says exactly which
     * files can belong here, so only those files need to be verified.
     */
    /**
     * One collection, straight from the record, no disk I/O.
     *
     * The Downloads page opens instantly because [snapshotSongs] exists. A
     * downloaded *playlist* had no equivalent, so opening one waited on
     * [getCollectionSongs] — a content-resolver open per track, for every
     * track in the collection — before it painted anything at all. On a big
     * collection that is the minute-long wait, and it repeated on every cold
     * start because the memo behind that path is in memory only: backing out
     * and in again was instant, which is exactly the difference between the
     * first open and every later one.
     *
     * Same trade as [snapshotSongs] and for the same reason — the record is a
     * claim about files, and the verified pass that follows this settles it.
     * Rows whose file has since been deleted come off the list when it lands.
     */
    fun snapshotCollectionSongs(collectionId: String): List<Song> {
        val record = _collections.value[collectionId] ?: return emptyList()
        val meta = _savedMetadata.value
        val seen = HashSet<String>()
        return record.videoIds.mapNotNull { videoId ->
            val row = meta[videoId] ?: return@mapNotNull null
            if (!seen.add(row.uri)) return@mapNotNull null
            Song(
                videoId = row.videoId,
                title = row.title,
                artist = row.artist,
                thumbnailUrl = row.thumbnailUrl,
                durationText = row.durationText,
                albumName = row.albumName,
                localUri = row.uri,
                downloadFormat = row.downloadFormat,
                localDateAddedSeconds = row.dateAddedSeconds,
                localDateModifiedSeconds = null,
            )
        }
    }

    suspend fun getCollectionSongs(context: Context, collectionId: String): List<Song> =
        withContext(Dispatchers.IO) {
            val gen = listingGeneration.get()
            collectionSongsCache[collectionId]?.takeIf { it.first == gen }
                ?.let { return@withContext it.second }
            verifiedCollectionSongs(context, collectionId).also {
                collectionSongsCache[collectionId] = gen to it
            }
        }

    private suspend fun verifiedCollectionSongs(context: Context, collectionId: String): List<Song> =
        withContext(Dispatchers.IO) {
            val record = _collections.value[collectionId] ?: return@withContext emptyList()
            val metadata = _savedMetadata.value
            val saved = _saved.value
            val metadataByUri = metadata.values.associateBy { it.uri }
            val seenUris = HashSet<String>()

            record.videoIds.mapNotNull { videoId ->
                val uriString = saved[videoId] ?: metadata[videoId]?.uri ?: return@mapNotNull null
                if (!seenUris.add(uriString)) return@mapNotNull null
                val uri = uriString.toUri()
                if (!existsRemembered(context, uriString, uri)) {
                    // Both a video id and its catalogue replacement can name
                    // this file. Prune every alias now that the one disk check
                    // has established that the shared file is gone.
                    saved.filterValues { it == uriString }.keys.forEach(::forget)
                    return@mapNotNull null
                }

                val meta = metadata[videoId] ?: metadataByUri[uriString] ?: return@mapNotNull null
                val fileModifiedMillis = uri.takeIf { it.scheme == "file" }
                    ?.path
                    ?.let(::File)
                    ?.lastModified()
                    ?.takeIf { it > 0 }
                val (dateAddedSeconds, dateModifiedSeconds) = resolvedDownloadDates(
                    persistedAddedSeconds = meta.dateAddedSeconds,
                    fileModifiedMillis = fileModifiedMillis,
                )
                Song(
                    videoId = videoId,
                    title = meta.title,
                    artist = meta.artist,
                    thumbnailUrl = meta.thumbnailUrl,
                    durationText = meta.durationText,
                    albumName = meta.albumName,
                    localUri = uriString,
                    downloadFormat = meta.downloadFormat ?: legacyYoutubeDownloadBadge(context, uri),
                    localDateAddedSeconds = dateAddedSeconds,
                    localDateModifiedSeconds = dateModifiedSeconds,
                )
            }
        }

    private fun String.toUri(): Uri = Uri.parse(this)

    // ---- Driven by DownloadService -----------------------------------------

    /**
     * The next track to fetch, or null when the queue is empty.
     *
     * Claims it as running under the same lock that removed it, so there is no
     * instant where a track is in neither the queue nor the running slot and a
     * [cancel] for it would quietly do nothing.
     */
    internal fun takeNext(): Song? = synchronized(lock) {
        val entry = pending.entries.firstOrNull() ?: return null
        pending.remove(entry.key)
        running[entry.key] = null
        entry.value
    }

    /** Attach the job fetching [videoId], unless it has been cancelled meanwhile. */
    internal fun onRunning(videoId: String, job: Job) {
        val cancelled = synchronized(lock) {
            if (videoId !in running) return@synchronized true
            running[videoId] = job
            false
        }
        if (cancelled) job.cancel()
    }

    /** [videoId] is finished, one way or another, and no longer holds a worker. */
    internal fun onIdle(videoId: String) {
        synchronized(lock) { running.remove(videoId) }
    }

    /** Whether anything is still queued or in flight — see [DownloadService]'s workers. */
    internal fun busy(): Boolean = synchronized(lock) { pending.isNotEmpty() || running.isNotEmpty() }

    /**
     * The service is gone, so nothing is running any more.
     *
     * Distinct from [onIdle], which is one worker reporting one finished track.
     * This is the whole drain going away at once — every claim in [running] is
     * void, and leaving one behind would have [enqueue] refuse that track
     * forever as already in flight.
     */
    internal fun onStopped() {
        synchronized(lock) { running.clear() }
    }

    /**
     * Fetch one track, start to finish.
     *
     * Two halves, and the split is what lets a queue go at any speed: [prepare]
     * decides where the bytes come from and [transfer] moves them. Everything
     * that can go wrong past the point of reserving a destination has to
     * unreserve it — a cancelled or failed download must not leave a partial
     * file behind pretending to be a whole one, which is what
     * [DownloadStore.Pending] exists to make hard to get wrong.
     *
     * Pinned to [Dispatchers.IO] here rather than trusted to arrive on it.
     * Resolving a stream blocks on HTTP and runs YouTube's player JavaScript
     * through Rhino, and [DownloadService] drives this from a main-thread scope
     * so its notification work stays where it belongs — inheriting that would
     * put every network call in the resolve on the main thread, where they
     * don't fail loudly so much as fail *uniformly*: `NetworkOnMainThreadException`
     * is caught by the same per-client `runCatching` that exists to tolerate a
     * client being turned away, so every client appears to be refused and the
     * whole thing reads as a network outage.
     *
     * Several of these run at once — see [DownloadService]. Nothing in here is
     * shared between them but the two state flows, and both are written through
     * atomic updates for exactly that reason.
     */
    internal suspend fun run(context: Context, song: Song) = withContext(Dispatchers.IO) {
        val id = song.videoId
        // Set before the lookup, not after it. Resolving where a lossless track
        // comes from is the long part of a download, and leaving the row on
        // "Queued" for all of it reads as a queue that has stopped rather than
        // one that is working.
        _active.update { it + (id to DownloadState.Running(0f)) }
        DownloadSession.running(id, 0f)

        try {
            val plan = prepare(context, song)
            // The manager is showing the row that was tapped, which for a music
            // video is the wrong title and the wrong cover for the file actually
            // being written. Corrected here rather than left to disagree with
            // the notification and with the Downloads page afterwards.
            DownloadSession.retitle(id, plan.track)
            transfer(context, song, plan)
        } catch (e: CancellationException) {
            clear(id)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download failed for $id: ${e.message}", e)
            fail(id, e.friendly())
        }
    }

    /**
     * Everything that has to be known before a byte can be asked for, and
     * nothing that touches the destination.
     *
     * Split out of [run] so it *can* be done ahead of time. Nothing does yet —
     * [DownloadService] still calls it inline, and the overlap that used to be
     * the point of the split is bought by running four workers instead. Kept
     * apart anyway, because the split is what makes the expensive half
     * separately measurable and separately cancellable.
     *
     * On a lossless queue this is the expensive half by a wide margin: a module
     * search fans out across a whole index and then a stream endpoint is
     * opened, tens of seconds against the few the transfer itself takes on a
     * fast connection. Run between transfers, as it used to be, that time was
     * simply the connection standing idle once per track, which is what a long
     * queue spent most of its life doing.
     *
     * Nothing here writes to [_active] or to [DownloadSession]. It may be
     * running for a track that is still queued — or for one that gets cancelled
     * before its turn — and a preparation is not a download.
     */
    internal suspend fun prepare(context: Context, song: Song): Prepared = withContext(Dispatchers.IO) {
        // Downloads preserve the exact item the listener picked. Catalogue
        // matching is a manual playback action and must not silently change a
        // download or its filename.
        val track = song
        // Read once, here, for the whole of this track. Both routes below
        // and the re-resolve inside [Downloader.fetch] have to agree on
        // which rung they are fetching, and re-reading the setting per call
        // would let a change made mid-download splice two renditions into
        // one file.
        val quality = AppSettings.downloadQuality.value

        // Asked before the lookup rather than after it, unlike the check on the
        // route's own filename below. A file already sitting in Music under a
        // lossless extension is the answer to the whole question, and spending
        // a twenty-second module search to arrive at a name we could have
        // guessed is the difference between re-running a 300-track queue in
        // seconds and re-running it in hours. Only the extensions that can only
        // be lossless are worth guessing at: an `.m4a` may be this app's ALAC
        // or its AAC, and adopting the wrong one would quietly answer a request
        // for lossless with a transcode.
        if (quality.keepsLossless) {
            LOSSLESS_EXTENSIONS.firstNotNullOfOrNull { extension ->
                DownloadStore.existing(context, DownloadStore.fileNameFor(track, extension))
            }?.let { uri ->
                return@withContext Prepared(song.videoId, track, route = null, alreadyAt = uri)
            }
        }

        val route = routeFor(context, track, quality)
        Log.d(TAG, "downloading ${song.videoId} as .${route.extension} (${route.describe}, ${quality.label})")
        Prepared(song.videoId, track, route = route, alreadyAt = null)
    }

    /**
     * What [prepare] worked out, ready for a transfer to be run against it.
     *
     * [videoId] rides along so a look-ahead can be checked against the track
     * actually taken off the queue: the two diverge whenever something is
     * cancelled while its route is being resolved, and a plan applied to the
     * wrong track would write one song's bytes under another's name.
     */
    internal class Prepared(
        val videoId: String,
        /** The catalogue track behind the row, which may not be the row. */
        val track: Song,
        /** Null when [alreadyAt] answered the question instead. */
        val route: Route?,
        /** A file already in Music that is this download, if there is one. */
        val alreadyAt: Uri?,
    )

    /**
     * Fetch the bytes [plan] points at and publish them.
     *
     * Owns the destination from end to end: every exit out of here either
     * commits or aborts, so a caller is free to call it a second time with a
     * freshly resolved plan without the first attempt leaving anything behind.
     */
    private suspend fun transfer(context: Context, song: Song, plan: Prepared) {
        val id = song.videoId
        val track = plan.track

        // Already there from a previous run the record lost track of — adopt it
        // rather than writing a second copy beside it.
        plan.alreadyAt?.let { uri ->
            remember(song, track, uri)
            DownloadSession.done(id)
            clear(id)
            return
        }
        val route = plan.route ?: error("Nothing to download")

        var pending: DownloadStore.Pending? = null
        var lyrics: Deferred<LyricsTag.Embeddable?>? = null
        var artwork: Deferred<MediaTagger.Artwork?>? = null
        try {
            coroutineScope {
                // Started before the transfer rather than after it, so four lyric
                // services are being raced while the bytes are already moving. Done
                // after the commit instead, every download would pay the slowest of
                // them in dead time — and it is a *suspending* wait, so it would sit
                // in the one stretch of this function that has no way back: past the
                // commit, [pending] is null and a cancellation there would abandon a
                // finished file that nothing has recorded yet. Awaited below while
                // there is still a pending destination to abort.
                //
                // [LyricsTag.forTrack] is contracted not to throw for anything but
                // cancellation, and that contract is load-bearing here: this is a
                // plain child of the scope, so a failure inside it would cancel the
                // download it was only meant to decorate.
                // HLS has no tag container, but its private offline package has
                // sidecars for exactly the same lyrics and full-resolution cover.
                if (route.taggable && (route.offlineHls != null || MediaTagger.carriesTags(route.extension))) {
                    lyrics = async { LyricsTag.forTrack(track) }
                    artwork = async { MediaTagger.artworkFor(context, track) }
                }

                val name = DownloadStore.fileNameFor(track, route.extension)
                val alreadyThere = DownloadStore.existing(context, name)
                if (alreadyThere != null) {
                    Log.d(TAG, "$name is already in Music; adopting it")
                    remember(song, track, alreadyThere)
                    DownloadSession.done(id)
                    clear(id)
                    return@coroutineScope
                }

                val manifest = route.offlineHls
                if (manifest != null) {
                    val onSegment: (Long, Long) -> Unit = { written, total ->
                        val fraction = written.toFloat() / total
                        _active.update { it + (id to DownloadState.Running(fraction)) }
                        DownloadSession.running(id, fraction)
                    }
                    val savedUri = if (manifest.dash) {
                        OfflineDash.save(
                            context = context,
                            id = id,
                            url = manifest.url,
                            headers = manifest.headers,
                            onProgress = onSegment,
                            lyrics = lyrics?.await(),
                            artwork = artwork?.await(),
                        )
                    } else {
                        OfflineHls.save(
                            context = context,
                            id = id,
                            url = manifest.url,
                            headers = manifest.headers,
                            onProgress = onSegment,
                            lyrics = lyrics?.await(),
                            artwork = artwork?.await(),
                        )
                    }
                    remember(song, track, savedUri, route.downloadFormat)
                    DownloadSession.done(id)
                    clear(id)
                    Log.d(TAG, "saved offline ${if (manifest.dash) "DASH" else "HLS"} package for $name")
                    return@coroutineScope
                }

                val destination = DownloadStore.begin(context, name, route.mimeType)
                pending = destination
                destination.openStream().use { sink ->
                    route.write(sink) { written, total ->
                        val fraction = written.toFloat() / total
                        _active.update { it + (id to DownloadState.Running(fraction)) }
                        DownloadSession.running(id, fraction)
                    }
                }
                val words = lyrics?.await()
                val cover = artwork?.await()
                // Publish only after metadata is part of the file. This keeps
                // concurrent album workers from exposing untagged tracks.
                MediaTagger.embed(context, destination.tagUri, track, route.extension, words, cover)
                val artworkUri = MediaTagger.persistArtwork(context, track.thumbnailUrl, cover)
                val savedUri = destination.commit()
                pending = null
                remember(song, track, savedUri, route.downloadFormat, artworkUri)
                DownloadSession.done(id)
                clear(id)
                Log.d(TAG, "saved $name")
            }
        } catch (e: Throwable) {
            pending?.abort()
            throw e
        } finally {
            // Every exit needs this, not just the failing ones: the adopt-it
            // path above returns with the lookup still in flight, and
            // [coroutineScope] does not return while a child of it is running —
            // so an unwaited job would hold the whole queue up for the length of
            // a lyrics search per already-downloaded track.
            lyrics?.cancel()
            artwork?.cancel()
        }
    }

    /**
     * One resolved download: what to call the file, what to tell the store it
     * is, and how to fill it.
     *
     * Exists so [run] has one linear body rather than two nearly-identical
     * ones. Everything after the bytes are chosen — the duplicate check, the
     * pending row, the commit, the tagging, the abort on failure — is the same
     * work whichever server the audio came from, and the two routes differ only
     * in these four answers.
     */
    internal class Route(
        val extension: String,
        val mimeType: String,
        /** For the log line, so a download's provenance is on the record. */
        val describe: String,
        /** Short premium-rendition badge shown only in BitChord's Downloads list. */
        val downloadFormat: String? = null,
        val taggable: Boolean = true,
        val offlineHls: Manifest? = null,
        val write: suspend (OutputStream, (written: Long, total: Long) -> Unit) -> Unit,
    )

    /**
     * A stream that arrived as an index rather than as audio, and which of the
     * two index formats it is. Both are saved into the same offline package —
     * see [OfflineDash] — so [dash] only decides who does the parsing.
     */
    internal class Manifest(
        val url: String,
        val headers: Map<String, String>,
        val dash: Boolean = false,
    )

    /**
     * Where this download's bytes are coming from.
     *
     * A configured source gets asked first, and YouTube is what happens when
     * none of them can serve it — see [SourceResolver.forDownload] for what
     * "can" means, which is narrower here than it is for playback.
     *
     * @param quality read once by the caller and passed down, so that a setting
     *   changed while this track is in the queue applies to the next one rather
     *   than to the middle of this one. [Downloader.fetch] resolves again after
     *   a mid-download refusal and has to ask for the same rung it started on.
     */
    private suspend fun routeFor(context: Context, track: Song, quality: DownloadQuality): Route {
        fromSources(track, quality)?.let { (stream, storable) ->
            // A manifest is an index, not audio. Whichever kind it is, fetching
            // it as a file writes the index into something named `.flac` —
            // which is exactly what a `.mpd` did until [OfflineDash] existed:
            // a 2.7 KB download that reported success and could never play.
            // Read off the URL rather than off `stream.format`, which describes
            // the audio inside and says nothing about the envelope.
            val dash = OfflineDash.handles(stream.url)
            val hls = stream.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
            val packaged = hls || dash
            // A manifest's segments are joined back into one ordinary file —
            // see [ManifestFile] — exported or not, so a lossless download is
            // a `.flac` wherever it lands. Exports used to decline manifests
            // instead, which meant YouTube: Tidal serves every lossless tier
            // as a manifest, so an exporting user heard FLAC and saved 131kbps
            // AAC. Only a codec the assembler can't file falls through to the
            // package (in-app) or YouTube (export) below.
            if (packaged && storable.extension in ManifestFile.EXTENSIONS) {
                return Route(
                    extension = storable.extension,
                    mimeType = storable.mimeType,
                    describe = "${stream.format.summary} (assembled from ${if (dash) "DASH" else "HLS"})",
                    downloadFormat = stream.format.downloadBadge(),
                    write = { sink, onProgress ->
                        ManifestFile.write(
                            context.cacheDir, stream.url, stream.headers, dash,
                            storable.extension, sink, onProgress,
                        )
                    },
                )
            }
            if (packaged && AppSettings.exportDownloads.value) {
                Log.d(TAG, "can't export a .${storable.extension} manifest; taking YouTube for ${track.videoId}")
                return@let
            }
            return Route(
                // DASH is saved *as* HLS — see [OfflineDash] for why — so both
                // kinds land as the same package and are named for what was
                // written rather than for what was fetched.
                extension = if (packaged) "m3u8" else storable.extension,
                mimeType = if (packaged) "application/vnd.apple.mpegurl" else storable.mimeType,
                describe = stream.format.summary,
                downloadFormat = stream.format.downloadBadge(),
                taggable = true,
                offlineHls = Manifest(stream.url, stream.headers, dash = dash).takeIf { packaged },
                write = { sink, onProgress ->
                    Downloader.fetchDirect(stream.url, stream.headers, sink, onProgress)
                },
            )
        }
        // MediaStore.Audio refuses audio/webm on affected Android versions.
        // Keep Opus for app-private downloads, but pin exported downloads to
        // AAC-in-MP4 for both the initial resolve and any mid-transfer retry.
        val requireM4a = AppSettings.exportDownloads.value
        val stream = StreamResolver.resolveForDownload(track.videoId, quality.maxKbps, requireM4a)
        return Route(
            extension = stream.downloadExtension,
            mimeType = stream.downloadMimeType,
            describe = "${stream.kbps}kbps ${stream.mimeType}",
            downloadFormat = youtubeDownloadBadge(stream.downloadExtension, stream.kbps),
            write = { sink, onProgress ->
                Downloader.fetch(track.videoId, stream, quality.maxKbps, requireM4a, sink, onProgress)
            },
        )
    }

    /**
     * The stream to keep for [track] from a configured source, with how to file
     * it — or null, which is not a failure, just YouTube's turn.
     *
     * Usually a bit-exact one; not always. [SourceResolver.forDownload] falls
     * back to the best lossy copy any enabled source holds when nothing has the
     * recording losslessly, and only gives up on the sources entirely when what
     * they offer would not beat YouTube's own AAC. Which of those happened is
     * the resolver's business — from here it is a URL and a codec either way.
     *
     * Bounded, because a module search waits on every backend it has (see
     * `ModuleSource.SEARCH_PATIENT_MS`) and does that once per query the matcher
     * offers. For a track no module holds, that is the whole queue stopped for
     * the better part of a minute on the way to a download that was always
     * going to be YouTube's. `PlaybackService.SUBSTITUTE_TIMEOUT_MS` bounds the
     * same search for the same reason.
     *
     * The [DownloadStore.storable] check belongs here rather than inside the
     * resolver: the resolver's job is finding the best audio, and whether this
     * device will keep a file of that codec is a question about Android.
     *
     * @param quality pinned by [run] for the whole of this track. Passed on to
     *   the resolver rather than left to it, so that the twenty seconds this may
     *   spend searching cannot be a window in which the setting changes and the
     *   two halves of one decision disagree.
     */
    private suspend fun fromSources(
        track: Song,
        quality: DownloadQuality,
    ): Pair<SourceStream, DownloadStore.Storable>? {
        // Wrapped so the two ways this can come back empty stay apart in the
        // log. `withTimeoutOrNull` collapses them into one null, and the
        // difference is the whole diagnosis: "no enabled source holds this
        // recording" is a fact about the track, and "the clock ran out" is a
        // fact about [SOURCE_LOOKUP_MS] — which is what silently sent a whole
        // Lossless queue to YouTube's AAC while the log said nothing at all.
        var timedOut = true
        val stream = withTimeoutOrNull(SOURCE_LOOKUP_MS) {
            try {
                SourceResolver.forDownload(
                    TrackMatcher.targetOf(track),
                    SourceResolver.requestForDownload(quality),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "source lookup failed for ${track.videoId}: ${e.message}")
                null
            }.also { timedOut = false }
        }
        if (stream == null) {
            if (timedOut) {
                Log.w(
                    TAG,
                    "source lookup for ${track.videoId} ran past ${SOURCE_LOOKUP_MS}ms; " +
                        "downloading it from YouTube instead of ${quality.label}",
                )
            }
            return null
        }

        val storable = DownloadStore.storable(stream.format.codec)
        if (storable == null) {
            Log.d(TAG, "nothing to file a '${stream.format.codec}' as; taking YouTube for ${track.videoId}")
            return null
        }
        return stream to storable
    }

    /** Back to "not downloaded" — used for success, where [saved] takes over, and for cancellation. */
    private fun clear(videoId: String) {
        _active.update { it - videoId }
    }

    private fun fail(videoId: String, reason: String) {
        _active.update { it + (videoId to DownloadState.Failed(reason)) }
        DownloadSession.failed(videoId, reason)
    }

    /**
     * A failure a user can read. The message on an [error] raised in this
     * package is already written for them; anything else is a network fault
     * with a class name for a message.
     *
     * [IllegalArgumentException] is in here because of one that wasn't: the
     * media store throws it for a MIME type it won't accept, and for a while
     * every download on this device failed that way and reported itself as a
     * connection problem. The message it carries is not written for a user, but
     * `Unsupported MIME type audio/webm` at least sends someone looking in the
     * right direction.
     */
    private fun Exception.friendly(): String = when {
        (this is IllegalStateException || this is IllegalArgumentException) &&
            !message.isNullOrBlank() -> message!!
        else -> "Download failed — check your connection"
    }

    /**
     * How long the source lookup may hold a download up before it goes to
     * YouTube regardless.
     *
     * **This has to clear `ModuleSource.SEARCH_PATIENT_MS`, and for a long time
     * it didn't.** It was 20s against a patient window of 25s, which made the
     * arithmetic decide the outcome: [SourceResolver.forDownload] asks every
     * source with `waitForAll = true`, that flag buys each module the full 25s
     * patient window, and this timeout fired five seconds before the window it
     * was waiting on could even close. Every track whose modules were not
     * unusually quick came back null — not "no source has it", but "nobody was
     * asked for long enough" — and null here means YouTube. So a Lossless
     * setting reliably produced YouTube's AAC, and it looked like a bulk-only
     * fault because it *was* one: a single download runs with the engines to
     * itself and lands inside 20s, while four workers sharing three interpreter
     * engines per module (`QuickJsExecutor.ENGINES_PER_MODULE`) do not.
     *
     * Sized off what it actually bounds, therefore, rather than off the playback
     * timeout it used to be matched to. One patient search is 25s, the matcher
     * offers up to three queries (`TrackMatcher.queries` — the third only for a
     * kana title, in the other script), and [streamBest] may then open up to
     * `STREAM_ATTEMPTS` stream endpoints on the winner. Eighty-five seconds
     * covers a slow-but-working index; it is still finite, because the
     * alternative is the queue stalled per track on modules that simply do not
     * have the recording.
     *
     * The comparison with playback is no longer the right one and is worth
     * stating plainly: `PlaybackService.SUBSTITUTE_TIMEOUT_MS` is short because
     * a listener is staring at a paused player. Nothing is waiting here, the
     * answer becomes a permanent file, and re-fetching it later costs the whole
     * download again.
     *
     * It bounds the lossy half of that lookup too, which is why
     * [SourceResolver.forDownload] runs both halves at once rather than in
     * turn: a fast source queued behind a slow one would spend this budget
     * waiting for a module and never be asked.
     */
    private const val SOURCE_LOOKUP_MS = 85_000L

    /**
     * The extensions a file in Music can carry that say, on their own, that a
     * lossless request has already been answered.
     *
     * `m4a` is deliberately absent even though [DownloadStore.storable] files
     * ALAC as one: an `.m4a` in this folder is just as likely to be the AAC a
     * download at the High rung wrote, and there is nothing in the name to
     * separate them. Guessing wrong there would answer a request for lossless
     * with a transcode and never fetch the real thing.
     */
    private val LOSSLESS_EXTENSIONS = listOf("flac", "wav")

    /**
     * Why a download didn't start, when the reason is a setting rather than a
     * fault.
     *
     * Names the switch, because a refusal that only says no leaves the user
     * looking for a network problem that isn't there. Shared with the callers
     * that show it as a toast so the two cannot drift apart.
     */
    internal const val WIFI_ONLY_REFUSAL = "Downloads are set to Wi-Fi only"

    /** Dropped when the sheet is reopened; a failure is worth showing once. */
    fun dismissFailure(videoId: String) {
        if (_active.value[videoId] is DownloadState.Failed) clear(videoId)
    }

    private const val KEY_SAVED = "downloaded_tracks"
}

@kotlinx.serialization.Serializable
internal data class SavedSongMetadata(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    /**
     * What release this track is off, when the row it was downloaded from knew.
     *
     * Added after the fact and defaulted, so a record written before it existed
     * still decodes — those entries come back with a null album and are filled
     * in from the file's own tags instead, see LocalMediaRepository.
     */
    val albumName: String? = null,
    val uri: String,
    val downloadFormat: String? = null,
    /** Stable creation time for downloads that MediaStore does not index. */
    val dateAddedSeconds: Long? = null,
)

/**
 * Dates for a downloaded file-backed row.
 *
 * Old metadata has no persisted creation time, so the file timestamp is the
 * only honest migration value. From then on the persisted value stays fixed,
 * while modified time continues to follow the file on disk.
 */
internal fun resolvedDownloadDates(
    persistedAddedSeconds: Long?,
    fileModifiedMillis: Long?,
): Pair<Long?, Long?> {
    val modifiedSeconds = fileModifiedMillis?.takeIf { it > 0 }?.div(1_000)
    return (persistedAddedSeconds ?: modifiedSeconds) to modifiedSeconds
}


/** Premium source labels used in BitChord's Downloads list. */
private fun StreamFormat.downloadBadge(): String? = when {
    isDolbyAtmos -> "DOLBY"
    codec.equals("flac", ignoreCase = true) || codec.equals("x-flac", ignoreCase = true) -> "FLAC"
    else -> null
}

/**
 * YouTube does not expose a named quality tier once the resolved file has been
 * saved, so retain the concrete codec/container choice and bitrate alongside
 * the download. This is display metadata only: the bytes are still the exact
 * WebM/Opus or MP4/AAC rendition returned by YouTube.
 */
internal fun youtubeDownloadBadge(extension: String, kbps: Int): String? {
    if (kbps <= 0) return null
    val codec = if (extension.equals("m4a", ignoreCase = true)) "AAC" else "OPUS"
    return "$codec · $kbps kbps"
}

/** Recover display metadata for private YouTube downloads made before it was persisted. */
private fun legacyYoutubeDownloadBadge(context: Context, uri: Uri): String? {
    if (!uri.lastPathSegment.orEmpty().substringBefore('?').endsWith(".webm", ignoreCase = true)) {
        return null
    }
    val bitsPerSecond = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
        } finally {
            retriever.release()
        }
    }.getOrNull() ?: return null
    val kbps = ((bitsPerSecond + 500L) / 1_000L).toInt()
    return youtubeDownloadBadge("webm", kbps)
}

/**
 * What a batch download was asked for as a whole.
 *
 * Built by whichever surface the tap came from — a release page's own download
 * button, a shelf card's menu — because that surface is the only thing that
 * knows the answer, and by the time the tracks reach the queue they are forty
 * unrelated rows. Null everywhere a single track is downloaded on its own,
 * which is the honest answer there: one song off an album is not the album.
 */
data class DownloadTarget(
    /**
     * What this release is filed under: its browse id where it has one, so the
     * same album downloaded twice is one entry rather than two.
     */
    val id: String,
    val title: String,
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    /** Playlists and albums are grouped alike but not billed alike. */
    val playlist: Boolean = false,
)

/**
 * A release the record says was downloaded whole, as it is written down.
 *
 * Only the tracks' ids, not the tracks: a [Song] is a wide row full of things
 * that go stale — like state, autoplay provenance, a resolved local path — and
 * a second copy of one per release is a second copy to keep in step. The songs
 * are looked up out of what the Downloads page already read off disk instead;
 * see [Downloads.collectionsAmong].
 */
@kotlinx.serialization.Serializable
data class SavedCollection(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    val playlist: Boolean = false,
    /** In the order the page listed them, which is the order to play them in. */
    val videoIds: List<String> = emptyList(),
)

/** A [SavedCollection] with its surviving tracks attached, ready to draw. */
data class DownloadedCollection(
    val id: String,
    val title: String,
    val subtitle: String,
    val thumbnailUrl: String?,
    val playlist: Boolean,
    val songs: List<Song>,
)

/**
 * The one write path for the download index.
 *
 * Two jobs, both learned the hard way.
 *
 * **Coalesce.** A batch download finishes dozens of tracks in a row, and each
 * completion used to encode the entire library and write it. Now a payload that
 * arrives while a write is in flight replaces the pending one instead of
 * queueing behind it, so a burst costs one write of the final state — which is
 * the only state anyone will ever read.
 *
 * **Never lose the last one.** [drain] reads the pending payload until there is
 * none, and re-checks for a late arrival before clearing [draining]. A payload
 * submitted in the window between that last read and the flag going down used to
 * be dropped, which on this index means a track the app had already told the
 * user it downloaded stops being one after a restart.
 *
 * Calls are serialised on [lock] and never block the caller; the sink runs on
 * [scope]. [Downloads] holds its own lock across *building* the payload, so the
 * bytes handed to [submit] are always a complete, current snapshot.
 */
internal class CoalescingWriter(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val sink: (Map<String, String>) -> Unit,
) {
    private val lock = Any()
    private var pending: Map<String, String>? = null
    private var draining = false

    fun submit(payload: Map<String, String>) {
        val start = synchronized(lock) {
            pending = payload
            if (draining) return
            draining = true
            true
        }
        if (start) scope.launch { drain() }
    }

    private suspend fun drain() {
        try {
            while (true) {
                val next = synchronized(lock) { pending.also { pending = null } } ?: break
                sink(next)
            }
        } finally {
            // Either a payload landed while the last one was being written — go
            // round again — or there is nothing left and the next [submit] starts
            // its own drain.
            val missed = synchronized(lock) {
                if (pending == null) draining = false
                pending != null
            }
            if (missed) scope.launch { drain() }
        }
    }
}
