package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.tidal.TidalApi
import com.music.bitchord.data.tidal.TidalInstances
import java.util.Locale

private const val TAG = "BitChord"

/**
 * Tidal Hi-Fi through a hifi-api-compatible instance: genuine lossless FLAC
 * (up to 24-bit/192kHz) streamed straight from Tidal's CDN.
 *
 * One config, many instances: [TidalInstances] keeps the public list healthy
 * and this source walks the primary URL first, then the live public ones —
 * so a dead instance is a skipped candidate, not an error the listener sees.
 * Search rows carry the catalogue's own duration and cover, and matching a
 * YouTube track to a Tidal row stays with whoever called [search], exactly
 * like every other catalogue source.
 */
class TidalSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.TIDAL
    override val displayName: String get() = config.label.ifBlank {
        config.baseUrl.substringAfter("://").substringBefore('/').ifBlank { SourceKind.TIDAL.label }
    }

    override suspend fun health(): SourceHealth {
        val base = TidalApi.normalize(config.baseUrl)
            ?: return SourceHealth.Rejected("That is not a usable instance address")
        return try {
            val version = TidalApi.probe(base)
            if (version == null) {
                SourceHealth.Rejected("That server is not a Tidal Hi-Fi API")
            } else {
                SourceHealth.Ok("v$version")
            }
        } catch (e: Exception) {
            SourceHealth.Unreachable(e.message ?: "No answer")
        }
    }

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        for (endpoint in TidalInstances.endpoints(config.baseUrl)) {
            val rows = try {
                TidalApi.search(endpoint, query, limit)
            } catch (e: Exception) {
                TrackLog.d(TAG, "  ✗ tidal search via $endpoint failed: ${e.message}")
                continue
            }
            if (rows.isEmpty()) continue
            TrackLog.d(TAG, "  ✓ tidal ${rows.size} rows via $endpoint")
            return rows.take(limit).map { it.toSong() }
        }
        return emptyList()
    }

    private fun TidalApi.TidalTrack.toSong(): Song {
        val duration = durationSec?.let { s -> "%d:%02d".format(Locale.ROOT, s / 60, s % 60) }
        return Song(
            videoId = SourceRegistry.trackKey(config.id, id),
            title = title,
            artist = artist,
            albumName = album,
            thumbnailUrl = coverSlug?.let { TidalApi.coverUrl(it) },
            durationText = duration,
            sourceQuality = when (audioQuality) {
                TidalApi.HI_RES, TidalApi.LOSSLESS -> "LOSSLESS"
                TidalApi.HIGH -> "HIGH"
                else -> null
            },
            isExplicit = explicit,
        )
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val tiers = when (request) {
            // Ask best-first; a lower tier that answers is still lossless and
            // is marked as below what was asked rather than refused.
            is StreamRequest.Lossless -> TidalApi.LOSSLESS_TIERS
            is StreamRequest.Capped -> if (request.maxKbps >= 1000) {
                listOf(TidalApi.LOSSLESS, TidalApi.HIGH)
            } else {
                listOf(TidalApi.HIGH, TidalApi.LOW)
            }
            // The source's best lossy rendition, as documented.
            is StreamRequest.Best -> listOf(TidalApi.HIGH)
        }
        for (endpoint in TidalInstances.endpoints(config.baseUrl)) {
            val answer = try {
                TidalApi.streamUrl(endpoint, trackId, tiers)
            } catch (e: Exception) {
                TrackLog.d(TAG, "  ✗ tidal stream via $endpoint failed: ${e.message}")
                continue
            } ?: continue
            val codec = answer.mimeType?.substringAfter('/')?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotBlank() } ?: "flac"
            TrackLog.d(TAG, "  ✓ tidal ${answer.quality.ifBlank { "FLAC" }} ${answer.url.take(96)}")
            return SourceStream(
                url = answer.url,
                format = com.music.bitchord.data.sources.StreamFormat(codec = codec),
                belowRequest = request is StreamRequest.Lossless &&
                    answer.quality.isNotBlank() &&
                    answer.quality != TidalApi.HI_RES,
            )
        }
        return null
    }
}
