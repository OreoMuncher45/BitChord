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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The true-bitrate measurement behind the audio pipeline's bitrate row.
 *
 * The owner's rule, tested: the published figure is MEASURED — decoder-input
 * bytes over the media time they cover — never the lossless source's pre-made
 * 1411 kbps claim, never a laundered container claim, and the
 * size-over-duration average only as a labelled last resort.
 */
class AudioPipelineMeasuredBitrateTest {

    // ── Span-weighted measurement ────────────────────────────────────────

    @Test
    fun spanWeightedMeasurementReportsTrueEncodedRate() {
        val meter = MeasuredBitrateMeter()
        // Two loads covering 0-5s and 5-10s of media, 1,000,000 bytes in all.
        meter.onBytesLoaded("track", 500_000, 0, 5_000)
        meter.onBytesLoaded("track", 500_000, 5_000, 10_000)
        // 1,000,000 bytes * 8 / 10,000 ms = 800 kbps, from the first ~10s of data.
        assertEquals(800, meter.measuredKbps("track"))
    }

    @Test
    fun spanMeasurementNeedsBothMinimumBytesAndMinimumSpan() {
        val meter = MeasuredBitrateMeter()
        // Bytes below the floor: not enough data to mean anything.
        meter.onBytesLoaded("track", 49_999, 0, 4_999)
        assertNull(meter.measuredKbps("track"))

        val enoughBytes = MeasuredBitrateMeter()
        enoughBytes.onBytesLoaded("track", 50_000, 0, 4_999)
        // Span below the floor (5s): hold back rather than print a guess.
        assertNull(enoughBytes.measuredKbps("track"))
    }

    @Test
    fun loadsWithoutTimelineSpansDoNotPolluteTheSpanAccount() {
        val meter = MeasuredBitrateMeter()
        // Media3 reports no seek map for a live stream: span stays unset.
        meter.onBytesLoaded("track", 1_000_000, Long.MIN_VALUE + 1, Long.MIN_VALUE + 1)
        // Bytes counted, span not — so no span-weighted figure is published.
        assertNull(meter.measuredKbps("track"))
    }

    @Test
    fun absurdSpanIsRefusedAsAContainerLie() {
        val meter = MeasuredBitrateMeter()
        // A single "load" spanning more media time than the cap is a
        // container lie about what the bytes cover, not a measurement.
        meter.onBytesLoaded("track", 1_000_000, 0, MeasuredBitrateMeter.MAX_SPAN_PER_LOAD_MS + 1)
        assertNull(meter.measuredKbps("track"))
    }

    @Test
    fun implausiblyHugeRateIsRejected() {
        val meter = MeasuredBitrateMeter()
        // 63 MB claiming to cover 10 seconds of media: 50,400 kbps, above
        // the DSD512-scale ceiling, so no figure at all rather than garbage.
        meter.onBytesLoaded("track", 63_000_000, 0, 10_000)
        assertNull(meter.measuredKbps("track"))
    }

    @Test
    fun dsdScaleRatesSurviveTheCeiling() {
        val meter = MeasuredBitrateMeter()
        // DSD256 stereo: 22.6 Mbps, and the meter must carry it.
        val bytes = 2_825_000L * 80 // 226,000,000 bytes over 80 seconds
        meter.onBytesLoaded("dsd", bytes, 0, 80_000)
        assertEquals(22_600, meter.measuredKbps("dsd"))
    }

    // ── Played-time fallback ─────────────────────────────────────────────

    @Test
    fun playedTimeFallbackMeasuresBytesOverPlayedDuration() {
        val meter = MeasuredBitrateMeter()
        // Loads with no usable span; playback ticks supply the time.
        meter.onBytesLoaded("track", 1_000_000, Long.MIN_VALUE + 1, Long.MIN_VALUE + 1)
        meter.onPlayTick("track", 0, 0)
        meter.onPlayTick("track", 5_000, 5_000)
        meter.onPlayTick("track", 10_000, 10_000)
        // 1,000,000 bytes over 10,000ms of actually played time = 800 kbps.
        assertEquals(800, meter.measuredKbps("track"))
    }

