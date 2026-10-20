/*
 * Copyright (C) 2026 BitChord Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.music.bitchord.playback.audio

import com.music.bitchord.data.TelemetryProvenance
import java.util.LinkedHashMap

/**
 * One local file's bitrate as it was established, with the honesty label
 * attached at the moment it was measured rather than inferred later from
 * which map it came out of.
 *
 * A figure read out of the audio track's own format (ArchiveTune's approach —
 * `MediaExtractor` + `KEY_BIT_RATE`, see ArchiveTune
 * `PlayerConnection.extractLocalAudioProperties`) is a measurement of the
 * file: [TelemetryProvenance.MEASURED]. The file's total size divided by its
 * total duration — the last resort when no track format states a rate — is
 * arithmetic on two file facts rather than a measurement of the audio, and is
 * labelled [TelemetryProvenance.DERIVED] so the stats sheet can say so.
 */
data class LocalBitrateReading(
    val kbps: Int,
    val provenance: TelemetryProvenance,
)

/**
 * Pure logic behind the pipeline's true-bitrate measurement. No Android
 * imports, so every rule here runs in the plain JVM test suite.
 */
object ContainerBitrate {

    /**
     * The ceiling a compressed stream's real encoded rate can reach, as a
     * fraction of the rate its samples decode to.
     *
     * A FLAC-in-MP4 written by a lossless provider states the PCM data rate
     * of the master — 1411 kbps for CD — in the sample entry a track-format
     * read returns. That is not the encoded rate of the file: the same file
     * measures 879 kbps of actual bytes over actual played time. Real
     * compressed audio never approaches its own PCM rate (FLAC tops out
     * around 85% even on uncompressible noise, and sits far below in
     * practice), so a figure at or above this fraction of the PCM rate is
     * the master's claim wearing the file's name, and is refused. A WAV is
     * exempt by construction — its PCM rate is its encoded rate — but it
     * never reaches this guard as a container claim, because the measured
     * accumulator returns the same figure for it honestly.
     */
    private const val PCM_MASTER_NUMERATOR = 9L
    private const val PCM_MASTER_DENOMINATOR = 10L

    /** Sanity ceiling for any computed figure; DSD512 stereo (~45 Mbps) must fit under it. */
    const val MAX_KBPS = 50_000

    /** Depth assumed when the container states no PCM encoding — the common case for the MP4 sample entry. */
    private const val ASSUMED_BIT_DEPTH = 16

    /**
     * Whether [bitrateBps] — a rate the container stated for its audio
     * track — is plausible as the file's *encoded* rate, rather than the
     * PCM rate of the master it was cut from.
     *
     * Needs the track's own sample rate and channel count to judge; without
     * them there is no PCM rate to compare against and the figure stands.
     */
    fun isPlausibleEncodedBitrate(
        bitrateBps: Long,
        sampleRateHz: Int?,
        channels: Int?,
        bitDepth: Int?,
    ): Boolean {
        if (bitrateBps <= 0) return false
        val rate = sampleRateHz?.takeIf { it > 0 } ?: return true
        val channelCount = channels?.takeIf { it > 0 } ?: return true
        val depth = bitDepth?.takeIf { it > 0 } ?: ASSUMED_BIT_DEPTH
        val pcmBps = rate.toLong() * channelCount * depth
        // Exact-fraction compare, no floating point.
        return bitrateBps * PCM_MASTER_DENOMINATOR < pcmBps * PCM_MASTER_NUMERATOR
    }

    /**
     * The last-resort average: the file's own byte size over its own
     * duration. True arithmetic, but not a measurement of the audio, which
     * is why its callers publish it as [TelemetryProvenance.DERIVED] at
     * best.
     */
    fun averageKbpsFromSizeAndDuration(sizeBytes: Long, durationMs: Long): Int? {
        if (sizeBytes <= 0 || durationMs <= 0) return null
        return kbpsFromBytesAndTime(sizeBytes, durationMs)
    }

    /** bits-per-millisecond is kilobits-per-second exactly; rounded to nearest. */
    internal fun kbpsFromBytesAndTime(bytes: Long, timeMs: Long): Int? {
        if (bytes <= 0 || timeMs <= 0) return null
        val value = (bytes * 8 + timeMs / 2) / timeMs
        return value.toInt().takeIf { it in 1..MAX_KBPS }
    }
}

/**
 * Accumulates decoder-input bytes against the media time they cover, per
 * track, and reports the measured encoded bitrate once enough of the track
 * has actually flowed.
 *
 * This is the pipeline's bitrate *measurement*: the figure Poweramp shows
 * and the figure the owner demanded — bytes the decoder really consumed,
 * over time the track really spent playing — never the source's pre-made
 * 1411 kbps lossless claim, and never a source-declared average.
 *
 * Two weights are kept, because loads arrive in bursts a played-time-only
 * account would misread:
 *
 *  - *Media span* — each load carries the stretch of the timeline its bytes
 *    decode to (`mediaStartTimeMs`/`mediaEndTimeMs`). Summing bytes against
 *    spans gives the encoded rate of exactly the data that arrived, from
 *    the first chunks, seek-proof because each load carries its own span.
 *  - *Played time* — the fallback for streams whose loads state no span
 *    (live/unknown seek maps): position deltas while playing, with any
 *    discontinuity or seek dropping out of the account rather than
 *    polluting it.
 *
 * Until one of the two has accumulated enough ([MIN_SPAN_MEDIA_MS] of span
 * or [MIN_PLAYED_MS] of playback, and [MIN_BYTES] of data), the honest
 * answer is "not measured yet" — null — and the stats sheet falls through
 * to the next rung of its chain rather than guessing.
 */
