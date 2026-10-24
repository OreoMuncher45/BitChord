package com.music.bitchord.ui.player

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.music.bitchord.ui.graphics.ColorUtils

/**
 * Ink for the controls laid directly over album artwork.
 *
 * White by default, so every surface outside the expanded player keeps its
 * existing look. The player swaps this to near-black when the sleeve's lower
 * half is bright enough that white glyphs would wash out.
 */
internal val LocalPlayerInk = staticCompositionLocalOf { Color.White }

/**
 * A soft text shadow, so dark ink stays legible where it crosses a dark patch
 * of an otherwise bright sleeve, and white ink keeps its edge over a bright one.
 *
 * Transparent by default, so nothing outside the expanded player changes.
 */
internal val LocalPlayerInkGlow = staticCompositionLocalOf { Color.Transparent }

/**
 * The chrome colour that stands in for "Use artwork".
 *
 * The backdrop is a blur of the sleeve, so its average is the colour of that
 * blur. Chrome keeps that same hue — the record's own colour, not its
 * complement — and lifts it until the glyphs clear the backdrop. The lift is
 * what makes the tint visible rather than a near-copy of the blur behind it;
 * keeping the hue is what makes it read as *this* cover's colour.
 *
 * "Brighter" only works one way up the scale. A dark sleeve gets a lightened
 * version of its average; a bright one gets the same colour deepened instead,
 * because nothing lighter than a bright backdrop is legible on it. Sleeves
 * with no hue keep the existing barely-warm white or near-black rather than
 * inventing a colour that isn't there.
 */
internal fun artworkAverageInk(average: Color?, controlLuminance: Float?): Color {
    val brightBackdrop = (controlLuminance ?: 0f) >= 0.55f
    if (average == null) return if (brightBackdrop) Color(0xFF0B0B0C) else Color.White

    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(average.toArgb(), it) }
    if (hsl[1] < MONOCHROME_SATURATION) {
        return if (brightBackdrop) Color(0xFF1B1513) else Color(0xFFFFF6F1)
    }

    val hue = hsl[0]
    // A small floor: a muted average still reads as a tint, not as grey.
    val saturation = hsl[1].coerceIn(0.22f, 0.80f)
    val lightness = if (brightBackdrop) {
        (hsl[2] - 0.30f).coerceIn(0.18f, 0.36f)
    } else {
        (hsl[2] + 0.28f).coerceIn(0.60f, 0.88f)
    }
    return Color(ColorUtils.HSLToColor(floatArrayOf(hue, saturation, lightness)))
}

/** Light ink needs the dark glow, and the reverse. */
internal fun Color.isLightInk(): Boolean =
    ColorUtils.calculateLuminance(toArgb()) >= 0.5f

/** Below this a sleeve has no hue worth tinting. */
private const val MONOCHROME_SATURATION = 0.10f
