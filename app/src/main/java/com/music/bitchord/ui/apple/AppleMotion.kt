package com.music.bitchord.ui.apple

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Apple's motion, as springs rather than durations.
 *
 * The legacy shell animates with fixed millisecond counts, which is why its
 * transitions read as slightly mechanical: a duration cannot absorb a change
 * of distance, so the same curve has to be tuned for one screen's travel and
 * then stretched for another's. These are springs, so the *character* is
 * specified and the duration falls out of the geometry — a short hop settles in
 * less time than a long one without needing its own curve.
 *
 * Two feel-defining numbers, and they are not interchangeable:
 *
 * - **Damping ratio 0.86** is Apple's "settle without overshoot". Lower reads
 *   as bouncy and cheap, higher as stiff and mechanical. Anything at or below
 *   0.8 with this stiffness is a toy.
 * - **Stiffness 400** is the mid setting: fast enough to feel immediate, slow
 *   enough that the eye can follow what moved. [sheetSpring] is deliberately
 *   softer, because a sheet that arrives as fast as a tab does reads as a
 *   jump-cut rather than something being handed to you.
 */
object AppleMotion {

    /** Tabs, pills, selection, anything that answers a tap. */
    val standardSpring: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.86f, stiffness = 400f)

    /** IntOffset variants, for anything that moves in pixels. */
    fun <T> standard(dampingRatio: Float = 0.86f, stiffness: Float = 400f) =
        spring<Float>(dampingRatio = dampingRatio, stiffness = stiffness)

    fun standardOffset(): FiniteAnimationSpec<IntOffset> =
        spring(dampingRatio = 0.86f, stiffness = 400f)

    /** Sheets: softer and slower, because they carry weight. */
    val sheetSpring: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.9f, stiffness = 320f)

    /** The hero artwork flying between the mini player and the full player. */
    val artworkSpring: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.85f, stiffness = 260f)

    /**
     * The one easing still used for scroll-linked things, where a spring has
     * nothing to interrupt. Apple's scroll curve: a strong ease-out that covers
     * most of the distance in the first third.
     */
    val scroll: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** Cross-fades. Deliberately plain — fades are not where character lives. */
    val fade: Duration = 180.milliseconds

    /** The tab switch: fast enough that tapping feels like a cut. */
    val tabSwitch: Duration = 220.milliseconds

    /**
     * The large title collapsing is *not* animated. It is driven directly by
     * scroll offset, which is why in the real app the title is under your thumb
     * and cannot be seen to animate. Animating it would add a frame of lag
     * between the finger and the thing the finger is dragging.
     */
    const val COLLAPSE_START_FRACTION = 0.55f

    /** Constants exposed so call sites do not invent their own durations. */
    const val PRESS_SCALE = 0.96f
    const val PRESS_DURATION_MS = 120
}