class MeasuredBitrateMeter(
    private val minSpanMediaMs: Long = DEFAULT_MIN_SPAN_MEDIA_MS,
    private val minPlayedMs: Long = DEFAULT_MIN_PLAYED_MS,
    private val minBytes: Long = DEFAULT_MIN_BYTES,
) {
    private class Tally {
        var bytes = 0L
        var spanMediaMs = 0L
        var playedMs = 0L
        var lastPositionMs = UNSET
        var lastTickRealtimeMs = 0L
    }

    private val tallies = object : LinkedHashMap<String, Tally>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Tally>?): Boolean = size > MAX_TRACKED
    }

    /** A completed load of [bytes] bytes, covering [mediaStartMs]..[mediaEndMs] of the track's timeline. */
    @Synchronized
    fun onBytesLoaded(
        mediaId: String?,
        bytes: Long,
        mediaStartMs: Long,
        mediaEndMs: Long,
    ) {
        if (mediaId.isNullOrBlank() || bytes <= 0) return
        val tally = tallyFor(mediaId)
        tally.bytes += bytes
        // A load that states no timeline span (unset/negative/garbage) still
        // counts its bytes; it just cannot feed the span-weighted figure.
        val span = mediaEndMs - mediaStartMs
        if (mediaStartMs >= 0 && mediaEndMs > mediaStartMs && span <= MAX_SPAN_PER_LOAD_MS) {
            tally.spanMediaMs += span
        }
    }

    /**
     * A playback-position sample taken while the track is audibly playing.
     * Only a forward move consistent with wall time counts as played; a
     * seek, a discontinuity or a backwards slip updates the anchor without
     * adding to the account.
     */
    @Synchronized
    fun onPlayTick(
        mediaId: String?,
        positionMs: Long,
        elapsedRealtimeMs: Long,
    ) {
        if (mediaId.isNullOrBlank() || positionMs < 0) return
        val tally = tallyFor(mediaId)
        if (tally.lastPositionMs != UNSET) {
            val delta = positionMs - tally.lastPositionMs
            val wallMs = (elapsedRealtimeMs - tally.lastTickRealtimeMs).coerceAtLeast(0L)
            // Playback speed multiplies position advance against wall time;
            // anything beyond that multiplier between two ticks is a seek
            // the discontinuity callback missed, and counts as nothing.
            val capMs = wallMs * MAX_TICK_SPEED_MULTIPLIER
            if (delta in 0..capMs) {
                tally.playedMs += delta
            }
        }
        tally.lastPositionMs = positionMs
        tally.lastTickRealtimeMs = elapsedRealtimeMs
    }

    /** A seek or transition boundary: the next tick starts a fresh anchor, counting no time across it. */
    @Synchronized
    fun onDiscontinuity(mediaId: String?) {
        if (mediaId.isNullOrBlank()) return
        tallies[mediaId]?.let { it.lastPositionMs = UNSET }
    }

    /**
     * The measured encoded bitrate for [mediaId], or null while not enough
     * of the track has flowed to say one. Span-weighted first — it needs no
     * waiting for playback to accumulate — then played-time-weighted.
     */
    @Synchronized
    fun measuredKbps(mediaId: String?): Int? {
        if (mediaId.isNullOrBlank()) return null
        val tally = tallies[mediaId] ?: return null
        if (tally.bytes < minBytes) return null
        if (tally.spanMediaMs >= minSpanMediaMs) {
            return ContainerBitrate.kbpsFromBytesAndTime(tally.bytes, tally.spanMediaMs)
        }
        if (tally.playedMs >= minPlayedMs) {
            return ContainerBitrate.kbpsFromBytesAndTime(tally.bytes, tally.playedMs)
        }
        return null
    }

    /** Drops every accumulated figure, for a player that is no more. */
    @Synchronized
    fun forget() {
        tallies.clear()
    }

    private fun tallyFor(mediaId: String): Tally = tallies.getOrPut(mediaId) { Tally() }

    companion object {
        /** Enough span-loaded media (5s) for the span-weighted figure to be worth printing. */
        const val DEFAULT_MIN_SPAN_MEDIA_MS = 5_000L

        /** Enough played time (10s) for the played-time figure to have survived its first buffer burst. */
        const val DEFAULT_MIN_PLAYED_MS = 10_000L

        /** Enough bytes (~50 KB) that a kbps figure is not three bytes and a rounding error. */
        const val DEFAULT_MIN_BYTES = 50_000L

        /** A single load cannot honestly span more media time than this; anything larger is a container lie. */
        const val MAX_SPAN_PER_LOAD_MS = 600_000L

        /** Position may advance at most 4x wall time between ticks; beyond is treated as a seek. */
        const val MAX_TICK_SPEED_MULTIPLIER = 4L

        /** Tracks kept simultaneously — enough for read-ahead tallies without unbounded growth. */
        const val MAX_TRACKED = 16

        private const val UNSET = Long.MIN_VALUE
    }
}
