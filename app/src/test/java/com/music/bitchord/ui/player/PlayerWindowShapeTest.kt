/*
 * Copyright (C) 2026 BitChord
 *
 * The two decisions Now Playing makes about the window it is being handed.
 *
 * `NowPlayingScreen.kt` is three and a half thousand lines and cannot be
 * rendered without the whole app behind it, but the answers it gives to "which
 * shape is this window?" are plain functions over two `Dp`s, and they are the
 * ones most easily broken by a layout change that looks like a no-op. Move a
 * gutter, or read the width off the display instead of the window in one place
 * and not the other, and nothing crashes: a tablet quietly stops going full
 * bleed, or a portrait tablet silently switches to the landscape player.
 *
 * So they are tested here as what they are — arithmetic — which means these
 * assertions run on any host, including the aarch64 one where every
 * Robolectric test in this module is excluded.
 */

package com.music.bitchord.ui.player

import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerWindowShapeTest {

    /**
     * `fullBleedArtworkAvailable` is `playerFillsWindow(w) || tabletSizedPlayer(w)`,
     * and those two are `w <= PLAYER_MAX_WIDTH + PLAYER_GUTTER * 2` and
     * `w >= TABLET_PLAYER_MIN_WIDTH` — 620dp and 700dp as the file has them now.
     *
     * Written out rather than imported because `PLAYER_MAX_WIDTH` and
     * `PLAYER_GUTTER` are `internal` to `:sharedUi` and not visible from here.
     * That is deliberate: this test states what the thresholds *mean* for a
     * window, so that when one of them moves the failure says which device
     * class changed its mind, rather than just that a number moved.
     */
    @Test
    fun `a phone-width window takes the artwork edge to edge`() {
        assertTrue(fullBleedArtworkAvailable(320.dp))
        assertTrue(fullBleedArtworkAvailable(360.dp))
        assertTrue(fullBleedArtworkAvailable(411.dp))
        assertTrue(fullBleedArtworkAvailable(480.dp))
    }

    /** The boundary itself: the last width where the player fills the window, and the first past it. */
    @Test
    fun `the player-fills-the-window boundary is 620dp`() {
        assertTrue(
            "at exactly the limit the player still fills the window",
            fullBleedArtworkAvailable(620.dp),
        )
        assertFalse(
            "one dp wider the player does not fill the window",
            fullBleedArtworkAvailable(621.dp),
        )
    }

    /**
     * The gap between the two rules is the whole of the middle band, and it is
     * a band rather than a boundary because both rules are deliberate: a window
     * past 620dp is a tablet at rest and past 700dp is one deliberately. A
     * change that made these two meet — or drift apart — would change which
     * devices the setting is offered on, with nothing to notice.
     */
    @Test
    fun `only the band between the phone limit and the tablet limit is excluded`() {
        val excluded = (1..2_048).filter { width -> !fullBleedArtworkAvailable(width.dp) }
        assertEquals(
            "every width between the two limits, and no other, must be excluded",
            (621..699).toList(),
            excluded,
        )
        assertTrue(fullBleedArtworkAvailable(700.dp))
        assertFalse(fullBleedArtworkAvailable(699.dp))
        assertTrue(fullBleedArtworkAvailable(1_024.dp))
        assertTrue(fullBleedArtworkAvailable(2_048.dp))
    }

    /** A width alone never makes it the landscape player — the shape does. */
    @Test
    fun `a window wider than it is tall has to be wide enough as well`() {
        assertFalse(
            "a phone on its side is still narrower than the landscape minimum",
            landscapePlayerAvailable(500.dp, 300.dp),
        )
        assertTrue(landscapePlayerAvailable(640.dp, 360.dp))
        assertTrue(landscapePlayerAvailable(1_024.dp, 768.dp))
    }

    /** The landscape minimum is 560dp, and the equality belongs on the width. */
    @Test
    fun `the landscape minimum width is 560dp and is inclusive`() {
        assertFalse(landscapePlayerAvailable(559.dp, 320.dp))
        assertTrue(landscapePlayerAvailable(560.dp, 320.dp))
        assertTrue(landscapePlayerAvailable(561.dp, 320.dp))
    }

    /**
     * The branch that is the whole reason this function takes two arguments:
     * a tablet held upright is as wide as a phone held sideways, and it is the
     * second shape that wants the two-column player. Reading width alone — or
     * asking about "large screens" — would give a portrait tablet the layout
     * meant for a landscape phone.
     */
    @Test
    fun `a wide window that is taller than it is wide still gets the portrait player`() {
        assertFalse("square is not landscape", landscapePlayerAvailable(700.dp, 700.dp))
        assertFalse(
            "a portrait tablet is as wide as a landscape phone and is still portrait",
            landscapePlayerAvailable(800.dp, 1_280.dp),
        )
        // The same width, turned on its side, is the other answer.
        assertTrue(landscapePlayerAvailable(1_280.dp, 800.dp))
    }

    /** A window narrower than it is tall is never the landscape player, however large. */
    @Test
    fun `a portrait window is never the landscape player`() {
        assertFalse(landscapePlayerAvailable(411.dp, 891.dp))
        assertFalse(landscapePlayerAvailable(560.dp, 900.dp))
        assertFalse(landscapePlayerAvailable(4_096.dp, 4_097.dp))
    }

    /**
     * These three are Media3's own values, restated so the transport can speak
     * them without a Media3 type on the other side of `PlayerControls.kt`.
     *
     * The duplication is the point, and it is the kind that rots: the numbers
     * still mean the same thing to a device whether or not this file agrees
     * with Media3, so a wrong constant here does not throw — it writes a
     * repeat mode the service reads as a different one. Compare against the
     * library rather than against a literal.
     */
    @Test
    fun `the repeat modes are Media3's own values`() {
        assertEquals(Player.REPEAT_MODE_OFF, RepeatModes.OFF)
        assertEquals(Player.REPEAT_MODE_ONE, RepeatModes.ONE)
        assertEquals(Player.REPEAT_MODE_ALL, RepeatModes.ALL)
    }

    /**
     * The cycle is driven by comparisons against these values rather than by
     * stepping them, so all three have to stay distinct and ordered — a
     * duplicate would make one repeat mode unreachable from the capsule.
     */
    @Test
    fun `the three repeat modes are distinct and ordered`() {
        val modes = listOf(RepeatModes.OFF, RepeatModes.ONE, RepeatModes.ALL)
        assertEquals("the cycle needs three distinct modes", modes.size, modes.toSet().size)
        assertEquals(listOf(0, 1, 2), modes)
    }
}
