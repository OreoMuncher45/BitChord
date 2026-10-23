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
 * blur. Chrome is painted in the *complement* of that colour — warm over a cool
 * record, cool over a warm one — which stays recognisably about this cover
 * while reading as a deliberate tint rather than the same black or white on
 * every screen. Lightness is chosen against the measured lower-band luminance
 * so the ink still clears the backdrop; for sleeves with no hue to complement,
 * a barely-warm white or near-black keeps the same softness without inventing
 * a colour that isn't there.
 */
internal fun artworkComplementInk(average: Color?, controlLuminance: Float?): Color {
    val brightBackdrop = (controlLuminance ?: 0f) >= 0.55f
    if (average == null) return if (brightBackdrop) Color(0xFF0B0B0C) else Color.White

    val hsl = FloatArray(3).also { ColorUtils.colorToHSL(average.toArgb(), it) }
    if (hsl[1] < MONOCHROME_SATURATION) {
        return if (brightBackdrop) Color(0xFF1B1513) else Color(0xFFFFF6F1)
    }

    val hue = (hsl[0] + 180f) % 360f
    val saturation = hsl[1].coerceIn(0.34f, 0.60f)
    val lightness = if (brightBackdrop) 0.30f else 0.84f
    return Color(ColorUtils.HSLToColor(floatArrayOf(hue, saturation, lightness)))
}

/** Light ink needs the dark glow, and the reverse. */
internal fun Color.isLightInk(): Boolean =
    ColorUtils.calculateLuminance(toArgb()) >= 0.5f

/** Below this a sleeve has no hue worth complementing. */
private const val MONOCHROME_SATURATION = 0.10f
