package com.music.bitchord.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Apple Music / iOS style slider: a hairline capsule track with a small
 * circular knob, instead of Material 3's thick pill and vertical bar thumb.
 *
 * This wraps the slot-based [Slider], so dragging, tapping, step snapping,
 * keyboard/RTL handling and progress semantics all stay Material's; only the
 * pixels are ours. The track's filled fraction is derived from the state's
 * coerced value inside the caller's value range rather than drawn by
 * [androidx.compose.material3.SliderDefaults].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AurisSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    steps: Int = 0,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.24f)
    val contentAlpha = if (enabled) 1f else 0.38f
    val activeColor = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha)
    val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha)

    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        enabled = enabled,
        steps = steps,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        thumb = {
            // SliderImpl measures the thumb with the slider's fixed 44dp height
            // and centers it by its measured height, so fill that height and
            // center the 20dp circle inside: a bare size(20.dp) would be
            // coerced into a 20x44 capsule by the incoming constraints.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .shadow(elevation = 2.dp, shape = CircleShape, clip = false)
                        .background(thumbColor, CircleShape),
                )
            }
        },
        track = { state ->
            val start = state.valueRange.start
            val end = state.valueRange.endInclusive
            val span = end - start
            // A zero-width range would divide by zero; pin it to empty instead.
            val fraction = if (span == 0f) {
                0f
            } else {
                ((state.value.coerceIn(start, end) - start) / span).coerceIn(0f, 1f)
            }
            Canvas(modifier = Modifier.fillMaxWidth().height(6.dp)) {
                val radius = size.height / 2f
                drawRoundRect(
                    color = inactiveColor,
                    cornerRadius = CornerRadius(radius, radius),
                )
                val activeWidth = size.width * fraction
                if (activeWidth > 0f) {
                    // RTL mirrors the fill to grow from the right-hand start.
                    val left = if (layoutDirection == LayoutDirection.Rtl) {
                        size.width - activeWidth
                    } else {
                        0f
                    }
                    drawRoundRect(
                        color = activeColor,
                        topLeft = Offset(left, 0f),
                        size = Size(activeWidth, size.height),
                        cornerRadius = CornerRadius(radius, radius),
                    )
                }
            }
        },
    )
}
