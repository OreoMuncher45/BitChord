package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.tidal.TidalApi
import com.music.bitchord.data.tidal.TidalInstances
import java.util.Locale

private const val TAG = "BitChord"

/**
 * Tidal Hi-Fi through compatible instances: genuine lossless FLAC streamed
 * straight from the CDN side.
 *
 * Two protocols, live standard first: the tracks protocol
 * (`/search/tracks` + direct `/track/{id}` bytes) is what Monochrome-class
 * instances speak today, and hifi-api v2 (`/search/?s=` + base64 manifests)
 * stays as fallback for older ones. Track ids pack their protocol, and the
 * minting endpoint is memoized, so stream() never guesses.
 *
 * One config, many instances: [TidalInstances] keeps the public list
 * healthy and each call walks the primary URL first, then the live public
 * ones — a dead instance is a skipped candidate, not an error the listener
 * sees. Matching a YouTube track to a catalogue row stays with whoever
 * called [search], exactly like every other catalogue source.
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
            if (TidalApi.isLive(base)) SourceHealth.Ok("FLAC") else {
                SourceHealth.Rejected("That server is not a Tidal Hi-Fi API")
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
            // Tracks protocol first; a shape miss falls through to hifi.
            val tracks = try {
                TidalApi.searchTracks(endpoint, query, limit)
            } catch (e: Exception) {
                TrackLog.d(TAG, "  ✗ tidal tracks search via $endpoint failed: ${e.message}")
                null
            }
            if (tracks != null) {
                if (tracks.isEmpty()) continue
                TrackLog.d(TAG, "  ✓ tidal ${tracks.size} rows via $endpoint (tracks)")
                return tracks.take(limit).map { it.toSong(endpoint) }
            }
            val hifi = try {
                TidalApi.search(endpoint, query, limit)
            } catch (e: Exception) {
                TrackLog.d(TAG, "  ✗ tidal hifi search via $endpoint failed: ${e.message}")
                continue
            }
            if (hifi.isEmpty()) continue
            TrackLog.d(TAG, "  ✓ tidal ${hifi.size} rows via $endpoint (hifi)")
            return hifi.take(limit).map { it.toSong(endpoint) }
        }
        return emptyList()
    }

    private fun TidalApi.TidalTrack.toSong(endpoint: String): Song {
        val packed = TidalApi.packId(protocol, id)
        TidalApi.memoizeOrigin(packed, endpoint)
        val duration = durationSec?.let { s -> "%d:%02d".format(Locale.ROOT, s / 60, s % 60) }
        return Song(
            videoId = SourceRegistry.trackKey(config.id, packed),
            title = title,
            artist = artist,
            albumName = album,
            thumbnailUrl = artDirectUrl ?: coverSlug?.let { TidalApi.coverUrl(it) },
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
        val (protocol, id) = TidalApi.unpackId(trackId)
        if (protocol == TidalApi.Protocol.TRACKS) {
            // The path IS the bytes — no request, nothing to fail over.
            val base = TidalApi.originOf(trackId)
                ?: TidalInstances.endpoints(config.baseUrl).firstOrNull()
                ?: return null
            TrackLog.d(TAG, "  ✓ tidal direct ${TidalApi.tracksStreamUrl(base, id).take(96)}")
            return SourceStream(
                url = TidalApi.tracksStreamUrl(base, id),
                format = com.music.bitchord.data.sources.StreamFormat(codec = "flac"),
            )
        }
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
                TidalApi.streamUrl(endpoint, id, tiers)
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
