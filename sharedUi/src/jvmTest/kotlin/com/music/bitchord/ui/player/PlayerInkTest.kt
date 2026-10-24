package com.music.bitchord.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.music.bitchord.ui.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

class PlayerInkTest {
    private fun contrastOf(ink: Color, backdrop: Float) =
        contrastRatio(ColorUtils.calculateLuminance(ink.toArgb()).toFloat(), backdrop)

    private fun saturationOf(color: Color) =
        FloatArray(3).also { ColorUtils.colorToHSL(color.toArgb(), it) }[1]

    private fun hueOf(color: Color) =
        FloatArray(3).also { ColorUtils.colorToHSL(color.toArgb(), it) }[0]

    private fun hueGap(one: Float, two: Float): Float {
        val gap = abs(one - two)
        return min(gap, 360f - gap)
    }

    @Test
    fun everyArtworkHueClearsTheContrastBarOnEveryBackdrop() {
        val backdrops = listOf(0.01f, 0.05f, 0.10f, 0.18f, 0.25f, 0.40f, 0.55f, 0.70f, 0.90f)
        val hues = listOf(0f, 30f, 60f, 120f, 210f, 280f, 330f)

        for (backdrop in backdrops) {
            for (hue in hues) {
                val average = Color(
                    ColorUtils.HSLToColor(floatArrayOf(hue, 0.90f, 0.50f)),
                )
                val ink = artworkAverageInk(average, backdrop)
                assertTrue(
                    "hue $hue on backdrop $backdrop got ${contrastOf(ink, backdrop)}:1",
                    contrastOf(ink, backdrop) >= MIN_INK_CONTRAST - 0.001f,
                )
            }
        }
    }

    @Test
    fun aFlatOrangeSleeveGetsDeepOrangeInkRatherThanPaleOrangeOnOrange() {
        val orange = Color(0xFFE65100)
        val ink = artworkAverageInk(orange, controlLuminance = 0.31f)

        assertTrue(contrastOf(ink, 0.31f) >= MIN_INK_CONTRAST - 0.001f)
        assertTrue("ink stayed too light", ColorUtils.calculateLuminance(ink.toArgb()) < 0.1)
        assertTrue("the artwork's hue was thrown away", hueGap(hueOf(ink), hueOf(orange)) < 10f)
    }

    @Test
    fun aWhiteSleeveGetsNearBlackInk() {
        val ink = artworkAverageInk(Color.White, controlLuminance = 0.85f)

        assertTrue(contrastOf(ink, 0.85f) >= MIN_INK_CONTRAST - 0.001f)
        assertTrue(ColorUtils.calculateLuminance(ink.toArgb()) < 0.1)
    }

    @Test
    fun aDarkSleeveGetsInkLiftedUntilItCanBeRead() {
        val sleeve = Color(0xFF101820)
        val ink = artworkAverageInk(sleeve, controlLuminance = 0.01f)

        assertTrue(contrastOf(ink, 0.01f) >= MIN_INK_CONTRAST - 0.001f)
        assertTrue(
            "ink should move lighter than the sleeve, not darker",
            ColorUtils.calculateLuminance(ink.toArgb()) >
                ColorUtils.calculateLuminance(sleeve.toArgb()),
        )
    }

    @Test
    fun aSingleColourSleeveBorrowsItsMinorityColourWhenThatColourCanBeRead() {
        val orange = Color(0xFFE65100)
        val blue = Color(0xFF1565C0)
        val ink = artworkAverageInk(
            average = orange,
            controlLuminance = 0.31f,
            leadingShare = 0.80f,
            secondary = blue,
        )

        assertTrue(contrastOf(ink, 0.31f) >= MIN_INK_CONTRAST - 0.001f)
        assertTrue("the minority hue was not kept", hueGap(hueOf(ink), hueOf(blue)) < 12f)
    }

    @Test
    fun aSingleColourSleeveWithNoSecondHueFallsBackToBlackAndWhite() {
        val ink = artworkAverageInk(
            average = Color(0xFF808080),
            controlLuminance = 0.18f,
            leadingShare = 0.90f,
            secondary = null,
        )

        assertTrue(contrastOf(ink, 0.18f) >= MIN_INK_CONTRAST - 0.001f)
        assertTrue("a neutral sleeve should not invent a hue", saturationOf(ink) < 0.10f)
    }

    @Test
    fun noArtworkAtAllKeepsLightInk() {
        val ink = artworkAverageInk(average = null, controlLuminance = null)

        assertTrue(ColorUtils.calculateLuminance(ink.toArgb()) > 0.8)
    }

    @Test
    fun aMidToneBackdropStaysReadableEvenThoughNeitherTintExtremeIsComfortable() {
        // Mid grey is the worst case: black and white both sit near 4.5:1.
        val ink = artworkAverageInk(Color(0xFF777777), controlLuminance = 0.18f)

        assertTrue(contrastOf(ink, 0.18f) >= MIN_INK_CONTRAST - 0.001f)
    }

    @Test
    fun theContrastBarIsTheWcagNormalTextRatio() {
        assertEquals(4.5f, MIN_INK_CONTRAST, 0f)
    }
}
