package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.octave.OctaveApi
import java.util.Locale

private const val TAG = "BitChord"

/**
 * Octave (octavestreaming.com) with the listener's own account key: free
 * lossless FLAC, no self-hosting, no manifest.
 *
 * The key rides as a Bearer token to mint short-lived playback tokens; the
 * audio bytes stream straight from Octave's CDN. Seeded off — without a key
 * there is nothing to answer with, so the toggle plus a pasted key is the
 * whole setup. Health tells the three truths apart: live, key rejected, and
 * the sour one — a valid key with no streaming entitlement.
 */
class OctaveSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.OCTAVE
    override val displayName: String get() = config.label.ifBlank { SourceKind.OCTAVE.label }

    private val base: String get() = OctaveApi.DEFAULT_BASE
    private fun key(): String = config.settings[OctaveApi.ACCOUNT_KEY].orEmpty()

    override suspend fun health(): SourceHealth {
        val key = key()
        if (key.isBlank()) return SourceHealth.Rejected("An Octave account key is required")
        if (!OctaveApi.isKeyShape(key)) {
            return SourceHealth.Rejected("That does not look like an Octave key (octv_… + 48 hex)")
        }
        return try {
            when (val verdict = OctaveApi.verify(base, key)) {
                is OctaveApi.KeyVerdict.Streaming ->
                    SourceHealth.Ok("FLAC")
                is OctaveApi.KeyVerdict.NoStreaming ->
                    SourceHealth.Rejected(
                        verdict.reason ?: "Key is valid, but this account has no streaming access",
                    )
                is OctaveApi.KeyVerdict.Invalid ->
                    SourceHealth.Rejected(verdict.reason)
                OctaveApi.KeyVerdict.Unreachable ->
                    SourceHealth.Unreachable("No answer")
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
        val key = key().ifBlank { return emptyList() }
        val rows = try {
            OctaveApi.search(base, key, query, limit)
        } catch (e: Exception) {
            TrackLog.d(TAG, "  ✗ octave search failed: ${e.message}")
            return emptyList()
        }
        if (rows.isEmpty()) return emptyList()
        TrackLog.d(TAG, "  ✓ octave ${rows.size} rows")
        return rows.take(limit).map { it.toSong() }
    }

    private fun OctaveApi.OctaveTrack.toSong(): Song {
        OctaveApi.memoizeHint(id, OctaveApi.Hint(title, artist, durationSec, explicit))
        val duration = durationSec?.let { s -> "%d:%02d".format(Locale.ROOT, s / 60, s % 60) }
        return Song(
            videoId = SourceRegistry.trackKey(config.id, id),
            title = title,
            artist = artist,
            albumName = album,
            thumbnailUrl = cover,
            durationText = duration,
            sourceQuality = "LOSSLESS",
            isExplicit = explicit.takeIf { it },
        )
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val key = key().ifBlank { return null }
        val tier = when (request) {
            is StreamRequest.Lossless -> OctaveApi.TIER_LOSSLESS
            is StreamRequest.Capped -> if (request.maxKbps >= 320) OctaveApi.TIER_320 else OctaveApi.TIER_128
            is StreamRequest.Best -> OctaveApi.TIER_320
        }
        // The row behind this id is looked up for its hint fields; a search
        // row that never carried them still streams, just less precisely.
        val token = try {
            OctaveApi.ensureToken(base, key)
        } catch (e: Exception) {
            TrackLog.d(TAG, "  ✗ octave token failed: ${e.message}")
            return null
        }
        // Title/artist/duration ride the URL the way their player sends them;
        // the id alone resolves, the hint fields disambiguate.
        val hint = OctaveApi.hintOf(trackId)
        val url = OctaveApi.audioUrl(
            base, tier, trackId,
            hint?.title.orEmpty(), hint?.artist.orEmpty(),
            hint?.durationSec, hint?.explicit == true, token,
        )
        TrackLog.d(TAG, "  ✓ octave $tier ${url.take(96)}")
        return SourceStream(
            url = url,
            format = com.music.bitchord.data.sources.StreamFormat(
                codec = if (tier == OctaveApi.TIER_LOSSLESS) "flac" else "mp3",
            ),
            belowRequest = request is StreamRequest.Lossless && tier != OctaveApi.TIER_LOSSLESS,
        )
    }
}
