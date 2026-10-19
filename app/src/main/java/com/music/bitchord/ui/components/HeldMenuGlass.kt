package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

// The held menu's material, and the shape it is cut with while the morph runs.
//
// It is here rather than in [HeldContextMenu] so that file stays about where
// the menu is placed and this one is about how it looks. Nothing in it knows
// what the menu offers.

/**
 * Whether the held menu gets liquid glass, or the flat plate that stands in for
 * it.
 *
 * The order of the two questions is the same one the app's other floating
 * surfaces ask in, and it is not arbitrary — see [Modifier.liquidGlass]'s
 * note on not reading the toggle itself, and MainActivity's
 * `glassSamplesBackdrop`. "Reduce dynamic blur" is asked *first* because it
 * switches off the recording of the app backdrop as well as the blur: a glass
 * surface composed while nothing is being recorded samples an empty layer, so
 * the menu would read as a hole punched in the page rather than as glass over
 * it. Asking the other way round — glass whenever the toggle is on, plate only
 * where it is off — is what [FloatingBottomBar] and [FrostedTopBar] do, and it
 * is what this follows.
 *
 * The menu is gated on the toggle rather than always glass, unlike
 * [Modifier.liquidGlass] itself: like the nav bar and the floating top
 * controls it is an app surface floating over a page, not chrome that is glass
 * by definition, so the switch has to mean something for it too.
 */
@Composable
internal fun heldMenuUsesGlass(reduceDynamicBlur: Boolean): Boolean =
    LocalLiquidGlassEnabled.current && isGlassSupported() && !reduceDynamicBlur

/**
 * The menu's surface for this frame: liquid glass where the app is already
 * using it, the flat plate everywhere else.
 *
 * [shape] is a parameter rather than a constant because the menu's corners move
 * while it opens — see [heldMenuMorphingShape]. Both branches cut to it and so
 * does the hairline, which is what keeps the edge a curve instead of a
 * straight line drawn across a corner that has already moved.
 *
 * Glass is sampled through [Modifier.liquidGlass], so the menu shares the
 * app's pooled backdrop rather than capturing one of its own; a menu drawing
 * its own backdrop would pay a second full-page pass every frame it moved.
 * The hairline is kept on top of the glass deliberately — the glass draws its
 * own refractive rim, but the navbar keeps this edge too so that every floating
 * surface in the app ends in the same line.
 */
@Composable
internal fun Modifier.heldMenuSurface(
    shape: CornerBasedShape,
    plate: Color,
    hairline: Color,
    reduceDynamicBlur: Boolean,
): Modifier {
    val glass = heldMenuUsesGlass(reduceDynamicBlur)
    return this
        .clip(shape)
        .then(if (glass) Modifier.liquidGlass(shape) else Modifier.background(plate, shape))
        .border(0.5.dp, hairline, shape)
}

/**
 * The menu's shape for this frame: the pressed item's plate radius at the
 * start of the morph, the menu's own radius at rest.
 *
 * [radiusPx] is read while the surface is composed rather than while it is
 * drawn, which is what forces the shape to be rebuilt each frame — see
 * [HeldMenuCornerRadius]. It is also what keeps the rebuild small: this call is
 * its own restart scope, so what is invalidated each frame is one surface's
 * modifiers rather than everything that reads the animation.
 */
@Composable
internal fun heldMenuMorphingShape(
    animates: Boolean,
    resting: CornerBasedShape,
    radiusPx: () -> Float,
): CornerBasedShape =
    if (!animates) resting else RoundedCornerShape(HeldMenuCornerRadius(radiusPx))

/**
 * A corner radius supplied per frame instead of fixed when the shape was built.
 *
 * [CornerSize] is what a [RoundedCornerShape] asks its radius through, so the
 * radius can be read at draw time while the shape that asked for it is just a
 * handle.
 *
 * Read at draw time is not sufficient on its own, and the reason decides the
 * whole design, so it is worth writing down: the vendored backdrop engine
 * caches the outline it has already built against a shape — `ShapeProvider`
 * keeps exactly one, keyed on the shape's identity — so a radius that moved
 * only *behind* a shape that never changed would go on drawing the first
 * outline it ever built. The shape therefore has to be a new one on every
 * frame the radius moves, which is what [equals] below is for: `remember`
 * hands back the shape it was given last time when the new one compares equal,
 * so two different radii must not compare equal.
 *
 * What that costs is one extra small backdrop capture, and one relayout of the
 * menu, for as long as the morph runs and not one frame longer. The surface is
 * a few hundred pixels across, the morph is shorter than a third of a second,
 * and at rest nothing changes at all. It buys the corner actually travelling
 * from the plate's radius to the menu's, which is the difference between a
 * menu that unfolds and one that merely gets bigger.
 */
internal class HeldMenuCornerRadius(private val radiusPx: () -> Float) : CornerSize {
    override fun toPx(shapeSize: Size, density: Density): Float = radiusPx()

    // Identity on purpose: a radius that reports itself equal to the last one
    // would keep `remember` handing the surface the shape it already had, and
    // the radius with it would never move.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
}
