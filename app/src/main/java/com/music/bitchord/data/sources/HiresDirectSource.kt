package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * BitChord Hi-Res Lossless: direct FLAC files from an open catalogue, and
 * nothing else.
 *
 * One backend, no accounts, no tokens, no converters, no unlock sheets. A
 * search row names a file; opening it HEADs the file, measures it, and
 * plays it when the bytes back the claim — or refuses it when they don't.
 * What the catalogue lacks, this source lacks too, and says so by returning
 * nothing rather than by stalling: a miss the resolver walks past in
 * milliseconds.
 */
class HiresDirectSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.HIRES

    override val displayName: String get() = config.label.ifBlank {
        SourceRegistry.HIRES_DISPLAY_NAME
    }

    override suspend fun health(): SourceHealth {
        // An empty catalogue still proves the host is there; only a failed
        // probe means it isn't. (The search call itself never throws — it
        // answers empty — so the probe goes underneath it, and the probe
        // answers false rather than throwing, so it is read, not caught.)
        val catalogueOk = try {
            HiResMonochromeBackend.probe().also {
                if (it) TrackLog.d(TAG, "  ✓ hires direct catalogue answers")
            }
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            false
        }
        if (!catalogueOk) return SourceHealth.Unreachable("no Hi-Res catalogue answered")
        // A file that answers the catalogue but refuses at playback is the
        // one failure this source can still produce, and the fetch path
        // records the server's stated reason — repeated here so it reads on
        // the Sources screen instead of only in a log.
        val refusal = com.music.bitchord.data.NerdStats
            .refusalFor(HiResMonochromeBackend.BASE_HOST)
            ?.let { " · last stream: $it" } ?: ""
        return SourceHealth.Ok("direct FLAC ready$refusal")
    }

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        val rows = try {
            HiResMonochromeBackend.searchTracks(q, limit.coerceIn(1, 50))
                .filter { HiResMonochromeBackend.isPlayableMatch(q, it.title) }
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            TrackLog.d(TAG, "  ✗ hires direct search failed: ${e.message}")
            emptyList()
        }.distinctBy { it.id }
        if (rows.isNotEmpty()) TrackLog.d(TAG, "  ✓ hires direct ${rows.size} rows")
        return rows.take(limit).map { it.toSong() }
    }

    private fun HiResMonochromeBackend.MonoTrack.toSong(): Song {
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
            title = title.ifBlank { "Unknown title" },
            artist = credits,
            albumName = album,
            thumbnailUrl = artwork?.takeIf { it.isNotBlank() },
            durationText = duration,
            sourceQuality = "LOSSLESS",
            isExplicit = explicit,
        )
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        // Direct files have no tiers: one FLAC rendition whatever was asked.
        // A capped or best-effort request still gets it — the only rendition
        // this source can serve beats refusing the track.
        val origin = HiResMonochromeBackend.originOf(trackId) ?: return null
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

    companion object {
        private const val TAG = "BitChord"

        /** Provenance label, shown in stats-for-nerds so a track's path is answerable on screen. */
        const val BACKEND_DIRECT = "Direct FLAC"
    }
}
