package com.music.bitchord.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.pow

/**
 * The six covers the old colour engine got wrong or nearly wrong. Each one is
 * scored at the dimmest text role and the dimmest icon role, over the lightest
 * background stop — the worst place on the screen.
 */
class NowPlayingPaletteTest {
    private data class Cover(val name: String, val pixels: List<Int>)

    private fun cover(name: String, vararg shares: Pair<Int, Int>): Cover =
        Cover(name, shares.flatMap { (color, count) -> List(count) { color } })

    private fun argb(value: Long) = value.toInt()

    private val covers = listOf(
        cover(
            "dark blue/purple",
            argb(0xFF141433) to 600,
            argb(0xFF2B1B5A) to 300,
            argb(0xFF4A3B9E) to 100,
        ),
        cover(
            "white/grey with red accent",
            argb(0xFFF2F2F2) to 780,
            argb(0xFFBDBDBD) to 120,
            argb(0xFFD32F2F) to 100,
        ),
        cover("all white", argb(0xFFFFFFFF) to 1000),
        cover("all black", argb(0xFF000000) to 1000),
        cover("bright yellow", argb(0xFFFFEB3B) to 1000),
        cover("pure red", argb(0xFFE53935) to 1000),
    )

    @Test
    fun everyCoverPassesTextAndIconContrast() {
        covers.forEach { cover ->
            val palette = computeNowPlayingPalette(cover.pixels)
            val text = minTextContrast(palette)
            val icon = minIconContrast(palette)
            println(
                String.format(
                    Locale.US,
                    "%-26s text %.2f:1  icons %.2f:1  bg cap %.2f  luminance limit %.3f",
                    cover.name, text, icon,
                    palette.backgroundLightnessCap, palette.backgroundLuminanceLimit,
                ),
            )
            assertTrue(
                "${cover.name}: text contrast $text:1 is below 4.5:1",
                text >= 4.5f,
            )
            assertTrue(
                "${cover.name}: icon contrast $icon:1 is below 3:1",
                icon >= 3f,
            )
        }
    }

    @Test
    fun neutralSleevesShareOneNeutralInk() {
        val white = computeNowPlayingPalette(covers.first { it.name == "all white" }.pixels)
        val black = computeNowPlayingPalette(covers.first { it.name == "all black" }.pixels)

        assertEquals(white.fg, black.fg)
        assertEquals(white.fg.red, white.fg.green, 0.001f)
        assertEquals(white.fg.green, white.fg.blue, 0.001f)
        // Once the accent is gone the backdrop loses its tint, not just its chroma.
        white.backgroundStops.forEach { stop ->
            assertEquals(stop.red, stop.green, 0.01f)
            assertEquals(stop.green, stop.blue, 0.01f)
        }
    }

    @Test
    fun brightCoversKeepAMidTonedBackdrop() {
        // The old check darkened every stop until the lightest passed, which
        // turned a pale cover's blur almost black. Only individual cells above
        // the luminance ceiling may be toned down now.
        listOf("white/grey with red accent", "all white", "bright yellow", "pure red").forEach { name ->
            val palette = computeNowPlayingPalette(covers.first { it.name == name }.pixels)
            assertEquals(MAX_BACKGROUND_L, palette.backgroundLightnessCap, 0f)
            val brightest = palette.backgroundStops.maxOf { luminance(it) }
            println(String.format(Locale.US, "%-26s brightest stop Y %.3f", name, brightest))
            assertTrue("$name: backdrop crushed to $brightest luminance", brightest > 0.04f)
        }
    }

    @Test
    fun chromaticSleevesKeepTheirHueInTheInk() {
        fun fg(name: String) = computeNowPlayingPalette(covers.first { it.name == name }.pixels).fg

        val red = fg("pure red")
        assertTrue("red accent should tint the ink red", red.red > red.green && red.red > red.blue)

        val yellow = fg("bright yellow")
        assertTrue(
            "yellow accent should tint the ink yellow",
            yellow.red > yellow.blue && yellow.green > yellow.blue,
        )
    }

    private fun luminance(color: Color): Float {
        val argb = color.toArgb()
        fun linear(shift: Int): Float {
            val channel = ((argb shr shift) and 0xFF) / 255f
            return if (channel <= 0.04045f) channel / 12.92f
            else ((channel + 0.055f) / 1.055f).pow(2.4f)
        }
        return 0.2126f * linear(16) + 0.7152f * linear(8) + 0.0722f * linear(0)
    }
}