    @Test
    fun playedTimeFallbackWaitsForEnoughPlayedTime() {
        val meter = MeasuredBitrateMeter()
        meter.onBytesLoaded("track", 1_000_000, Long.MIN_VALUE + 1, Long.MIN_VALUE + 1)
        meter.onPlayTick("track", 0, 0)
        meter.onPlayTick("track", 5_000, 5_000)
        // Only 5s played: the first buffer burst would inflate the figure,
        // so nothing publishes yet.
        assertNull(meter.measuredKbps("track"))
    }

    @Test
    fun seekDiscontinuityDoesNotCountJumpedTime() {
        val meter = MeasuredBitrateMeter()
        meter.onBytesLoaded("track", 1_000_000, Long.MIN_VALUE + 1, Long.MIN_VALUE + 1)
        meter.onPlayTick("track", 0, 0)
        meter.onPlayTick("track", 5_000, 5_000)
        meter.onDiscontinuity("track")
        // Seek from 5s to 60s: the 55s jumped over is not played time.
        meter.onPlayTick("track", 60_000, 6_000)
        meter.onPlayTick("track", 65_000, 11_000)
        // 10s genuinely played (5s before the seek, 5s after) = 800 kbps.
        assertEquals(800, meter.measuredKbps("track"))
    }

    @Test
    fun unreportedSeekIsSelfHealedByTheSpeedCap() {
        val meter = MeasuredBitrateMeter()
        meter.onBytesLoaded("track", 1_000_000, Long.MIN_VALUE + 1, Long.MIN_VALUE + 1)
        meter.onPlayTick("track", 0, 0)
        meter.onPlayTick("track", 5_000, 5_000)
        // A position jump no discontinuity callback reported: 55s of
        // "advance" against 5s of wall time is far past the 4x cap and
        // counts as nothing, exactly as a reported seek would.
        meter.onPlayTick("track", 60_000, 10_000)
        meter.onPlayTick("track", 65_000, 15_000)
        // 5s + 5s genuinely played.
        assertEquals(800, meter.measuredKbps("track"))
    }

    @Test
    fun backwardsPositionSlipCountsNothing() {
        val meter = MeasuredBitrateMeter()
        meter.onBytesLoaded("track", 1_000_000, Long.MIN_VALUE + 1, Long.MIN_VALUE + 1)
        meter.onPlayTick("track", 5_000, 5_000)
        meter.onPlayTick("track", 3_000, 10_000)
        meter.onPlayTick("track", 13_000, 15_000)
        // 5s backwards counts 0; 10s forward counts 10s.
        assertEquals(800, meter.measuredKbps("track"))
    }

    // ── Mode preference and hygiene ──────────────────────────────────────

    @Test
    fun spanModeIsPreferredOverPlayedTime() {
        val meter = MeasuredBitrateMeter()
        // 400,000 bytes genuinely cover 5s of media (640 kbps), while the
        // same bytes over 10s of played time would claim 320.
        meter.onBytesLoaded("track", 400_000, 0, 5_000)
        meter.onPlayTick("track", 0, 0)
        meter.onPlayTick("track", 5_000, 5_000)
        meter.onPlayTick("track", 10_000, 10_000)
        assertEquals(640, meter.measuredKbps("track"))
    }

    @Test
    fun loadsAreAttributedToTheirOwnTrack() {
        val meter = MeasuredBitrateMeter()
        meter.onBytesLoaded("a", 1_000_000, 0, 10_000)
        meter.onBytesLoaded("b", 500_000, 0, 10_000)
        assertEquals(800, meter.measuredKbps("a"))
        assertEquals(400, meter.measuredKbps("b"))
        assertNull(meter.measuredKbps("c"))
    }

