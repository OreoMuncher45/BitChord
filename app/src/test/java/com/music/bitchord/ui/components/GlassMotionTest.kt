/*
 * Copyright (C) 2026 BitChord
 *
 * The glass motion freeze, as arithmetic.
 *
 * This clock is the whole swipe-jank fix's safety story, and none of it
 * needs a renderer: it is one long compared against one window. What is
 * being pinned here is not the arithmetic itself but the properties the
 * rest of the glass depends on — that motion freezes immediately, that
 * settling thaws immediately, and above all that there is no sequence of
 * cancelled coroutines and missed callbacks that can leave the glass
 * frozen forever. A stuck freeze would not crash; it would read as glass
 * gone dead, once, on one device, with nothing in a log to point at it.
 *
 * Every assertion runs on any host, including the aarch64 one where
 * Robolectric cannot run at all.
 */

package com.music.bitchord.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassMotionTest {

    /**
     * The production window, restated. The duplication is deliberate, in
     * the way `PlayerWindowShapeTest` restates its thresholds: this test
     * states what the window has to mean for the sheet — wide enough to
     * span the open's whole 300ms layout wait (the one stretch of motion
     * with no frame to stamp), narrow enough that a freeze nobody cleared
     * is a hiccup rather than a habit — so a change to the constant that
     * breaks either half fails with the reason attached, not just a number.
     */
    private val windowNs = 500_000_000L

    /**
     * The sheet's layout wait after an open, restated from
     * `PlayerSheetMotion` (where it is private): the clock is stamped once
     * when the open starts, and cannot be stamped again until the sheet is
     * laid out and the slide's first frame runs — 300ms, at the outside.
     */
    private val layoutWaitNs = 300_000_000L

    /** Deterministic time: the test moves the clock by hand. */
    private var nowNs = 0L

    private fun clock() = GlassMotionClock(windowNs) { nowNs }

    /** Nothing has ever moved: glass must start live, not frozen on nothing. */
    @Test
    fun `a fresh clock is not frozen`() {
        assertFalse(clock().frozen())
    }

    /**
     * The "no motion" sentinel is not zero. `System.nanoTime` is under no
     * contract to be nonzero — a stamp taken at exactly zero is a real
     * frame of motion and must freeze, or the very first frame of motion
     * on some device would be the one frame the glass stutters on.
     */
    @Test
    fun `a stamp at the clock's zero is still motion`() {
        nowNs = 0L
        val clock = clock()
        clock.moved()
        assertTrue(clock.frozen())
    }

    /**
     * The normal end of motion: the sheet settles, the glass thaws on the
     * same beat — not when a window runs out. A slide's last frame and its
     * clear happen back to back, and the gap between them must not show.
     */
    @Test
    fun `settling thaws the glass at once`() {
        val clock = clock()
        clock.moved()
        clock.settled()
        assertFalse(clock.frozen())
    }

    /**
     * The property the whole design is built on: no missed clear can stick.
     * Every explicit settle lives in a coroutine that can be cancelled, so
     * the clock is constructed such that being frozen *is* "stamped
     * recently" — it goes false on its own, [windowNs] after the last
     * moving frame, whatever any path failed to call. The boundary itself
     * is asserted because it is the guarantee: the freeze is a bound, not a
     * promise someone has to keep.
     */
    @Test
    fun `the freeze expires on its own however it was left`() {
        val clock = clock()
        clock.moved()
        nowNs = windowNs - 1
        assertTrue("the last instant inside the window is still frozen", clock.frozen())
        nowNs = windowNs
        assertFalse("the window has no extra frame of grace", clock.frozen())
    }

    /**
     * The window is not the length of the freeze — per-frame stamping is.
     * A hard flick can run the spring for the better part of a second and
     * a slow pull longer still, and the freeze must last as long as the
     * motion does, not as long as a constant. So every moving frame
     * re-arms the window, and only the last one's counts down.
     */
    @Test
    fun `each frame of motion extends the freeze`() {
        val clock = clock()
        clock.moved()
        nowNs = 400_000_000L
        clock.moved()
        nowNs = 400_000_000L + windowNs - 1
        assertTrue("the window runs from the newest frame, not the first", clock.frozen())
        nowNs = 400_000_000L + windowNs
        assertFalse(clock.frozen())
    }

    /**
     * The one stretch of real motion with no frame to stamp: an open from
     * a tap stamps once and then waits for the sheet to be laid out before
     * the slide can stamp again. If the window were narrower than that
     * wait, the glass would thaw in the middle of the open — live
     * refraction for a few frames of an appearing sheet, then frozen
     * again when the slide starts, which is the stutter this fix exists
     * to remove, reintroduced as a flicker.
     */
    @Test
    fun `the window spans the sheet's whole layout wait`() {
        val clock = clock()
        clock.moved()
        nowNs = layoutWaitNs - 1
        assertTrue(
            "the freeze must still hold through the open's last possible layout-wait frame",
            clock.frozen(),
        )
        nowNs = layoutWaitNs
        assertTrue(
            "and the layout wait alone never runs the window out — the slide's first frame is still inside it",
            clock.frozen(),
        )
    }

    /**
     * `settled` is a state, not a timestamp: nothing later in time can
     * resurrect an expired motion from under it. The sheet is gone, the
     * glass must read live no matter how the clock is consulted afterwards
     * — a settle that decayed instead of clearing would leave the next
     * gesture racing a stale window.
     */
    @Test
    fun `settled stays settled while the clock runs on`() {
        val clock = clock()
        clock.moved()
        clock.settled()
        nowNs = 10_000_000L
        assertFalse(clock.frozen())
        nowNs = windowNs * 2
        assertFalse(clock.frozen())
    }
}
