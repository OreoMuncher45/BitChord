/*
 * Copyright (C) 2026 BitChord
 *
 * The tap-versus-swipe decision behind the account button, as plain logic.
 *
 * It is separated from the pointer plumbing because that plumbing is where the
 * bug was: a drag detector stacked ahead of `clickable` consumes the down
 * event, so the button never taps. The decision itself is testable on any host,
 * and a regression here is exactly the regression that shipped dead.
 */

package com.music.bitchord.ui.apple

import kotlin.math.abs

/**
 * How far a finger must travel sideways before a press stops being a tap.
 *
 * In pixels, not dp: the pointer events report pixels and converting would put
 * a density lookup in the middle of the gesture.
 */
const val APPLE_PROFILE_SWIPE_THRESHOLD_PX = 56f

/** What a gesture has done so far. */
internal sealed interface ProfileGesture {
    /** Still a press; nothing has been committed to yet. */
    data object Pressed : ProfileGesture

    /**
     * Passed the threshold. [forward] is true when the finger moved left, which
     * steps to the *next* account.
     *
     * Latched: once a gesture is a swipe it stays a swipe however far it goes,
     * so one drag steps exactly one account instead of every account the finger
     * crosses.
     */
    data class Swiped(val forward: Boolean) : ProfileGesture

    /**
     * The finger lifted. [wasSwipe] decides what fires: a tap, or nothing.
     */
    data class Released(val wasSwipe: Boolean) : ProfileGesture
}

/**
 * The gesture, accumulated across pointer events.
 *
 * Deliberately a plain object rather than a composable-local: the state that
 * decides whether the account button does anything at all should be checkable
 * without rendering anything.
 */
internal class ProfileGestureTracker(
    private val threshold: Float = APPLE_PROFILE_SWIPE_THRESHOLD_PX,
) {
    private var travelled = 0f
    private var swiped = false

    /** Call with each horizontal delta; returns what should fire right now. */
    fun onDrag(deltaX: Float): ProfileGesture {
        if (swiped) return ProfileGesture.Swiped(lastForward)
        travelled += deltaX
        if (abs(travelled) > threshold) {
            swiped = true
            lastForward = travelled < 0f
            return ProfileGesture.Swiped(lastForward)
        }
        return ProfileGesture.Pressed
    }

    private var lastForward = false

    /** Call once the finger lifts. */
    fun onRelease(): ProfileGesture.Released {
        val wasSwipe = swiped
        reset()
        return ProfileGesture.Released(wasSwipe)
    }

    /** Abandon without acting, e.g. the gesture was cancelled. */
    fun reset() {
        travelled = 0f
        swiped = false
        lastForward = false
    }
}
