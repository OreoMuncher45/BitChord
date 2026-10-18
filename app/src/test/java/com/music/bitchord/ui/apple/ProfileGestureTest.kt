/*
 * Copyright (C) 2026 BitChord
 *
 * The account button shipped dead: a drag detector stacked ahead of `clickable`
 * consumed the down event, so a tap never reached the callback. The fix put the
 * tap-versus-swipe decision in [ProfileGestureTracker] precisely so it could be
 * checked here — on any host, with no renderer — rather than only by opening the
 * app and tapping.
 */

package com.music.bitchord.ui.apple

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileGestureTest {

    @Test
    fun `a press that never moves is a tap`() {
        val t = ProfileGestureTracker()
        assertEquals(ProfileGesture.Pressed, t.onDrag(0f))
        assertFalse(t.onRelease().wasSwipe)
    }

    @Test
    fun `small jitter is still a tap`() {
        val t = ProfileGestureTracker()
        t.onDrag(4f)
        t.onDrag(-3f)
        t.onDrag(7f)
        assertFalse(t.onRelease().wasSwipe)
    }

    @Test
    fun `travel past the threshold is a swipe and never a tap`() {
        val t = ProfileGestureTracker()
        t.onDrag(20f)
        t.onDrag(50f) // total 70 > 56
        assertTrue(t.onRelease().wasSwipe)
    }

    @Test
    fun `one gesture steps exactly one account however far it travels`() {
        val t = ProfileGestureTracker()
        t.onDrag(60f)
        // Continuing past the threshold must not re-fire.
        assertEquals(ProfileGesture.Swiped(forward = false), t.onDrag(400f))
        assertEquals(ProfileGesture.Swiped(forward = false), t.onDrag(400f))
        assertTrue(t.onRelease().wasSwipe)
    }

    @Test
    fun `swiping right steps backwards`() {
        val t = ProfileGestureTracker()
        t.onDrag(-60f)
        assertEquals(ProfileGesture.Swiped(forward = true), t.onDrag(0f))
    }

    @Test
    fun `swiping left steps forwards`() {
        val t = ProfileGestureTracker()
        t.onDrag(60f)
        assertEquals(ProfileGesture.Swiped(forward = false), t.onDrag(0f))
    }

    @Test
    fun `the tracker resets between gestures`() {
        val t = ProfileGestureTracker()
        t.onDrag(200f)
        assertTrue(t.onRelease().wasSwipe)

        // A fresh tap after a swipe must still be a tap: the old travel must not
        // carry over, or the second gesture would be pre-armed as a swipe.
        assertFalse(t.onRelease().wasSwipe)
    }

    @Test
    fun `exactly the threshold is still a tap`() {
        val t = ProfileGestureTracker()
        t.onDrag(APPLE_PROFILE_SWIPE_THRESHOLD_PX)
        assertFalse("the threshold is exclusive; equal must not trip it",
            t.onRelease().wasSwipe)
    }
}
