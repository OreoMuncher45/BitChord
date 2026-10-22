package com.music.bitchord.ui.player

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

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
