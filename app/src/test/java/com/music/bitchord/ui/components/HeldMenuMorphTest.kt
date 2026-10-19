/*
 * Copyright (C) 2026 BitChord
 *
 * The held menu's entrance, as arithmetic.
 *
 * Two things make this worth testing outside Compose:
 *
 *  - A wrong number here does not crash. It opens the menu somewhere the finger
 *    never was, which is exactly the class of defect nobody opens the app to
 *    look for. There is no stack trace and no log line; there is only a menu
 *    that appears in the wrong place, once, on one screen size.
 *  - None of it needs a renderer. `heldMenuMorph` is arithmetic over two
 *    rectangles that are already known in pixels before anything is drawn, so
 *    every assertion below runs on any host — including the aarch64 one, where
 *    Robolectric cannot run at all and every interaction test in this module is
 *    excluded.
 *
 * So these are the tests that will actually have run before the next person
 * touches the glass. The Robolectric tests next door cover the wiring; this
 * covers the geometry.
 */

package com.music.bitchord.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldMenuMorphTest {

    private val density = Density(1f)

    /**
     * The plate the finger was on: a card-shaped rectangle somewhere in the
     * upper half of the screen, smaller than the menu that opens out of it.
     * This is the shape a grid tile's plate has.
     */
    private val origin = Rect(left = 40f, top = 300f, right = 160f, bottom = 420f)

    /** Where the layout put the menu: wider, shorter, and lower down. */
    private val laidOut = Rect(left = 16f, top = 440f, right = 296f, bottom = 640f)

    /**
     * Where [morph] actually draws the rectangle the layout laid out, given
     * Compose's layer transform order: the translation is applied in the
     * parent's coordinates and the scale is taken about the top-left corner,
     * which is `transformOrigin = TransformOrigin(0f, 0f)` at the call site.
     */
    private fun drawn(morph: HeldMenuMorph): Rect = Rect(
        left = laidOut.left + morph.translationX,
        top = laidOut.top + morph.translationY,
        right = laidOut.left + morph.translationX + morph.scaleX * laidOut.width,
        bottom = laidOut.top + morph.translationY + morph.scaleY * laidOut.height,
    )

    /**
     * The whole point of the morph: at fraction 0 the menu *is* the plate the
     * finger was resting on, pixel for pixel. If this drifts by even a few
     * pixels the menu visibly jumps out of the card at the first frame of its
     * entrance, which is the one frame nobody can un-notice.
     */
    @Test
    fun `at zero the menu covers the plate the finger was on exactly`() {
        val morph = heldMenuMorph(0f, origin, laidOut)
        assertEquals(origin.left, drawn(morph).left, 1e-3f)
        assertEquals(origin.top, drawn(morph).top, 1e-3f)
        assertEquals(origin.right, drawn(morph).right, 1e-3f)
        assertEquals(origin.bottom, drawn(morph).bottom, 1e-3f)
    }

    /**
     * And at 1 it is exactly where the layout put it — no residue of the
     * animation left in the resting transform, which would leave the menu
     * permanently offset by a fraction of a pixel for as long as it was open.
     */
    @Test
    fun `at one the menu is exactly where the layout put it`() {
        val morph = heldMenuMorph(1f, origin, laidOut)
        assertEquals(1f, morph.scaleX, 1e-6f)
        assertEquals(1f, morph.scaleY, 1e-6f)
        assertEquals(0f, morph.translationX, 1e-6f)
        assertEquals(0f, morph.translationY, 1e-6f)
        assertEquals(laidOut, drawn(morph))
    }

    /**
     * The scale is a *rectangle* growing, not a box scaling about its middle.
     * Both axes are independent: this menu grows wider and shorter at the same
     * time, so a single scale factor would open it into the wrong shape.
     */
    @Test
    fun `the two axes scale independently`() {
        val morph = heldMenuMorph(0f, origin, laidOut)
        assertEquals(origin.width / laidOut.width, morph.scaleX, 1e-6f)
        assertEquals(origin.height / laidOut.height, morph.scaleY, 1e-6f)
        assertNotEquals(morph.scaleX, morph.scaleY)
    }

    /**
     * Halfway is halfway — on every one of the four numbers, not roughly. The
     * entrance is meant to read as one surface unfolding, and it only does
     * while the rectangle's corners travel in a straight line between the two
     * rectangles they are travelling between.
     */
    @Test
    fun `halfway is halfway on all four numbers`() {
        val morph = heldMenuMorph(0.5f, origin, laidOut)
        assertEquals(lerp(origin.width / laidOut.width, 1f, 0.5f), morph.scaleX, 1e-6f)
        assertEquals(lerp(origin.height / laidOut.height, 1f, 0.5f), morph.scaleY, 1e-6f)
        assertEquals(lerp(origin.left - laidOut.left, 0f, 0.5f), morph.translationX, 1e-6f)
        assertEquals(lerp(origin.top - laidOut.top, 0f, 0.5f), morph.translationY, 1e-6f)
    }

    /**
     * The offset is the *whole* difference between the two corners, not that
     * difference scaled. Compose applies the translation before the scale, so
     * scaling the offset too would land the menu short of the plate by however
     * much it shrank — quiet, and wrong at every fraction except 0 and 1.
     */
    @Test
    fun `the offset is the whole corner difference and is never itself scaled`() {
        val morph = heldMenuMorph(0f, origin, laidOut)
        assertEquals(origin.left - laidOut.left, morph.translationX, 1e-6f)
        assertEquals(origin.top - laidOut.top, morph.translationY, 1e-6f)
        // Proof that it is not `delta * scale`: had it been, the drawn rect
        // would not land on the plate.
        assertTrue(
            "the offset must be applied outside the scale, or the menu opens short of the card",
            drawn(morph).left == origin.left,
        )
    }

    /** An overshooting spring reaches past 1; it must land on the resting pose, not past it. */
    @Test
    fun `fractions outside zero to one are clamped`() {
        val over = heldMenuMorph(1.4f, origin, laidOut)
        assertEquals(1f, over.scaleX, 1e-6f)
        assertEquals(1f, over.scaleY, 1e-6f)
        assertEquals(0f, over.translationX, 1e-6f)
        assertEquals(0f, over.translationY, 1e-6f)

        val under = heldMenuMorph(-0.3f, origin, laidOut)
        assertEquals(heldMenuMorph(0f, origin, laidOut), under)
    }

    /**
     * A menu measured at no width or height has nothing to scale *from*.
     * Dividing by it would hand the layer a scale of infinity or a NaN, and
     * Compose takes those literally — the surface would fly off screen on the
     * first frame instead of fading in where it is laid out.
     */
    @Test
    fun `a menu with no measured size falls back to the resting transform`() {
        val zeroWidth = Rect(16f, 440f, 16f, 640f)
        val zeroHeight = Rect(16f, 440f, 296f, 440f)
        val resting = HeldMenuMorph(scaleX = 1f, scaleY = 1f, translationX = 0f, translationY = 0f)

        assertEquals(resting, heldMenuMorph(0.5f, origin, zeroWidth))
        assertEquals(resting, heldMenuMorph(0.5f, origin, zeroHeight))
        // Including at the very start of the entrance, where the "origin"
        // branch would otherwise divide by zero as well.
        assertEquals(resting, heldMenuMorph(0f, origin, zeroWidth))
    }

    /**
     * The corners are ends of one animation rather than two unrelated numbers,
     * so both the drawn corner and the animated one are built from the same
     * pair — see `CARD_RADIUS` and `MENU_RADIUS`. This pins the *direction*:
     * the plate's 20dp at the press, tightening to the menu's 16dp at rest.
     * Which is which is a decision; that the menu's radius is the smaller of the
     * two, and that the two are not the same number, is what stops them being
     * able to drift into each other unnoticed.
     */
    @Test
    fun `the corner runs from the plate's radius to the menu's`() {
        val card = 20f * density.density
        val menu = 16f * density.density

        assertEquals(card, heldMenuCornerRadiusPx(0f, card, menu), 1e-4f)
        assertEquals(menu, heldMenuCornerRadiusPx(1f, card, menu), 1e-4f)
        assertEquals(18f * density.density, heldMenuCornerRadiusPx(0.5f, card, menu), 1e-4f)
        assertEquals(16f * density.density, heldMenuCornerRadiusPx(2f, card, menu), 1e-4f)
        assertEquals(20f * density.density, heldMenuCornerRadiusPx(-1f, card, menu), 1e-4f)
        assertTrue("the menu's resting radius is the tighter of the two", menu < card)
    }

    /**
     * The corner radius travels *per frame*, so a shape built once and reused
     * would keep drawing the first outline it ever had — the vendored backdrop
     * engine caches exactly one outline per shape, keyed on the shape's
     * identity. `HeldMenuCornerRadius` is therefore identity-compared, and two
     * different radii must not compare equal: `remember` hands back what it was
     * given last time whenever the new shape compares equal to the old one, and
     * a data-class-style `equals` on the lambda would quietly freeze the morph
     * mid-open with nothing failing.
     */
    @Test
    fun `two different radii never compare equal`() {
        val first = HeldMenuCornerRadius { 20f }
        val second = HeldMenuCornerRadius { 16f }
        assertNotEquals("a new radius must not look like the last one", first, second)

        // …but a shape is equal to itself, or nothing would ever be reused.
        assertEquals(first, first)
        assertEquals(first.hashCode(), first.hashCode())
        assertNotEquals(first.hashCode(), second.hashCode())
    }

    /** The radius is read at draw time, which is what lets it move at all. */
    @Test
    fun `the radius is read when the shape asks for it`() {
        var px = 20f
        val radius = HeldMenuCornerRadius { px }
        val size = Size(100f, 100f)

        assertEquals(20f, radius.toPx(size, density), 1e-4f)
        px = 16f
        assertEquals(16f, radius.toPx(size, density), 1e-4f)
    }

    private fun lerp(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction
}
