package com.music.bitchord.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.util.lerp

/**
 * One frame of the held menu's entrance: the rectangle the menu is being drawn
 * in, relative to where the layout laid it out.
 *
 * A type of its own rather than five values written straight into the layer
 * because both ends of this animation have to agree with what is actually on
 * screen for it to read as one surface opening rather than two: at fraction 0
 * the menu must cover the plate the finger was holding, and at 1 the rectangle
 * the layout gave it. Both are known in pixels before anything is drawn, so
 * this is arithmetic over two rectangles — no state, no gesture, no drawing.
 *
 * That is what makes it worth pulling out of [HeldContextMenu] instead of
 * living in the layer block: plain arithmetic over two rectangles is the one
 * part of a menu opening that can be tested on any host. It is also where a
 * mistake is quiet — a wrong offset here does not crash, it opens the menu
 * somewhere the finger never was.
 */
internal data class HeldMenuMorph(
    /** Horizontal scale, taken about the origin rectangle's top-left corner. */
    val scaleX: Float,
    /** The same, down the screen. */
    val scaleY: Float,
    /** How far the scaled rectangle's top-left sits from where it is laid out. */
    val translationX: Float,
    /** The same, down the screen. */
    val translationY: Float,
)

/**
 * The held menu at [fraction] of the way from where it was pressed to where it
 * lies.
 *
 * This is a *rectangle* growing, not a box scaling about its middle. The scale
 * is taken about the top-left corner and the offset is applied outside it — in
 * Compose's layer transform the translation is applied before the scale, so it
 * lands in the parent's coordinates — which leaves the origin's left and top
 * edges pinned to the item while its right and bottom edges travel out to meet
 * the menu's. The card beside it keeps scaling about its centre, and that is
 * right for it: it is a picture of the item and has to stay over the thing it
 * is a picture of. The menu has no such anchor, and pinning one edge is what
 * makes it look like the plate unfolding instead of a second surface sliding
 * in behind it.
 *
 * A laid-out side of zero would divide by nothing. That collapses to the
 * resting transform, so a menu with no size yet fades in where it is instead
 * of scaling towards infinity.
 */
internal fun heldMenuMorph(fraction: Float, origin: Rect, laidOut: Rect): HeldMenuMorph {
    val p = fraction.coerceIn(0f, 1f)
    val width = laidOut.width
    val height = laidOut.height
    if (width <= 0f || height <= 0f) return HeldMenuMorph(1f, 1f, 0f, 0f)
    return HeldMenuMorph(
        scaleX = lerp(origin.width / width, 1f, p),
        scaleY = lerp(origin.height / height, 1f, p),
        // The whole difference between the two top-left corners, not the same
        // difference scaled — see the note on the transform order above.
        translationX = lerp(origin.left - laidOut.left, 0f, p),
        translationY = lerp(origin.top - laidOut.top, 0f, p),
    )
}

/**
 * The corner radius for the same frame: the pressed item's plate radius where
 * the morph starts, the menu's own where it rests.
 *
 * Kept out of [heldMenuMorph] because the two are read at different times. The
 * rectangle is only known once the layout has measured the menu, which is
 * later and in the layout pass; the radius has to be built while the surface
 * is being composed, because a shape has to exist before anything can ask one
 * for its outline.
 */
internal fun heldMenuCornerRadiusPx(
    fraction: Float,
    originRadiusPx: Float,
    restingRadiusPx: Float,
): Float = lerp(originRadiusPx, restingRadiusPx, fraction.coerceIn(0f, 1f))
