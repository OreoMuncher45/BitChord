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
