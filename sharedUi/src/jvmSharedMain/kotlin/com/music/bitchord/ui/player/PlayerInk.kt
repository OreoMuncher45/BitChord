package com.music.bitchord.ui.player

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
 * A soft halo drawn behind player glyphs and text, so dark ink stays legible
 * where it crosses a dark patch of an otherwise bright sleeve — and white ink
 * keeps its edge over a bright patch of a dark one.
 *
 * Transparent by default, so nothing outside the expanded player changes.
 */
internal val LocalPlayerInkGlow = staticCompositionLocalOf { Color.Transparent }

/**
 * [LocalPlayerInkGlow], painted as a radial falloff behind the node it is put
 * on. Meant for glyph-sized boxes: the halo reaches a little past the icon's
 * own bounds and fades to nothing.
 */
internal fun Modifier.playerGlow(glow: Color): Modifier =
    if (glow.alpha <= 0f) {
        this
    } else {
        drawBehind {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glow, Color.Transparent),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = size.maxDimension * 0.85f,
                ),
            )
        }
    }
