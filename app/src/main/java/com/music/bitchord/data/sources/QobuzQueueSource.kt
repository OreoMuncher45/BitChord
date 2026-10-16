package com.music.bitchord.data.sources

import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.qobuz.QobuzQueueApi
import java.util.Locale

private const val TAG = "BitChord"

/**
 * Qobuz FLAC through flacdownloader.com's conversion queue — a separate
 * addon, not part of Hi-Res: direct files and queued conversions fail in
 * completely different ways and deserve separate rows, separate toggles
 * and separate blame.
 *
 * Flow per track: free catalogue search (no auth), conversion queue behind
 * a session (`sp`, resumed natively), one in-app-browser unlock minting
 * that session via the site's own Turnstile widget. Format ids are the
 * site's: 27 = 24/192, 7 = 24/96, 6 = 16/44.1 FLAC, 5 = MP3 320 — the MP3
 * only ever as the last resort their own UI offers when FLAC will not
 * prepare.
 *
 * Seeded off: without an unlock it answers searches but can stream
 * nothing, so the toggle plus one tap is the whole setup. Slow by design —
 * a conversion is a job, not a URL — so this kind never prefetches and a
 * miss here is a timeout, not a fault.
 */
class QobuzQueueSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.QOBUZ
    override val displayName: String get() = config.label.ifBlank { SourceKind.QOBUZ.label }

    /**
     * Budget for one stream() round, shared across format attempts.
     * Mutable for tests; production value keeps a slow conversion from
     * stalling the upgrade race past half a minute.
     */
    internal var roundBudgetMs = 30_000L

    companion object {
        const val SP_KEY = "qobuz_sp"
        const val TOKEN_KEY = "qobuz_token"

        /** Provenance label, shown in stats-for-nerds. */
        const val BACKEND_QUEUE = "Qobuz conversion"
    }

    private fun sp(): String = config.settings[SP_KEY].orEmpty()
    private fun token(): String = config.settings[TOKEN_KEY].orEmpty()

    /**
     * Writes a rotated session back to the config — only when something
     * actually changed. An unconditional write republishes the config list
     * on every stream, and the Sources screen re-probes on every publish,
     * which is how one playing track kept every addon manifest churning.
     */
    private fun persistSession(sp: String?, token: String?) {
        val next = config.settings.toMutableMap()
        var changed = false
        if (!sp.isNullOrBlank() && next[SP_KEY] != sp) {
            next[SP_KEY] = sp
            changed = true
        }
        if (!token.isNullOrBlank() && next[TOKEN_KEY] != token) {
            next[TOKEN_KEY] = token
            changed = true
        }
        if (changed) runCatching { SourceRegistry.update(config.copy(settings = next)) }
    }

    override suspend fun health(): SourceHealth {
        if (sp().isBlank()) return SourceHealth.Rejected("Tap to unlock — one browser check, then it stays")
        // Read-only: persisting here republished the config on every probe,
        // and the probe reruns on every publish. Rotation is recorded on
        // successful streams instead, where it is earned.
        return try {
            when (val verdict = QobuzQueueApi.prepare(sp())) {
                is QobuzQueueApi.TokenOutcome.Ready ->
                    SourceHealth.Ok("Unlocked")
                QobuzQueueApi.TokenOutcome.Challenge ->
                    SourceHealth.Rejected("Unlock expired — tap to renew")
                is QobuzQueueApi.TokenOutcome.Failed ->
                    SourceHealth.Unreachable(verdict.reason)
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
        // Free and unchallenged — works unlocked or not. Streaming still
        // needs the session; rows without one simply miss at play time.
        val rows = try {
            QobuzQueueApi.searchTracks(query, limit)
        } catch (e: Exception) {
            TrackLog.d(TAG, "  ✗ qobuz search failed: ${e.message}")
            return emptyList()
        }
        if (rows.isEmpty()) return emptyList()
        TrackLog.d(TAG, "  ✓ qobuz ${rows.size} rows")
        return rows.take(limit).map { it.toSong() }
    }

    private fun QobuzQueueApi.QobuzTrack.toSong(): Song {
        val packed = QobuzQueueApi.packId(id)
        QobuzQueueApi.memoizeOrigin(
            packed,
            QobuzQueueApi.Origin(url, title, artist, durationMs),
        )
        val duration = durationMs?.let { ms ->
            val s = (ms / 1000).toInt()
            "%d:%02d".format(Locale.ROOT, s / 60, s % 60)
        }
        return Song(
            videoId = SourceRegistry.trackKey(config.id, packed),
            title = title,
            artist = artist,
            albumName = album,
            thumbnailUrl = cover,
            durationText = duration,
            sourceQuality = "LOSSLESS",
            isExplicit = null,
        )
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val packed = SourceRegistry.parseTrackKey(trackId)?.second ?: trackId
        val qobuzId = QobuzQueueApi.unpackId(packed) ?: return null
        val origin = QobuzQueueApi.originOf(packed) ?: return null
        val track = QobuzQueueApi.QobuzTrack(
            id = qobuzId,
            title = origin.title,
            artist = origin.artist,
            album = "",
            cover = null,
            durationMs = origin.durationMs,
            isrc = "",
            url = origin.url,
            bitDepth = null,
            samplingRateHz = null,
            hires = false,
        )
        val formats = when (request) {
            is StreamRequest.Lossless ->
                QobuzQueueApi.LOSSLESS_FORMATS + QobuzQueueApi.FORMAT_MP3_320
            is StreamRequest.Capped ->
                if (request.maxKbps >= 320) listOf(QobuzQueueApi.FORMAT_MP3_320) else return null
            is StreamRequest.Best -> listOf(QobuzQueueApi.FORMAT_MP3_320)
        }
        var token = token().ifBlank { null }
        // One round budget shared across formats: a conversion is a job
        // that routinely outlives any single race, so a round that waits
        // for it holds the whole upgrade hostage. Time out fast, memo the
        // job, and let the next round re-poll the same job to completion.
        val deadline = System.currentTimeMillis() + roundBudgetMs
        for (formatId in formats) {
            if (System.currentTimeMillis() >= deadline) break
            var attempt = QobuzQueueApi.jobOf(packed, formatId)?.let {
                QobuzQueueApi.QueueOutcome.Job(it)
            } ?: tryQueue(trackId, track, formatId, token)
            if (attempt is QobuzQueueApi.QueueOutcome.TokenDead || (attempt == null && token != null)) {
                // One resume and one retry: a rotated session mints a fresh
                // token without the browser. Anything beyond that is the
                // unlock sheet's business, not a loop's.
                token = resumeToken() ?: run {
                    noteUnlock()
                    return null
                }
                attempt = tryQueue(trackId, track, formatId, token)
            }
            when (attempt) {
                is QobuzQueueApi.QueueOutcome.Job -> {
                    QobuzQueueApi.memoizeJob(packed, formatId, attempt.jobId)
                    val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
                    when (val job = QobuzQueueApi.pollJob(attempt.jobId, formatId, budgetMs = remaining)) {
                        is QobuzQueueApi.JobOutcome.Done -> {
                            QobuzQueueApi.forgetJob(packed, formatId)
                            val file = job.file
                            TrackLog.d(TAG, "  ✓ qobuz ${file.container} ${file.url.take(96)}", about = trackId)
                            return SourceStream(
                                url = file.url,
                                format = StreamFormat(
                                    codec = file.container,
                                    kbps = file.bitrate,
                                    sampleRateHz = file.sampleRateHz,
                                    bitDepth = file.bitDepth,
                                ),
                                belowRequest = request is StreamRequest.Lossless &&
                                    formatId == QobuzQueueApi.FORMAT_MP3_320,
                                durationSec = origin.durationMs?.div(1000)?.toInt(),
                                backend = BACKEND_QUEUE,
                            )
                        }
                        is QobuzQueueApi.JobOutcome.Failed -> {
                            QobuzQueueApi.forgetJob(packed, formatId)
                            TrackLog.d(TAG, "  ✗ qobuz job failed: ${job.reason}", about = trackId)
                            continue
                        }
                        QobuzQueueApi.JobOutcome.Timeout -> {
                            TrackLog.d(TAG, "  ✗ qobuz job timed out ($formatId)", about = trackId)
                            continue
                        }
                    }
                }
                QobuzQueueApi.QueueOutcome.ConverterDown -> {
                    NerdStats.noteRefusal(QobuzQueueApi.BASE_HOST, "converter down")
                    TrackLog.d(TAG, "  ✗ qobuz converter down", about = trackId)
                    return null
                }
                QobuzQueueApi.QueueOutcome.TokenDead -> {
                    noteUnlock()
                    return null
                }
                is QobuzQueueApi.QueueOutcome.Failed -> {
                    TrackLog.d(TAG, "  ✗ qobuz queue failed: ${attempt.reason}", about = trackId)
                    continue
                }
                null -> continue
            }
        }
        return null
    }

    private suspend fun tryQueue(
        trackId: String,
        track: QobuzQueueApi.QobuzTrack,
        formatId: Int,
        token: String?,
    ): QobuzQueueApi.QueueOutcome? {
        if (token.isNullOrBlank()) return null
        return try {
            QobuzQueueApi.queue(track, formatId, token)
        } catch (e: Exception) {
            TrackLog.d(TAG, "  ✗ qobuz queue threw: ${e.message}", about = trackId)
            null
        }
    }

    private suspend fun resumeToken(): String? {
        return when (val verdict = try {
            QobuzQueueApi.prepare(sp())
        } catch (e: Exception) {
            null
        }) {
            is QobuzQueueApi.TokenOutcome.Ready -> {
                persistSession(verdict.sp, verdict.token)
                verdict.token
            }
            else -> null
        }
    }

    private fun noteUnlock() {
        NerdStats.noteRefusal(QobuzQueueApi.BASE_HOST, "tap to unlock")
    }

}
