/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.bitchord.ui.components.foldingtabbar

import androidx.compose.animation.core.Easing
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.backdrop.catalog.utils.InteractiveHighlight
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

internal class SpringEasing(private val zeta: Float, private val omegaT: Float = 7.8f) : Easing {
    private val root = kotlin.math.sqrt(1f - zeta * zeta)
    private val end = raw(1f)

    private fun raw(t: Float): Float {
        val wd = omegaT * root * t
        return 1f - kotlin.math.exp(-zeta * omegaT * t) *
            (kotlin.math.cos(wd) + zeta / root * kotlin.math.sin(wd))
    }

    override fun transform(fraction: Float): Float =
        if (fraction >= 1f) 1f else raw(fraction) + (1f - end) * fraction
}

internal fun Modifier.landingEcho(): Modifier = this

internal fun Modifier.pillShadow(shape: Shape, elevation: Dp): Modifier = this

internal fun Modifier.clipWhilePressed(
    glow: InteractiveHighlight,
    shape: Shape,
): Modifier = graphicsLayer {
    this.shape = shape
    clip = glow.pressProgress > 0f
}

internal fun elasticStretchLayerBlock(
    interactiveHighlight: InteractiveHighlight,
    magnitude: Float = 1f,
): GraphicsLayerScope.() -> Unit = {
    val progress = interactiveHighlight.pressProgress
    val scale = androidx.compose.ui.util.lerp(
        1f,
        1f + magnitude * 4f.dp.toPx() / size.height,
        progress,
    )

    val maxOffset = size.minDimension
    val initialDerivative = 0.05f
    val offset = interactiveHighlight.offset
    translationX = magnitude * maxOffset * tanh(initialDerivative * offset.x / maxOffset)
    translationY = magnitude * maxOffset * tanh(initialDerivative * offset.y / maxOffset)

    val maxDragScale = magnitude * 4f.dp.toPx() / size.height
    val offsetAngle = atan2(offset.y, offset.x)
    scaleX = scale +
        maxDragScale * abs(cos(offsetAngle) * offset.x / size.maxDimension) *
        (size.width / size.height).coerceAtMost(1f)
    scaleY = scale +
        maxDragScale * abs(sin(offsetAngle) * offset.y / size.maxDimension) *
        (size.height / size.width).coerceAtMost(1f)
}
