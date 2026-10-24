package com.music.bitchord.ui.player

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.music.bitchord.ui.graphics.ColorUtils

/**
 * Ink for the controls laid directly over album artwork.
 *
 * White by default, so every surface outside the expanded player keeps its
 * existing look. The player supplies a light tint of the artwork's own accent
 * here — see [computeNowPlayingPalette].
 */
internal val LocalPlayerInk = staticCompositionLocalOf { Color.White }

/**
 * A soft text shadow, so the ink keeps its edge where it crosses a lighter
 * patch of the artwork. Transparent by default, and transparent in the player
 * whenever the listener has switched glow off.
 */
internal val LocalPlayerInkGlow = staticCompositionLocalOf { Color.Transparent }

/** Light ink needs the dark glow, and the reverse. */
internal fun Color.isLightInk(): Boolean =
    ColorUtils.calculateLuminance(toArgb()) >= 0.5f

/** WCAG's contrast ratio, from two relative luminances. */
internal fun contrastRatio(one: Float, two: Float): Float {
    val lighter = maxOf(one, two)
    val darker = minOf(one, two)
    return (lighter + 0.05f) / (darker + 0.05f)
}
