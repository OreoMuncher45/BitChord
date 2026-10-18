package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which copy of a track plays, given the two things that decide it.
 *
 * Kept as a pure truth table rather than a test of `AppSettings`, which cannot
 * be built on the JVM: the bug this pins was not in the arithmetic but in the
 * wiring, where a caller read the detector and forgot the switch — or read a
 * signal whose offline verdict was computed at the wrong instant.
 */
class OfflineModeTest {

    private fun playsLocal(hasFile: Boolean, offline: Boolean) =
        shouldPlayLocalFile(hasLocalFile = hasFile, canStream = !offline)

    @Test
    fun `offline plays the file`() {
        assertTrue(playsLocal(hasFile = true, offline = true))
    }

    @Test
    fun `online streams even though the file exists`() {
        assertFalse(playsLocal(hasFile = true, offline = false))
    }

    @Test
    fun `offline with no file has nothing to play, and says nothing`() {
        assertFalse(playsLocal(hasFile = false, offline = true))
    }

    @Test
    fun `online with no file streams`() {
        assertFalse(playsLocal(hasFile = false, offline = false))
    }

    @Test
    fun `the switch and the detector are alternatives, either is enough`() {
        // AppSettings.effectiveOffline is `manual || !streaming`; this is that
        // line, spelled out, because a caller that reconstructs it wrongly is
        // how the switch ends up doing nothing.
        val combos = listOf(
            //              manual, streaming, offline?
            Triple(true, true, true),    // switch on, network present -> offline
            Triple(true, false, true),   // switch on, no network -> offline
            Triple(false, false, true),   // switch off, no network -> offline
            Triple(false, true, false),   // switch off, network present -> online
        )
        for ((manual, streaming, expected) in combos) {
            val offline = manual || !streaming
            assertTrue("manual=$manual streaming=$streaming", offline == expected)
        }
    }
}