    @Test
    fun forgetDropsEveryAccumulatedFigure() {
        val meter = MeasuredBitrateMeter()
        meter.onBytesLoaded("track", 1_000_000, 0, 10_000)
        assertEquals(800, meter.measuredKbps("track"))
        meter.forget()
        assertNull(meter.measuredKbps("track"))
    }

    @Test
    fun onlyBoundedTrackHistoryIsKept() {
        val meter = MeasuredBitrateMeter()
        for (i in 0 until MeasuredBitrateMeter.MAX_TRACKED) {
            meter.onBytesLoaded("t$i", 1_000_000, 0, 10_000)
        }
        assertEquals(800, meter.measuredKbps("t0"))
        // One more track than the cap evicts the least recently used tally.
        // t0 was just read, so the eviction takes t1 — that is what bounded
        // means: reading a track keeps it, ignoring one does not.
        meter.onBytesLoaded("extra", 1_000_000, 0, 10_000)
        assertNull(meter.measuredKbps("t1"))
        assertEquals(800, meter.measuredKbps("t0"))
        assertEquals(800, meter.measuredKbps("extra"))
    }

    // ── The 1411 bar on container claims ────────────────────────────────

    @Test
    fun preMadePcmMasterClaimIsRefused() {
        // The exact pre-made claim: 1411.2 kbps = the 16-bit/44.1kHz stereo
        // PCM data rate of the master, stated by a FLAC-in-MP4 sample entry.
        assertFalse(ContainerBitrate.isPlausibleEncodedBitrate(1_411_200, 44_100, 2, 16))
        assertFalse(ContainerBitrate.isPlausibleEncodedBitrate(1_411_200, 44_100, 2, null))
        assertFalse(ContainerBitrate.isPlausibleEncodedBitrate(0, 44_100, 2, 16))
        assertFalse(ContainerBitrate.isPlausibleEncodedBitrate(-1, 44_100, 2, 16))
    }

    @Test
    fun genuineEncodedRatesPass() {
        // The same file's real measured average (Poweramp's figure).
        assertTrue(ContainerBitrate.isPlausibleEncodedBitrate(879_000, 44_100, 2, 16))
        // A lossy 320 kbps MP3.
        assertTrue(ContainerBitrate.isPlausibleEncodedBitrate(320_000, 44_100, 2, 16))
        // A 24-bit FLAC's real rate can exceed the *16-bit* PCM rate — the
        // depth must be read, not assumed, or genuine hi-res is refused.
        assertTrue(ContainerBitrate.isPlausibleEncodedBitrate(1_500_000, 44_100, 2, 24))
        // DSD's own encoded rate is exact by format and always plausible.
        assertTrue(ContainerBitrate.isPlausibleEncodedBitrate(5_644_800, 2_822_400, 2, null))
        // No sample rate to judge against: the figure stands.
        assertTrue(ContainerBitrate.isPlausibleEncodedBitrate(1_411_200, null, 2, 16))
    }

    // ── The size-over-duration last resort, labelled DERIVED ────────────

    @Test
    fun sizeOverDurationMathIsExact() {
        // 10 MB over 90s: 10,000,000 * 8 / 90,000 = 888.9 -> 889 kbps.
        assertEquals(889, ContainerBitrate.averageKbpsFromSizeAndDuration(10_000_000, 90_000))
        assertNull(ContainerBitrate.averageKbpsFromSizeAndDuration(0, 90_000))
        assertNull(ContainerBitrate.averageKbpsFromSizeAndDuration(10_000_000, 0))
        assertNull(ContainerBitrate.averageKbpsFromSizeAndDuration(-1, -1))
    }

    @Test
    fun provenanceDistinguishesMeasurementFromDerivation() {
        // A track-format extraction is a measurement of the file; the
        // size/duration average is derived arithmetic on two file facts.
        assertEquals(TelemetryProvenance.MEASURED, LocalBitrateReading(879, TelemetryProvenance.MEASURED).provenance)
        assertEquals(TelemetryProvenance.DERIVED, LocalBitrateReading(889, TelemetryProvenance.DERIVED).provenance)
    }
}
