package com.music.bitchord.ui.classipod

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * iPod marquee line: sits still when it fits, otherwise strolls left,
 * holds, and snaps back — exactly like the real thing, which only ever
 * scrolls the line you're on ([scroll] gates that: rows pass their
 * selected state, Now Playing always scrolls).
 */
@Composable
fun PodMarquee(
    text: String,
    color: Color,
    fontSize: TextUnit,
    fontFamily: FontFamily?,
    fontWeight: FontWeight?,
    scroll: Boolean,
    modifier: Modifier = Modifier,
    letterSpacing: TextUnit = 0.sp,
    maxLines: Int = 1,
) {
    val style = TextStyle(
        color = color,
        fontSize = fontSize,
        fontFamily = fontFamily,
        fontWeight = fontWeight,
        letterSpacing = letterSpacing,
    )
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        val maxWpx = with(LocalDensity.current) { maxWidth.toPx() }
        val laid = remember(text, fontSize, fontFamily, fontWeight, letterSpacing) {
            measurer.measure(text, style = style, maxLines = maxLines)
        }
        val overflow = laid.size.width - maxWpx
        if (!scroll || overflow <= 2f) {
            Text(
                text = text,
                style = style,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            val off = remember(text) { Animatable(0f) }
            LaunchedEffect(text) {
                while (true) {
                    delay(900)
                    off.animateTo(
                        -overflow,
                        tween(
                            ((overflow / 60f * 1000).toInt()).coerceIn(900, 6000),
                            easing = LinearEasing,
                        ),
                    )
                    delay(900)
                    off.snapTo(0f)
                }
            }
            Text(
                text = text,
                style = style,
                maxLines = maxLines,
                overflow = TextOverflow.Visible,
                softWrap = false,
                modifier = Modifier.offset { IntOffset(off.value.roundToInt(), 0) },
            )
        }
    }
    @Composable
    fun unused() {
        LocalTextStyle.current
    }
}
