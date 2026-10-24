package com.music.bitchord.ui.theme

import androidx.compose.ui.graphics.toArgb
import com.music.bitchord.ui.graphics.ColorUtils
import com.music.bitchord.ui.graphics.Swatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkPaletteTest {
    @Test
    fun grayscaleDoesNotAcquireTheDefaultRedHue() {
        assertEquals(0f, adaptedArtworkSaturation(0f, minimum = 0.20f, maximum = 0.62f), 0f)
        assertEquals(0f, adaptedArtworkSaturation(0f, minimum = 0.55f, maximum = 1f), 0f)
    }

    @Test
    fun nearlyNeutralArtworkKeepsItsSubtleSaturation() {
        assertEquals(0.08f, adaptedArtworkSaturation(0.08f, minimum = 0.20f, maximum = 0.62f), 0f)
    }

    @Test
    fun genuinelyChromaticArtworkIsStillStrengthenedAndCapped() {
        assertEquals(0.55f, adaptedArtworkSaturation(0.25f, minimum = 0.55f, maximum = 1f), 0f)
        assertEquals(0.62f, adaptedArtworkSaturation(0.90f, minimum = 0.20f, maximum = 0.62f), 0f)
    }

    @Test
    fun aSingleColourCoverMovesThePageToBlackAndWhiteAndBorrowsItsOtherColour() {
        val tint = chooseArtworkTint(
            listOf(
                Swatch(RED, 70),
                Swatch(BLUE, 20),
                Swatch(WHITE, 10),
            ),
        )

        assertNotNull(tint)
        assertTrue(tint!!.leadingShare >= SINGLE_COLOUR_SHARE)
        assertEquals(0f, saturationOf(tint.page), 0.01f)
        assertNotNull(tint.secondary)
        assertEquals(210f, hueOf(tint.secondary!!), 12f)
        assertEquals(tint.secondary, tint.accent)
    }

    @Test
    fun aSingleColourCoverWithNoSecondColourStaysMonochrome() {
        val tint = chooseArtworkTint(
            listOf(
                Swatch(RED, 70),
                Swatch(WHITE, 20),
                Swatch(BLACK, 10),
            ),
        )

        assertNotNull(tint)
        assertNull(tint!!.secondary)
        assertEquals(tint.page, tint.accent)
        assertEquals(0f, saturationOf(tint.page), 0.01f)
    }

    @Test
    fun aSleeveWithNoRunawayColourKeepsItsDominantTint() {
        val tint = chooseArtworkTint(
            listOf(
                Swatch(RED, 40),
                Swatch(BLUE, 35),
                Swatch(GREEN, 25),
            ),
        )

        assertNotNull(tint)
        assertTrue(tint!!.leadingShare < SINGLE_COLOUR_SHARE)
        assertEquals(RED, tint.page.toArgb())
        assertNull(tint.secondary)
    }

    private fun saturationOf(color: androidx.compose.ui.graphics.Color): Float =
        FloatArray(3).also { ColorUtils.colorToHSL(color.toArgb(), it) }[1]

    private fun hueOf(color: androidx.compose.ui.graphics.Color): Float =
        FloatArray(3).also { ColorUtils.colorToHSL(color.toArgb(), it) }[0]

    private companion object {
        const val RED = 0xFFD32F2F.toInt()
        const val BLUE = 0xFF1565C0.toInt()
        const val GREEN = 0xFF2E7D32.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}
