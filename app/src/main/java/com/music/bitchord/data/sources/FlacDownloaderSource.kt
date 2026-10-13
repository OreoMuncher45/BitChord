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
 * Community Hi-Res FLAC through two backends under one source row.
 *
 * The primary backend is the downloader: Qobuz catalogue search, Deezer ISRC
 * precision matching, and queued conversions up to 24-bit/192kHz. No
 * account, no key — the backend holds whatever it holds.
 *
 * The direct backend is tried first: when it holds the recording it
 * streams a verified FLAC file in about a second, with quality inferred
 * from measured size rather than catalogue claims. Conversions run only
 * when direct has no row, since they are server-side work that measures in
 * minutes. It is deliberately not a separate source.
 *
 * The protocol is the one the Spotube FLAC plugin speaks, reimplemented
 * against plain HTTP: that plugin needs a Hetu runtime and Spotube's host
 * APIs, neither of which exists here, but its half-dozen calls reduce to
 * JSON this app can ask for directly. Conversion is a *job*, not a URL, so
 * resolution is budgeted and cancellable: a slow backend loses the
 * resolver's race instead of holding it.
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
        if (!catalogueOk) {
            if (directOk) return SourceHealth.Ok("direct FLAC ready · Qobuz catalogue down")
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
            SourceHealth.Ok("flacdownloader.com · Qobuz FLAC$directSuffix$failureSuffix")
        } else if (AppSettings.fdlSp.value.isBlank()) {
            SourceHealth.Ok("tap to unlock Qobuz$directSuffix")
        } else {
            SourceHealth.Ok("catalogue ok · conversions blocked on this network$directSuffix$failureSuffix")
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
            val deezerRows = deezer.await()
            textHits = text.await()
            monoRaw = mono.await()
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
        for (track in monoHits) {
            val title = track.title.ifBlank { "Unknown title" }
            val artist = track.credits
            if (!seen.add(catalogueKey(title, artist))) continue
            songs += track.toSong(title, artist)
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
        HiResMonochromeBackend.originOf(trackId)?.let { return streamDirect(it) }
        val origin = FlacDownloaderApi.originOf(trackId) ?: return null
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
        } ?: return null
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
    }
}
