package com.music.bitchord.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

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
                    "%-26s text %.2f:1  icons %.2f:1  bg cap %.2f",
                    cover.name, text, icon, palette.backgroundLightnessCap,
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
}
