package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive

/**
 * Community Hi-Res FLAC through four backends under one source row.
 *
 * The deterministic backend is the listener's own Qobuz account: Qobuz's
 * catalogue API answers with nothing but public credentials, and its file
 * endpoint serves whatever tier that account holds — no converter, no
 * challenge, no waiting. Signed in, this is tried first and usually ends
 * the search for a stream right there.
 *
 * Without an account the order is direct, conversion, Tidal conversion:
 * the direct backend streams a verified FLAC file in about a second when
 * it holds the recording; the downloaders queue server-side conversions
 * that measure in minutes. It is deliberately not a separate source.
 *
 * The Qobuz-downloader protocol is the one the Spotube FLAC plugin speaks,
 * reimplemented against plain HTTP: that plugin needs a Hetu runtime and
 * Spotube's host APIs, neither of which exists here, but its half-dozen
 * calls reduce to JSON this app can ask for directly. Conversion is a
 * *job*, not a URL, so resolution is budgeted and cancellable: a slow
 * backend loses the resolver's race instead of holding it.
 */
class FlacDownloaderSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.HIRES

    override val displayName: String get() = config.label.ifBlank {
        SourceRegistry.HIRES_DISPLAY_NAME
    }

    /**
     * What a search row needs later at queue time, by the packed id handed
     * out in [search]. Parked on the API object — see
     * [FlacDownloaderApi.memoizeOrigin] for why an instance memo strands rows.
     */
    private fun memoize(origin: FlacDownloaderApi.Origin, packed: String) =
        FlacDownloaderApi.memoizeOrigin(packed, origin)

    override suspend fun health(): SourceHealth {
        // The account leg is independent of every catalogue: a signed-in
        // listener streams from Qobuz itself even when all three converters
        // and the direct host are down.
        val accountSuffix = when {
            AppSettings.qobuzToken.value.isNotBlank() ->
                " · Qobuz account (${AppSettings.qobuzTier.value.ifBlank { "signed in" }})"
            AppSettings.qobuzEmail.value.isNotBlank() ->
                " · Qobuz sign-in expired"
            else -> ""
        }
        val catalogueOk = try {
            // An empty catalogue still proves the backend is there; only a
            // failed call means it isn't. (The search call itself never
            // throws — it answers empty — so the probe goes underneath it.)
            FlacDownloaderApi.probe()
            TrackLog.d(TAG, "  ✓ hifiresolver catalogue answers")
            true
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            TrackLog.d(TAG, "  ✗ hifiresolver catalogue failed: ${e.message}")
            false
        }
        val directOk = HiResMonochromeBackend.probe()
        // A signed-in account streams from Qobuz itself, so it is healthy
        // even when every other backend is down — and checking the
        // challenge gate first would misreport it on a blocked network.
        if (AppSettings.qobuzToken.value.isNotBlank()) {
            return SourceHealth.Ok(
                "Qobuz account (${AppSettings.qobuzTier.value.ifBlank { "signed in" }})" +
                    if (!catalogueOk && !directOk) " · converters down" else "",
            )
        }
        if (!catalogueOk) {
            if (directOk) return SourceHealth.Ok("direct FLAC ready · Qobuz catalogue down$accountSuffix")
            return SourceHealth.Unreachable("no Hi-Res catalogue answered")
        }
        val directSuffix = if (directOk) " · direct FLAC ready" else ""
        // Second leg, stated plainly on the sources screen: a catalogue
        // that answers while conversions stay challenged streams nothing
        // from the downloader — but the direct backend still can. And when a
        // token exists yet conversions still fail, the last failure rides
        // along so "says unlocked, streams nothing" names its reason.
        val failureSuffix = FlacDownloaderApi.lastFailure?.let { " · last conversion: $it" } ?: ""
        return if (FlacDownloaderApi.prepareToken() != null) {
            SourceHealth.Ok("flacdownloader.com · Qobuz FLAC$directSuffix$failureSuffix$accountSuffix")
        } else if (AppSettings.fdlSp.value.isBlank()) {
            SourceHealth.Ok("tap to unlock Qobuz$directSuffix$accountSuffix")
        } else {
            SourceHealth.Ok("catalogue ok · conversions blocked on this network$directSuffix$failureSuffix$accountSuffix")
        }
    }

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        // ISRC precision first, exactly like the plugin: Deezer names the
        // recording, Qobuz is asked for that ISRC, and text search is the
        // fallback rather than the authority.
        //
        // The three catalogue reads fan out concurrently: run sequentially
        // they cost the sum of three round trips on every query of every
        // upgrade race, and the resolver asks two or three queries per
        // track. Only the ISRC re-search waits on Deezer, since it needs
        // the ISRCs first.
        val textHits: List<FlacDownloaderApi.QobuzTrack>
        val monoRaw: List<HiResMonochromeBackend.MonoTrack>
        val tidalRaw: List<FlacDownloaderApi.TidalTrack>
        val accountRaw: List<QobuzApi.QobuzTrack>
        val isrcs: List<String>
        coroutineScope {
            val deezer = async {
                try {
                    FlacDownloaderApi.deezerRows(q)
                        .filter { FlacDownloaderApi.titlesMatch(q, it.title) }
                } catch (e: Exception) {
                    coroutineContext.ensureActive()
                    emptyList()
                }
            }
            val text = async {
                try {
                    FlacDownloaderApi.searchQobuz(q, limit.coerceIn(1, 50))
                        .filter { FlacDownloaderApi.isPlayableMatch(q, it.title) }
                } catch (e: Exception) {
                    coroutineContext.ensureActive()
                    TrackLog.d(TAG, "  ✗ hifiresolver search failed: ${e.message}")
                    emptyList()
                }
            }
            val mono = async {
                try {
                    HiResMonochromeBackend.searchTracks(q, limit.coerceIn(1, 50))
                } catch (e: Exception) {
                    coroutineContext.ensureActive()
                    TrackLog.d(TAG, "  ✗ hires direct catalogue search failed: ${e.message}")
                    emptyList()
                }
            }
            // Qobuz's own catalogue, signed with public credentials — no
            // account, no challenge. Richer rows than the downloader's
            // (true maximum bit depth), and the same global track ids, so
            // the dedupe below folds the two together.
            val account = async {
                try {
                    QobuzApi.search(q, limit.coerceIn(1, 50))
                        .filter { it.id != 0L && FlacDownloaderApi.isPlayableMatch(q, it.title) }
                } catch (e: Exception) {
                    coroutineContext.ensureActive()
                    TrackLog.d(TAG, "  ✗ hires qobuz-direct search failed: ${e.message}")
                    emptyList()
                }
            }
            // The site's own Tidal catalogue, alongside Qobuz's. Same host and
            // same token, and broader than the direct host for the niche
            // recordings Qobuz's dead converter used to be the only route to.
            val tidal = async {
                try {
                    FlacDownloaderApi.searchTidal(q, limit.coerceIn(1, 50))
                        .filter { FlacDownloaderApi.isPlayableMatch(q, it.title) }
                } catch (e: Exception) {
                    coroutineContext.ensureActive()
                    TrackLog.d(TAG, "  ✗ hifiresolver tidal search failed: ${e.message}")
                    emptyList()
                }
            }
            val deezerRows = deezer.await()
            textHits = text.await()
            monoRaw = mono.await()
            tidalRaw = tidal.await()
            accountRaw = account.await()
            isrcs = deezerRows.mapNotNull { it.isrc?.takeIf(String::isNotBlank) }
                .distinct()
                .take(3)
        }
        val isrcHits = try {
            isrcs.flatMap { isrc -> FlacDownloaderApi.searchQobuz(isrc, 5) }
                .filter { FlacDownloaderApi.isPlayableMatch(q, it.title) }
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            emptyList()
        }
        val monoHits = HiResMonochromeBackend.rankByIsrc(monoRaw, isrcs.firstOrNull())
            .filter { FlacDownloaderApi.isPlayableMatch(q, it.title) }
        val flacRows = (isrcHits + textHits).distinctBy { it.id }
        val songs = flacRows.map { it.toSong() }.toMutableList()
        val seen = songs.mapTo(HashSet()) { catalogueKey(it.title, it.artist) }
        // Account rows lead when signed in: their stream is instant and
        // tier-exact, so trying a minutes-long conversion first would be
        // pure waste. Without a token they cannot stream yet, so they join
        // behind the convertible rows rather than spending attempts.
        val accountSongs = accountRaw.mapNotNull { it.toSongOrNull() }
        if (AppSettings.qobuzToken.value.isNotBlank()) {
            val lead = accountSongs.filter { seen.add(catalogueKey(it.title, it.artist)) }
            songs.addAll(0, lead)
        } else {
            for (song in accountSongs) {
                if (!seen.add(catalogueKey(song.title, song.artist))) continue
                songs += song
            }
        }
        for (track in monoHits) {
            val title = track.title.ifBlank { "Unknown title" }
            val artist = track.credits
            if (!seen.add(catalogueKey(title, artist))) continue
            songs += track.toSong(title, artist)
        }
        // Tidal rows join last: where the other catalogues hold the recording
        // they are the better answer, since a direct file arrives in about a
        // second and a Tidal conversion does not. They are here for what they
        // alone hold.
        for (track in tidalRaw) {
            val title = track.title.ifBlank { "Unknown title" }
            val artist = track.artist.ifBlank { "Unknown artist" }
            if (!seen.add(catalogueKey(title, artist))) continue
            songs += track.toSong()
        }
        val merged = songs.take(limit)
        if (merged.isNotEmpty()) TrackLog.d(TAG, "  ✓ hifiresolver ${merged.size} rows")
        return merged
    }

    private fun FlacDownloaderApi.QobuzTrack.toSong(): Song {
        val packed = id.toString()
        val qobuzUrl = url?.takeIf { it.isNotBlank() } ?: "https://open.qobuz.com/track/$id"
        val durationSec = durationMs?.takeIf { it > 0 }?.div(1000)?.toInt()
        memoize(
            FlacDownloaderApi.Origin(
                qobuzUrl = qobuzUrl,
                title = title,
                artist = artist,
                durationSec = durationSec,
                album = album,
                cover = cover.orEmpty(),
                isrc = isrc.orEmpty(),
                explicit = explicit,
                albumArtist = albumArtist,
                date = date,
                trackNumber = trackNumber,
                trackTotal = trackTotal,
                discNumber = discNumber,
                discTotal = discTotal,
                genre = genre,
                label = label,
                copyright = copyright,
                composer = composer,
                barcode = barcode,
                version = version,
                work = work,
                bitDepth = bitDepth,
                samplingRate = samplingRate,
            ),
            packed,
        )
        val duration = durationSec?.let { s -> "%d:%02d".format(Locale.ROOT, s / 60, s % 60) }
        return Song(
            videoId = SourceRegistry.trackKey(config.id, packed),
            title = title.ifBlank { "Unknown title" },
            artist = artist.ifBlank { "Unknown artist" },
            albumName = album.takeIf { it.isNotBlank() },
            thumbnailUrl = cover?.takeIf { it.isNotBlank() },
            durationText = duration,
            sourceQuality = if (hires || (bitDepth ?: 0.0) >= 24.0) "LOSSLESS" else null,
            isExplicit = explicit,
        )
    }

    /**
     * A direct-Qobuz row. Null only for id 0, which carries no track. Rows
     * stream through the signed-in account; without one they miss fast at
     * stream time (blank token, no network), while still carrying true
     * maximum-bit-depth metadata for badges and ranking.
     */
    private fun QobuzApi.QobuzTrack.toSongOrNull(): Song? {
        if (id == 0L) return null
        val packed = QobuzApi.packId(id)
        QobuzApi.memoizeOrigin(packed, QobuzApi.Origin(id))
        val title = this.title.ifBlank { "Unknown title" }
        val artist = artistName.ifBlank { "Unknown artist" }
        val durationSec = durationSec.takeIf { it > 0 }
        val duration = durationSec?.let { s -> "%d:%02d".format(Locale.ROOT, s / 60, s % 60) }
        return Song(
            videoId = SourceRegistry.trackKey(config.id, packed),
            title = title,
            artist = artist,
            albumName = album.title.takeIf { it.isNotBlank() },
            thumbnailUrl = album.artwork,
            durationText = duration,
            sourceQuality = if (hires || maximumBitDepth >= 24) "LOSSLESS" else null,
            isExplicit = isExplicit,
        )
    }

    private fun FlacDownloaderApi.TidalTrack.toSong(): Song {
        val packed = FlacDownloaderApi.packTidalId(id)
        val tidalUrl = url?.takeIf { it.isNotBlank() } ?: "https://tidal.com/browse/track/$id"
        val durationSec = durationMs?.takeIf { it > 0 }?.div(1000)?.toInt()
        FlacDownloaderApi.memoizeTidalOrigin(
            packed,
            FlacDownloaderApi.TidalOrigin(id, tidalUrl, title, artist),
        )
        val duration = durationSec?.let { s -> "%d:%02d".format(Locale.ROOT, s / 60, s % 60) }
        return Song(
            videoId = SourceRegistry.trackKey(config.id, packed),
            title = title.ifBlank { "Unknown title" },
            artist = artist.ifBlank { "Unknown artist" },
            albumName = album.takeIf { it.isNotBlank() },
            thumbnailUrl = cover?.takeIf { it.isNotBlank() },
            durationText = duration,
            sourceQuality = if (hires || lossless) "LOSSLESS" else null,
            isExplicit = explicit,
        )
    }

    private fun catalogueKey(title: String, artist: String): String =
        "${FlacDownloaderApi.normalizeTitle(title).lowercase(Locale.ROOT)}|" +
            HiResMonochromeBackend.simplify(artist)

    private fun HiResMonochromeBackend.MonoTrack.toSong(title: String, artist: String): Song {
        val packed = HiResMonochromeBackend.packId(id)
        val durationSec = HiResMonochromeBackend.normalizeDurationMs(durationMs)
            ?.div(1000)?.toInt()
        HiResMonochromeBackend.memoizeOrigin(
            packed,
            HiResMonochromeBackend.Origin(id, HiResMonochromeBackend.normalizeDurationMs(durationMs)),
        )
        val duration = durationSec?.let { s -> "%d:%02d".format(Locale.ROOT, s / 60, s % 60) }
        return Song(
            videoId = SourceRegistry.trackKey(config.id, packed),
            title = title,
            artist = artist,
            albumName = album,
            thumbnailUrl = artwork?.takeIf { it.isNotBlank() },
            durationText = duration,
            sourceQuality = "LOSSLESS",
            isExplicit = explicit,
        )
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        // The listener's own Qobuz account first: deterministic, instant,
        // tier-exact, no challenge and no minutes-long conversion. A blank
        // token misses here in microseconds — no network spent.
        QobuzApi.originOf(trackId)?.let { origin ->
            streamAccount(origin, request)?.let { return it }
        }
        HiResMonochromeBackend.originOf(trackId)?.let { return streamDirect(it) }
        // A Tidal row: direct first (this host is not the direct host, so it
        // will miss), then the site's Tidal queue, which is the route Qobuz's
        // dead converter used to serve for these recordings.
        FlacDownloaderApi.tidalOriginOf(trackId)?.let { origin ->
            val file = try {
                FlacDownloaderApi.resolveTidalStream(origin)
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                TrackLog.d(TAG, "  ✗ hifiresolver tidal stream failed: ${e.message}")
                null
            }
            if (file != null) {
                TrackLog.d(TAG, "  ✓ hifiresolver tidal ${file.url.take(96)}")
                return SourceStream(
                    url = file.url,
                    format = com.music.bitchord.data.sources.StreamFormat(
                        codec = "flac",
                        kbps = file.kbps,
                        sampleRateHz = file.sampleRateHz,
                        bitDepth = file.bitDepth,
                    ),
                    durationSec = null,
                    backend = BACKEND_TIDAL,
                )
            }
            TrackLog.d(
                TAG,
                "  ✗ no Tidal conversion for '${origin.title}' " +
                    "(${FlacDownloaderApi.lastFailure ?: "no reason recorded"})",
            )
            return null
        }
        val origin = FlacDownloaderApi.originOf(trackId) ?: run {
            // A row whose metadata never made it into the memo cannot be
            // queued: the queue body *is* that metadata. Returning null here
            // used to be silent, which made it look identical to a conversion
            // that was refused — two faults with different fixes, one line of
            // silence between them.
            TrackLog.w(
                TAG,
                "no queued metadata for '$trackId' (memo missed; the row cannot be converted)",
            )
            return null
        }
        // Direct first, conversion second. A verified file answers a HEAD in
        // about a second; a conversion is server-side work on a free service
        // and measures in minutes when it answers at all. Trying conversion
        // first held every upgrade race for the whole poll budget while the
        // instant copy sat one lookup away — which is what "takes 20 seconds
        // to load FLAC" felt like from the listener's side.
        monochromeFallback(origin.title, origin.artist, origin.durationSec)?.let { return it }
        val formatIds = when (request) {
            // Best-first; a lower FLAC that answers is still lossless.
            is StreamRequest.Lossless -> FlacDownloaderApi.LOSSLESS_FORMATS
            is StreamRequest.Capped -> if (request.maxKbps >= 1000) {
                FlacDownloaderApi.LOSSLESS_FORMATS + FlacDownloaderApi.LOSSY_FORMATS
            } else {
                FlacDownloaderApi.LOSSY_FORMATS
            }
            // The backend's best lossy rendition.
            is StreamRequest.Best -> FlacDownloaderApi.LOSSY_FORMATS
        }
        val file = try {
            FlacDownloaderApi.resolveStream(origin, formatIds)
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            TrackLog.d(TAG, "  ✗ hifiresolver stream failed: ${e.message}")
            null
        } ?: run {
            // resolveStream covers no-token, queue-refused, job-errored and
            // budget-spent, and names the ones it can into lastFailure. Saying
            // so here ties that reason to this track: without it the caller
            // sees a null, the listener hears the YouTube copy, and nothing
            // connects the two.
            TrackLog.d(
                TAG,
                "  ✗ no Qobuz conversion for '${origin.title}' " +
                    "(${FlacDownloaderApi.lastFailure ?: "no reason recorded"})",
            )
            return null
        }
        TrackLog.d(TAG, "  ✓ hifiresolver ${file.formatId} ${file.url.take(96)}")
        val mp3 = file.formatId == FlacDownloaderApi.F_MP3_320
        return SourceStream(
            url = file.url,
            format = com.music.bitchord.data.sources.StreamFormat(
                codec = if (mp3) "mp3" else "flac",
                kbps = file.kbps,
                sampleRateHz = file.sampleRateHz,
                bitDepth = file.bitDepth,
            ),
            belowRequest = mp3 && request is StreamRequest.Lossless,
            durationSec = origin.durationSec,
            backend = BACKEND_CONVERSION,
        )
    }

    /**
     * A direct-Qobuz file behind the listener's own account. The token is
     * never cleared here: a null covers both "track not entitled at this
     * tier" and "sign-in expired", and logging the listener out because one
     * track isn't theirs would be the wrong verdict for the common case.
     * An expired token surfaces in health as "sign-in expired" once its
     * stored email outlives it — re-signing mints a new one.
     */
    private suspend fun streamAccount(
        origin: QobuzApi.Origin,
        request: StreamRequest,
    ): SourceStream? {
        val token = AppSettings.qobuzToken.value.takeIf { it.isNotBlank() } ?: return null
        val formatIds = when (request) {
            is StreamRequest.Lossless -> QobuzApi.LOSSLESS_FORMATS
            is StreamRequest.Capped -> if (request.maxKbps >= 1000) {
                QobuzApi.LOSSLESS_FORMATS + listOf(QobuzApi.F_MP3_320)
            } else {
                listOf(QobuzApi.F_MP3_320)
            }
            is StreamRequest.Best -> listOf(QobuzApi.F_MP3_320)
        }
        val file = try {
            QobuzApi.resolveFile(origin.id, token, formatIds)
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            TrackLog.d(TAG, "  ✗ hires qobuz account stream failed: ${e.message}")
            null
        } ?: return null
        TrackLog.d(TAG, "  ✓ hires qobuz account ${file.formatId} ${file.url.take(96)}")
        val mp3 = file.formatId == QobuzApi.F_MP3_320
        return SourceStream(
            url = file.url,
            format = com.music.bitchord.data.sources.StreamFormat(
                codec = if (mp3) "mp3" else "flac",
                kbps = file.kbps,
                sampleRateHz = file.sampleRateHz,
                bitDepth = file.bitDepth,
            ),
            belowRequest = mp3 && request is StreamRequest.Lossless,
            durationSec = null,
            backend = BACKEND_ACCOUNT,
        )
    }

    private suspend fun streamDirect(origin: HiResMonochromeBackend.Origin): SourceStream? {
        val file = try {
            HiResMonochromeBackend.resolveFile(origin.id, origin.durationMs)
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            TrackLog.d(TAG, "  ✗ hires direct stream failed: ${e.message}")
            null
        } ?: return null
        TrackLog.d(TAG, "  ✓ hires direct ${file.kbps}kbps ${file.url.take(96)}")
        return SourceStream(
            url = file.url,
            format = com.music.bitchord.data.sources.StreamFormat(
                codec = "flac",
                kbps = file.kbps,
                sampleRateHz = file.sampleRateHz,
                bitDepth = file.bitDepth,
            ),
            durationSec = HiResMonochromeBackend.normalizeDurationMs(origin.durationMs)
                ?.div(1000)?.toInt(),
            backend = BACKEND_DIRECT,
        )
    }

    /**
     * The direct catalogue for a downloader row, tried before any conversion
     * is queued. Title, artist credit and runtime all have to agree, so a
     * cover or a different cut cannot stand in for the row.
     */
    private suspend fun monochromeFallback(
        title: String,
        artist: String,
        durationSec: Int?,
    ): SourceStream? {
        val query = listOf(title, artist)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .takeIf { it.isNotEmpty() }
            ?: return null
        val candidates = try {
            HiResMonochromeBackend.searchTracks(query, 10)
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            emptyList()
        }
        val hit = HiResMonochromeBackend.plausible(candidates, listOf(artist), null)
            .firstOrNull {
                FlacDownloaderApi.isPlayableMatch(title, it.title) &&
                    HiResMonochromeBackend.durationsMatch(durationSec, it.durationMs)
            } ?: return null
        TrackLog.d(TAG, "  ✓ hifiresolver fell back to direct catalogue")
        return streamDirect(
            HiResMonochromeBackend.Origin(
                hit.id,
                HiResMonochromeBackend.normalizeDurationMs(hit.durationMs),
            ),
        )
    }

    companion object {
        private const val TAG = "BitChord"

        /**
         * Provenance labels for the two backends behind this one source row,
         * shown in stats-for-nerds so a track's path is answerable on screen.
         */
        const val BACKEND_DIRECT = "Direct FLAC"
        const val BACKEND_CONVERSION = "Qobuz conversion"
        const val BACKEND_TIDAL = "Tidal conversion"
        const val BACKEND_ACCOUNT = "Qobuz account"
    }
}
