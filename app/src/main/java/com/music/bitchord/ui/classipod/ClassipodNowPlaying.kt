package com.music.bitchord.ui.classipod

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt

/**
 * Now Playing, 1-for-1 from Classipod's NowPlayingWidget +
 * NowPlayingBottomBar + StatusBar:
 *
 * - Header: title left, blue play/pause glyph + battery right
 *   (30dp silver gradient + hairline, verbatim).
 * - Art: 150x150 with a 3D tilt (rotateY -0.12, perspective 0.003)
 *   over a 50px mirror reflection washed into the background.
 * - Meta: 18sp title, 14sp artist/album, "N of M" caption. No stars —
 *   the real screen has none.
 * - Bottom bar: elapsed, 20dp bordered track with the 7-stop blue
 *   fill + gloss, stacked "- / m:ss" remaining. Tap or drag to seek.
 * - Shuffle/repeat badges only while engaged, else a 20dp spacer.
 * - Lossless line: [qualityLine] is null unless the stream is
 *   lossless/hi-res/Atmos (see discordAudioQualityLine), so it renders
 *   the badge for premium streams and nothing at all otherwise.
 * - Rotary is volume here, like the real thing.
 */
@Composable
fun ClassipodNowPlaying(
    song: Song?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    queuePosition: Int,
    queueTotal: Int,
    qualityLine: String?,
    shuffleOn: Boolean,
    repeatMode: Int,
    lcd: ClassipodTheme.Lcd,
    wheel: ClassipodWheelState,
    volume: Float,
    onVolume: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    wheel.onStep = { dir ->
        onVolume((volume + dir * 0.04f).coerceIn(0f, 1f))
    }
    wheel.onCenter = { /* transport lives on the wheel zones */ }
    Column(modifier = modifier.fillMaxSize().background(lcd.bg)) {
        NpStatusBar(isPlaying = isPlaying, lcd = lcd)
        if (shuffleOn || repeatMode != Player.REPEAT_MODE_OFF) {
            Row(
                modifier = Modifier.fillMaxWidth().height(20.dp).padding(end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                if (shuffleOn) {
                    Icon(
                        Icons.Rounded.Shuffle, null,
                        tint = lcd.text,
                        modifier = Modifier.size(20.dp).clickable(onClick = onToggleShuffle),
                    )
                    Spacer(Modifier.width(10.dp))
                }
                if (repeatMode != Player.REPEAT_MODE_OFF) {
                    Icon(
                        if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne
                        else Icons.Rounded.Repeat,
                        null,
                        tint = lcd.text,
                        modifier = Modifier.size(20.dp).clickable(onClick = onCycleRepeat),
                    )
                }
            }
        } else {
            Spacer(Modifier.height(20.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            TiltedReflectiveArt(
                url = song?.thumbnailUrl?.artworkAt(480),
                lcd = lcd,
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = song?.title ?: "Nothing Playing",
                    fontFamily = ClassipodTheme.helveticaBold,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = lcd.text,
                    maxLines = 1,
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
                // Rating slot: no ratings in BitChord, keep the 22dp
                // gap so the counter sits exactly where theirs does.
                Spacer(Modifier.height(22.dp))
                if (queueTotal > 0) {
                    Text(
                        text = "$queuePosition of $queueTotal",
                        fontFamily = ClassipodTheme.helveticaBold,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = lcd.text,
                    )
                }
                // Lossless/hi-res only: qualityLine is null for lossy,
                // so lossy streams render nothing here.
                if (qualityLine != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = qualityLine.uppercase(),
                        fontFamily = ClassipodTheme.helveticaBold,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                        color = lcd.dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(10.dp))
        NpSeekBar(
            positionMs = positionMs,
            durationMs = durationMs,
            lcd = lcd,
            onSeek = onSeek,
        )
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * Status header, verbatim from Classipod's StatusBar: 30dp, title left
 * in 14sp bold, blue play/pause glyph + battery right, hairline below.
 */
@Composable
private fun NpStatusBar(isPlaying: Boolean, lcd: ClassipodTheme.Lcd) {
    Column(modifier = Modifier.fillMaxWidth().height(30.dp)) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(
                    if (lcd.dark) {
                        Brush.verticalGradient(
                            listOf(
                                ClassipodTheme.STATUS_DARK_TOP,
                                ClassipodTheme.STATUS_DARK_BOTTOM,
                            ),
                        )
                    } else {
                        Brush.verticalGradient(
                            0f to ClassipodTheme.STATUS_GRAD_TOP,
                            0.53f to ClassipodTheme.STATUS_GRAD_MID,
                            1f to ClassipodTheme.STATUS_GRAD_BOTTOM,
                        )
                    },
                )
                .padding(horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Now Playing",
                fontFamily = ClassipodTheme.helveticaBold,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = lcd.text,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                null,
                tint = ClassipodTheme.PLAYBACK_BLUE,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(2.dp))
            NpBattery(lcd = lcd)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    if (lcd.dark) ClassipodTheme.STATUS_DARK_BORDER
                    else ClassipodTheme.STATUS_BORDER,
                ),
        )
    }
}

/** Live green battery cell, same outline language as the shell glyph. */
@Composable
private fun NpBattery(lcd: ClassipodTheme.Lcd) {
    val context = LocalContext.current
    val pct = remember { readBatteryPct(context) }
    val outline = if (lcd.dark) Color.White.copy(alpha = 0.85f) else Color(0xFF69696A)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 24.dp, height = 12.dp)
                .border(1.dp, outline),
        ) {
            Box(
                modifier = Modifier
                    .padding(2.dp)
                    .fillMaxWidth(fraction = (pct / 100f).coerceIn(0f, 1f))
                    .fillMaxSize()
                    .background(Color(0xFF4AA630)),
            )
        }
        Box(
            modifier = Modifier
                .size(width = 2.dp, height = 5.dp)
                .background(outline),
        )
    }
}

/**
 * Album art with the signature tilt + floor reflection, verbatim from
 * Classipod's AlbumReflectiveArt: 150x150 art, rotateY(-0.12) with a
 * 0.003 perspective entry, 50px flipped mirror washed out by a
 * top-to-bottom overlay (white in light, black in dark).
 */
@Composable
private fun TiltedReflectiveArt(url: Any?, lcd: ClassipodTheme.Lcd) {
    Column(
        modifier = Modifier
            .size(width = 150.dp, height = 200.dp)
            .graphicsLayer { rotationY = -6.88f },
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomCenter,
            modifier = Modifier
                .size(150.dp)
                .background(if (lcd.dark) lcd.bar else Color.Transparent),
        )
        Box(modifier = Modifier.size(width = 150.dp, height = 50.dp)) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleY = -1f },
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                if (lcd.dark) ClassipodTheme.REFLECT_DARK_TOP
                                else ClassipodTheme.REFLECT_LIGHT_TOP,
                                if (lcd.dark) ClassipodTheme.REFLECT_DARK_BOTTOM
                                else ClassipodTheme.REFLECT_LIGHT_BOTTOM,
                            ),
                        ),
                    ),
            )
        }
    }
}

