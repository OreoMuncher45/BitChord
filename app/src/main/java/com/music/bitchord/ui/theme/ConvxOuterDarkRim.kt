/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx: ui/utils/ModifierExt.kt -> outerDarkRim.
 * Outer dark rim for glass surfaces (Convx ui/utils/ModifierExt.kt).
 */

package com.music.bitchord.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

fun Modifier.outerDarkRim(
    shape: Shape,
    width: Dp = 1.6.dp,
    alpha: Float = 0.32f,
    /** Light rim's angle, degrees — the dark axis is derived from it. */
    angle: Float = 45f,
    /**
     * Draw only the inner half of the stroke, inset so clipping preserves the outline width.
     */
    insideOnly: Boolean = false,
): Modifier = drawWithCache {
    val inset = if (insideOnly) width.toPx() / 4f else 0f
    val outline = shape.createOutline(
        androidx.compose.ui.geometry.Size(size.width - inset * 2f, size.height - inset * 2f),
        layoutDirection,
        this,
    )
    val stroke = Stroke(width = if (insideOnly) width.toPx() / 2f else width.toPx())
    // One gradient per diagonal axis (canvas coords, y down — same convention
    // as the highlight shader's cos/sin normal). Each is black at both ends
    // fading out at its middle, so the two passes swell on opposite sides.
    val center = Offset(size.width / 2f, size.height / 2f)
    fun flankBrush(axisDeg: Float): Brush {
        val a = axisDeg * (PI / 180f).toFloat()
        val dir = Offset(cos(a), sin(a))
        // Matched so the shape's own corners land on the black ends.
        val reach = dir * (abs(dir.x) * size.width + abs(dir.y) * size.height) / 2f
        return Brush.linearGradient(
            0f to Color.Black.copy(alpha = alpha),
            0.5f to Color.Transparent,
            1f to Color.Black.copy(alpha = alpha),
            start = center - reach,
            end = center + reach,
        )
    }
    val firstBrush = flankBrush(angle)
    val secondBrush = flankBrush(angle + 90f)
    onDrawWithContent {
        drawContent()
        translate(inset, inset) {
            drawOutline(outline = outline, brush = firstBrush, style = stroke)
            drawOutline(outline = outline, brush = secondBrush, style = stroke)
        }
    }
}
/**
 * The radius an inner element needs for its curve to stay concentric with the [outer]
 * one it sits [inset] inside. Apple's rule, and the reason a card inside a sheet looks
 * wrong at the same radius as the sheet: parallel curves must differ by the gap between
 * them. Floors at 4dp so a large inset cannot square the corners off entirely.
 */
