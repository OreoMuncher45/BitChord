package com.music.bitchord.ui.classipod

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import kotlin.math.roundToInt

/**
 * Now Playing, 1-for-1 from Classipod's NowPlayingWidget: artwork left,
 * title / artist / album / stars / "N of M" right, shuffle+repeat glyphs
 * top-right, blue gradient progress bar with elapsed and -remaining.
 *
 * BitChord additions the brief demands, kept small: a quality badge under
 * the bar (same truth as Discord/nerd stats) and tap-the-stars to love.
 * Rotary on this screen is volume, like the real thing.
 */
@Composable
fun ClassipodNowPlaying(
    song: Song?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    queuePosition: Int,
    queueTotal: Int,
    liked: Boolean,
    qualityLine: String?,
    shuffleOn: Boolean,
    repeatOne: Boolean,
    lcd: ClassipodTheme.Lcd,
    wheel: ClassipodWheelState,
    volume: Float,
    onVolume: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onToggleLike: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Rotary = volume here, like the real thing.
    wheel.onStep = { dir ->
        onVolume((volume + dir * 0.04f).coerceIn(0f, 1f))
    }
    wheel.onCenter = { /* transport lives on the wheel zones */ }
    Column(modifier = modifier.fillMaxSize().background(lcd.bg)) {
        ClassipodBar(
            title = "Now Playing",
            lcd = lcd,
            onBack = onBack,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Shuffle, null,
                        tint = if (shuffleOn) Color(0xFF1584D3) else lcd.dim,
                        modifier = Modifier.size(17.dp).clickable(onClick = onToggleShuffle),
                    )
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        if (repeatOne) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        null,
                        tint = if (repeatOne) Color(0xFF1584D3) else lcd.dim,
                        modifier = Modifier.size(17.dp).clickable(onClick = onCycleRepeat),
                    )
                    Spacer(Modifier.width(4.dp))
                }
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            AsyncImage(
                model = song?.thumbnailUrl?.artworkAt(480),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 132.dp, height = 176.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(lcd.bar),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = song?.title ?: "Nothing Playing",
                    fontFamily = ClassipodTheme.helveticaBold,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = lcd.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = song?.artist.orEmpty(),
                    fontFamily = ClassipodTheme.helveticaBold,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = lcd.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = song?.albumName.orEmpty(),
                    fontFamily = ClassipodTheme.helveticaBold,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = lcd.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                // Stars: liked = 5, tap toggles the heart behind them.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(onClick = onToggleLike),
                ) {
                    repeat(5) {
                        Text(
                            text = if (liked) "★" else "☆",
                            fontSize = 14.sp,
                            color = ClassipodTheme.RATING_GRAY,
                        )
                        Spacer(Modifier.width(2.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (queueTotal > 0) {
                    Text(
                        text = "$queuePosition of $queueTotal",
                        fontFamily = ClassipodTheme.helveticaBold,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = lcd.text,
                    )
                }
                Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(10.dp))
        // Progress: elapsed left, blue gradient fill, -remaining right.
        val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        var dragging by remember { mutableStateOf(false) }
        var dragFrac by remember { mutableFloatStateOf(0f) }
        val shown = if (dragging) dragFrac else progress
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatMs(positionMs),
                fontSize = 11.sp,
                color = lcd.dim,
                fontFamily = ClassipodTheme.helvetica,
                modifier = Modifier.width(38.dp),
            )
            BoxWithConstraints(modifier = Modifier.weight(1f)) {
                val maxW = maxWidth
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .pointerInput(Unit) {
                            var w = 1f
                            detectDragGestures(
                                onDragStart = { offset ->
                                    w = size.width.toFloat().coerceAtLeast(1f)
                                    dragging = true
                                    dragFrac = (offset.x / w).coerceIn(0f, 1f)
                                },
                                onDrag = { change, _ ->
                                    dragFrac = (change.position.x / w).coerceIn(0f, 1f)
                                    change.consume()
                                },
                                onDragEnd = {
                                    dragging = false
                                    if (durationMs > 0) onSeek((dragFrac * durationMs).toLong())
                                },
                                onDragCancel = { dragging = false },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(9.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(lcd.bar),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction = shown)
                                .height(9.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(
                                    Brush.verticalGradient(ClassipodTheme.PROGRESS_FILL),
                                ),
                        )
                    }
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    Box(
                        modifier = Modifier
                            .size(13.dp)
                            .align(Alignment.CenterStart)
                            .offset {
                                val knobPx = with(density) { 13.dp.toPx() }
                                val travel = (maxW.toPx() - knobPx).coerceAtLeast(0f)
                                IntOffset((shown * travel).roundToInt(), 0)
                            }
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color.White)
                            .border(1.dp, lcd.dim.copy(alpha = 0.5f)),
                    )
                }
            }
            Text(
                text = "-" + formatMs((durationMs - positionMs).coerceAtLeast(0)),
                fontSize = 11.sp,
                color = lcd.dim,
                fontFamily = ClassipodTheme.helvetica,
                textAlign = TextAlign.End,
                modifier = Modifier.width(44.dp),
            )
        }
        // Quality badge: small caps under the bar (BitChord requirement).
        if (qualityLine != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = qualityLine.uppercase(),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = lcd.dim,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(2.dp))
        // Like + volume foot row.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
           androidx.compose.material3.Icon(
                imageVector = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = null,
                tint = if (liked) androidx.compose.ui.graphics.Color(0xFFFF5B6E) else lcd.dim,
                modifier = Modifier.size(18.dp).clickable(onClick = onToggleLike),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (liked) "Loved" else "Love",
                fontSize = 12.sp,
                color = lcd.text,
                fontFamily = ClassipodTheme.helvetica,
                modifier = Modifier.clickable(onClick = onToggleLike),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = "Vol ${(volume * 100).toInt()}%",
                fontSize = 11.sp,
                color = lcd.dim,
                fontFamily = ClassipodTheme.helvetica,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (isPlaying) "❚❚ Playing" else "❚❚ Paused",
            fontSize = 11.sp,
            color = lcd.dim,
            fontFamily = ClassipodTheme.helvetica,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
    }
}

private fun formatMs(ms: Long): String {
    val s = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