/**
 * Seek bar, verbatim from Classipod's SeekBar: 20dp bordered track
 * (3-stop silver, 1px #C5C5C5), 7-stop blue fill with a top gloss,
 * 35dp elapsed left, 40dp stacked "- / m:ss" right. Tap or drag seeks.
 */
@Composable
private fun NpSeekBar(
    positionMs: Long,
    durationMs: Long,
    lcd: ClassipodTheme.Lcd,
    onSeek: (Long) -> Unit,
) {
    val progress =
        if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) dragFrac else progress
    val seekTo = { frac: Float ->
        if (durationMs > 0) onSeek((frac.coerceIn(0f, 1f) * durationMs).toLong())
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatMs(positionMs),
            fontFamily = ClassipodTheme.helveticaBold,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = lcd.text,
            maxLines = 1,
            modifier = Modifier.width(35.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(20.dp)
                .padding(horizontal = 8.dp)
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        seekTo(offset.x / size.width.toFloat().coerceAtLeast(1f))
                    }
                }
                .pointerInput(durationMs) {
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
                            seekTo(dragFrac)
                        },
                        onDragCancel = { dragging = false },
                    )
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (lcd.dark) {
                            Brush.verticalGradient(ClassipodTheme.TRACK_DARK)
                        } else {
                            Brush.verticalGradient(
                                0f to ClassipodTheme.TRACK_LIGHT[0],
                                0.6f to ClassipodTheme.TRACK_LIGHT[1],
                                1f to ClassipodTheme.TRACK_LIGHT[2],
                            )
                        },
                    )
                    .border(
                        1.dp,
                        if (lcd.dark) ClassipodTheme.TRACK_DARK_BORDER
                        else ClassipodTheme.TRACK_BORDER,
                    ),
            )
            if (shown > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = shown)
                        .height(20.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to ClassipodTheme.PROGRESS_FILL[0],
                                    0.08f to ClassipodTheme.PROGRESS_FILL[1],
                                    0.46f to ClassipodTheme.PROGRESS_FILL[2],
                                    0.54f to ClassipodTheme.PROGRESS_FILL[3],
                                    0.69f to ClassipodTheme.PROGRESS_FILL[4],
                                    0.92f to ClassipodTheme.PROGRESS_FILL[5],
                                    1f to ClassipodTheme.PROGRESS_FILL[6],
                                ),
                            ),
                    )
                    // Top gloss, like the reflection the real fill wears.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.28f),
                                        Color.White.copy(alpha = 0f),
                                    ),
                                ),
                            ),
                    )
                }
            }
        }
        Column(
            modifier = Modifier.width(40.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = "-",
                fontFamily = ClassipodTheme.helveticaBold,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = lcd.text,
            )
            Text(
                text = formatMs((durationMs - positionMs).coerceAtLeast(0)),
                fontFamily = ClassipodTheme.helveticaBold,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = lcd.text,
                maxLines = 1,
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    val s = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
