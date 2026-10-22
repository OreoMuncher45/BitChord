package com.music.bitchord.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.tan

/**
 * Corner softness for the transport glyphs, 0f (sharp) to 1f (soft).
 *
 * Passed down from the player so the app can expose it as a setting; the
 * desktop client takes the default.
 */
internal const val DEFAULT_TRANSPORT_SMOOTHNESS = 0.55f

/** The three glyphs that used to be fixed vectors or vendored drawables. */
internal enum class TransportShape { PLAY, PAUSE, PREVIOUS, NEXT }

/**
 * A transport glyph built from filled polygons with circular corners.
 *
 * The play triangle and the skip arrows were the pointiest things on the
 * player, and their points could not move because they were an [ImageVector]
 * and two drawable resources. Drawing them here lets one setting round every
 * corner by the same optical amount, and keeps the skip pair in the same
 * construction as the play/pause pair instead of two vendors' idea of an edge.
 */
@Composable
internal fun TransportShapeGlyph(
    shape: TransportShape,
    tint: Color,
    contentDescription: String,
    smoothness: Float = DEFAULT_TRANSPORT_SMOOTHNESS,
    modifier: Modifier = Modifier,
) {
    val softness = smoothness.coerceIn(0f, 1f)
    Canvas(
        modifier = modifier.semantics {
            this.contentDescription = contentDescription
        },
    ) {
        // Everything below is authored on the 24-unit grid the rest of the
        // player icons use, then scaled once so a 38dp play button and a 42dp
        // skip button share the same proportions.
        val unit = size.minDimension / 24f
        val corner = (0.15f + (maxCorner(shape) - 0.15f) * softness) * unit
        val path = Path()

        when (shape) {
            TransportShape.PLAY -> path.appendRoundedPolygon(
                points = listOf(
                    Offset(4.0f, 1.2f),
                    Offset(21.4f, 12f),
                    Offset(4.0f, 22.8f),
                ),
                radius = corner,
                unit = unit,
            )

            TransportShape.PAUSE -> {
                path.appendRoundedPolygon(
                    points = listOf(
                        Offset(2.8f, 0.3f),
                        Offset(10.2f, 0.3f),
                        Offset(10.2f, 23.7f),
                        Offset(2.8f, 23.7f),
                    ),
                    radius = corner,
                    unit = unit,
                )
                path.appendRoundedPolygon(
                    points = listOf(
                        Offset(13.8f, 0.3f),
                        Offset(21.2f, 0.3f),
                        Offset(21.2f, 23.7f),
                        Offset(13.8f, 23.7f),
                    ),
                    radius = corner,
                    unit = unit,
                )
            }

            TransportShape.NEXT -> {
                path.appendSkipPair(corner, unit, mirrored = false)
            }

            TransportShape.PREVIOUS -> {
                path.appendSkipPair(corner, unit, mirrored = true)
            }
        }

        drawPath(path, tint)
    }
}

/**
 * The widest radius a shape can take without the corner arcs eating each
 * other. Derived from the narrowest edge and the sharpest angle at each size,
 * with a little headroom: a slider at 100% should still read as a triangle or
 * a bar, not as a circle or a pill.
 */
private fun maxCorner(shape: TransportShape): Float = when (shape) {
    TransportShape.PLAY -> 4.2f
    TransportShape.PAUSE -> 3.0f
    TransportShape.PREVIOUS, TransportShape.NEXT -> 3.0f
}

/** Two solid arrows, the second offset; [mirrored] points them left. */
private fun Path.appendSkipPair(radius: Float, unit: Float, mirrored: Boolean) {
    val first = listOf(
        Offset(2.0f, 4.9f),
        Offset(11.5f, 12f),
        Offset(2.0f, 19.1f),
    )
    val second = listOf(
        Offset(12.5f, 4.9f),
        Offset(22.0f, 12f),
        Offset(12.5f, 19.1f),
    )
    fun mirror(points: List<Offset>): List<Offset> = if (!mirrored) {
        points
    } else {
        points.map { Offset(24f - it.x, it.y) }.reversed()
    }
    appendRoundedPolygon(mirror(first), radius, unit)
    appendRoundedPolygon(mirror(second), radius, unit)
}

/**
 * Appends one closed, convex polygon whose corners are circular arcs of
 * [radius].
 *
 * A triangle or bar with a stroke only *looks* slightly rounded at its points;
 * the radius there is half the stroke width and cannot be raised without
 * making the whole glyph fatter. This walks the vertices instead, pulls back
 * along each edge by the tangent distance, and joins the two tangent points
 * with an arc centred on the angle bisector. The radius is therefore a real
 * radius in the same units as the path.
 */
private fun Path.appendRoundedPolygon(
    points: List<Offset>,
    radius: Float,
    unit: Float,
) {
    if (points.size < 3) return
    val scaled = points.map { Offset(it.x * unit, it.y * unit) }
    val r = radius.coerceAtLeast(0.01f)

    for (i in scaled.indices) {
        val previous = scaled[(i + scaled.size - 1) % scaled.size]
        val vertex = scaled[i]
        val next = scaled[(i + 1) % scaled.size]
        val toPrevious = (previous - vertex).unitVector()
        val toNext = (next - vertex).unitVector()
        val angle = acos(
            (toPrevious.x * toNext.x + toPrevious.y * toNext.y).coerceIn(-1f, 1f),
        )
        if (angle < 0.01f || abs(angle - PI.toFloat()) < 0.01f) {
            // Degenerate corner; there is no arc to draw.
            if (i == 0) moveTo(vertex.x, vertex.y) else lineTo(vertex.x, vertex.y)
            continue
        }

        // Pull back from the corner along both edges. Clamp so a radius close
        // to the short edge's half cannot make adjacent corners overlap.
        val half = angle / 2f
        val shortestEdge = minOf(
            (previous - vertex).getDistance(),
            (next - vertex).getDistance(),
        )
        val tangent = (r / tan(half)).coerceAtMost(shortestEdge * 0.45f)
        val effectiveRadius = tangent * tan(half)
        val start = vertex + toPrevious * tangent
        val end = vertex + toNext * tangent
        val bisector = (toPrevious + toNext).unitVector()
        val centre = vertex + bisector * (effectiveRadius / sin(half))

        if (i == 0) moveTo(start.x, start.y) else lineTo(start.x, start.y)

        val startAngle = Math.toDegrees(
            atan2(start.y - centre.y, start.x - centre.x).toDouble(),
        ).toFloat()
        val endAngle = Math.toDegrees(
            atan2(end.y - centre.y, end.x - centre.x).toDouble(),
        ).toFloat()
        var sweep = endAngle - startAngle
        while (sweep > 180f) sweep -= 360f
        while (sweep < -180f) sweep += 360f

        arcTo(
            rect = Rect(
                offset = Offset(centre.x - effectiveRadius, centre.y - effectiveRadius),
                size = Size(effectiveRadius * 2f, effectiveRadius * 2f),
            ),
            startAngleDegrees = startAngle,
            sweepAngleDegrees = sweep,
            forceMoveTo = false,
        )
    }
    close()
}

private fun Offset.unitVector(): Offset {
    val length = getDistance()
    return if (length <= 0.0001f) Offset.Zero else this / length
}
