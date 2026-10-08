package com.music.bitchord.ui.classipod

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import coil3.compose.AsyncImage
import com.music.bitchord.data.NerdStats
import com.music.bitchord.data.discord.discordAudioQualityLine
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt

/**
 * Now Playing, iPod style: big sleeve, title/artist/album, scrub bar with
 * times, quality badge, like toggle. Rotary on this screen is volume (the
 * classic); tap-and-drag the bar to seek.
 *
 * @param qualityLine precomputed from [NerdStats] (same truth as Discord) —
 *   e.g. "Lossless · FLAC · 1411 kbps". Null hides the badge.
 */
@Composable
fun ClassipodNowPlaying(
    song: Song?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    liked: Boolean,
    qualityLine: String?,
    lcd: ClassipodTheme.Lcd,
    wheel: ClassipodWheelState,
    volume: Float,
    onVolume: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onToggleLike: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Rotary = volume here, like the real thing.
    wheel.onStep = { dir ->
        onVolume((volume + dir * 0.04f).coerceIn(0f, 1f))
    }
    wheel.onCenter = { /* center toggles nothing here; transport below */ }
    Column(modifier = modifier.fillMaxSize().background(lcd.bg)) {
        ClassipodBar(title = "Now Playing", lcd = lcd, onBack = onBack)
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AsyncImage(
                model = song?.thumbnailUrl?.artworkAt(480),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(150.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(lcd.bar),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = song?.title ?: "Nothing Playing",
                fontFamily = ClassipodTheme.helveticaBold,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = lcd.text,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song?.artist.orEmpty(),
                fontFamily = ClassipodTheme.helvetica,
                fontSize = 13.sp,
                color = lcd.dim,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            song?.albumName?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    fontFamily = ClassipodTheme.helvetica,
                    fontSize = 12.sp,
                    color = lcd.dim,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            ScrubBar(
                progress = progress,
                lcd = lcd,
                onSeekFraction = { f ->
                    if (durationMs > 0) onSeek((f * durationMs).toLong())
                },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = formatMs(positionMs),
                    fontSize = 11.sp,
                    color = lcd.dim,
                    fontFamily = ClassipodTheme.helvetica,
                )
                Text(
                    text = "-" + formatMs((durationMs - positionMs).coerceAtLeast(0)),
                    fontSize = 11.sp,
                    color = lcd.dim,
                    fontFamily = ClassipodTheme.helvetica,
                )
            }
            Spacer(Modifier.height(6.dp))
            // Quality badge: small caps under the bar, Apple-style.
            if (qualityLine != null) {
                Text(
                    text = qualityLine.uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    color = lcd.dim,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
            }
            // Like toggle + volume readout share the foot row.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(lcd.bar)
                        .clickable(onClick = onToggleLike)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(
                            imageVector = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = null,
                            tint = if (liked) androidx.compose.ui.graphics.Color(0xFFFA2D48) else lcd.dim,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (liked) "Loved" else "Love",
                            fontSize = 12.sp,
                            color = lcd.text,
                            fontFamily = ClassipodTheme.helvetica,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Vol ${(volume * 100).toInt()}%",
                    fontSize = 11.sp,
                    color = lcd.dim,
                    fontFamily = ClassipodTheme.helvetica,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = if (isPlaying) "❚❚ Playing" else "❚❚ Paused",
                fontSize = 11.sp,
                color = lcd.dim,
                fontFamily = ClassipodTheme.helvetica,
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ScrubBar(
    progress: Float,
    lcd: ClassipodTheme.Lcd,
    onSeekFraction: (Float) -> Unit,
) {
    var dragging by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var dragFrac by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val shown = if (dragging) dragFrac else progress
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .pointerInputCompat(
                onDown = { frac ->
                    dragging = true
                    dragFrac = frac
                },
                onMove = { frac -> dragFrac = frac },
                onUp = {
                    dragging = false
                    onSeekFraction(dragFrac)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val maxW = maxWidth
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(lcd.bar),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = shown)
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(lcd.text.copy(alpha = 0.85f)),
            )
        }
        // The round knob, pure iPod, riding the fill.
        val density = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .size(11.dp)
                .align(Alignment.CenterStart)
                .offset {
                    val knobPx = with(density) { 11.dp.toPx() }
                    val travel = (maxW.toPx() - knobPx).coerceAtLeast(0f)
                    androidx.compose.ui.unit.IntOffset((shown * travel).roundToInt(), 0)
                },
        ) {
            drawCircle(color = lcd.text, radius = size.minDimension / 2.4f)
        }
    }
}

private fun formatMs(ms: Long): String {
    val s = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * Press-drag-release fraction tracking on a box. Kept tiny on purpose: the
 * wheel owns fine scrubbing elsewhere; touch gets the direct grab.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.pointerInputCompat(
    onDown: (Float) -> Unit,
    onMove: (Float) -> Unit,
    onUp: () -> Unit,
): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        var w = 1f
        detectDragGestures(
            onDragStart = { offset ->
                w = (size.width.toFloat()).coerceAtLeast(1f)
                onDown((offset.x / w).coerceIn(0f, 1f))
            },
            onDrag = { change, _ ->
                onMove((change.position.x / w).coerceIn(0f, 1f))
                change.consume()
            },
            onDragEnd = { onUp() },
            onDragCancel = { onUp() },
        )
    },
)
