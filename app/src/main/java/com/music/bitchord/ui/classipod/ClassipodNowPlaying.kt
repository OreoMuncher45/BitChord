package com.music.bitchord.ui.classipod

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Now Playing, 1-for-1 from Classipod's NowPlayingWidget +
 * NowPlayingBottomBar + StatusBar, with BitChord's extras folded in:
 *
 * - Header: title left, blue play/pause glyph + battery right.
 * - Art: tilted 150px cover over a washed mirror reflection
 *   ([PodReflectiveArt], edge-to-edge — never letterboxed).
 * - Meta: 18sp title, 14sp artist/album, "N of M", lossless-only badge
 *   (renders nothing for lossy streams).
 * - Bottom bar: 20dp seek track + 7-stop blue fill, stacked remaining.
 * - Center button toggles the lyrics sheet when the song has synced
 *   lyrics; rotary scrolls the sheet line-by-line, auto-following the
 *   vocal until you grab it (5s manual override).
 * - Rotary is volume otherwise, and the volume bar (diamond knob)
 *   replaces the seek bar for 2s — like the real thing.
 * - Center long-press opens the song's full options menu.
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
    lyrics: List<LyricLine>?,
    lyricsChecked: Boolean,
    queue: List<Song>,
    queueIndex: Int,
    shuffleOn: Boolean,
    repeatMode: Int,
    lcd: ClassipodTheme.Lcd,
    wheel: ClassipodWheelState,
    volume: Float,
    onVolume: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    onPlayAt: (Int) -> Unit,
    onHoldSeekStart: (dir: Int) -> Unit,
    onHoldSeekStop: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSongMenu: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val hasLyrics = !lyrics.isNullOrEmpty()
    // Center cycles art -> lyrics -> queue. Lyrics stays in the cycle
    // while the lookup is still running so the button never feels dead.
    val lyricsLive = hasLyrics || !lyricsChecked
    // 0 = art, 1 = lyrics, 2 = queue.
    var mode by remember(song?.videoId) { mutableIntStateOf(0) }
    if (mode == 1 && !lyricsLive && !hasLyrics) mode = 0
    var queueSel by remember(song?.videoId) {
        mutableIntStateOf((queueIndex + 1).coerceAtLeast(0))
    }

    // Transient volume bar: rotary shows it for 2s, like the real thing.
    var showVol by remember { mutableStateOf(false) }
    var volGen by remember { mutableIntStateOf(0) }
    LaunchedEffect(volGen) {
        if (volGen == 0) return@LaunchedEffect
        delay(2000)
        showVol = false
    }

    val lyricList = rememberLazyListState()
    var followSuspendUntil by remember { mutableLongStateOf(0L) }

    // Claimed, not assigned: when this screen leaves it unregisters, so no
    // dead screen can ever answer the wheel again. No manual resets needed.
    wheel.claim(
        ClassipodPage.NowPlaying,
        WheelHandlers(
            onStep = { dir ->
                when (mode) {
                    1 -> {
                        followSuspendUntil = System.currentTimeMillis() + 5000
                        scope.launch { lyricList.scrollBy(-dir * 90f) }
                    }
                    2 -> if (queue.isNotEmpty()) {
                        queueSel = ((queueSel + dir) % queue.size + queue.size) % queue.size
                    }
                    else -> {
                        onVolume((volume + dir * 0.04f).coerceIn(0f, 1f))
                        showVol = true
                        volGen++
                    }
                }
            },
            onCenter = {
                mode = when (mode) {
                    0 -> if (lyricsLive || hasLyrics) 1 else if (queue.isNotEmpty()) 2 else 0
                    1 -> if (queue.isNotEmpty()) 2 else 0
                    else -> 0
                }
            },
            onCenterLongPress = {
                song?.let(onSongMenu)
            },
            onSeekHoldStart = onHoldSeekStart,
            onSeekHoldStop = onHoldSeekStop,
        ),
    )

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
        // Pinned middle: the bar below always has room, on any LCD
        // height — this is what kept it off-screen before.
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when (mode) {
                1 -> LyricsSheet(
                    lines = lyrics,
                    positionMs = positionMs,
                    followSuspendUntil = followSuspendUntil,
                    listState = lyricList,
                    lcd = lcd,
                    modifier = Modifier.fillMaxSize(),
                )
                2 -> QueueSheet(
                    queue = queue,
                    queueIndex = queueIndex,
                    selected = queueSel,
                    lcd = lcd,
                    onPick = { queueSel = it },
                    onPlayPick = { onPlayAt(queueSel) },
                    modifier = Modifier.fillMaxSize(),
                )
                else -> Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                PodReflectiveArt(
                    url = song?.thumbnailUrl?.artworkAt(480),
                    lcd = lcd,
                    artSize = 150.dp,
                    reflectH = 50.dp,
                    tilt = true,
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Spacer(Modifier.height(10.dp))
                    PodMarquee(
                        text = song?.title ?: "Nothing Playing",
                        color = lcd.text,
                        fontSize = 18.sp,
                        fontFamily = ClassipodTheme.helveticaBold,
                        fontWeight = FontWeight.Bold,
                        scroll = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(5.dp))
                    PodMarquee(
                        text = song?.artist.orEmpty(),
                        color = lcd.dim,
                        fontSize = 14.sp,
                        fontFamily = ClassipodTheme.helveticaBold,
                        fontWeight = FontWeight.Bold,
                        scroll = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(5.dp))
                    PodMarquee(
                        text = song?.albumName.orEmpty(),
                        color = lcd.dim,
                        fontSize = 14.sp,
                        fontFamily = ClassipodTheme.helveticaBold,
                        fontWeight = FontWeight.Bold,
                        scroll = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Rating slot: no ratings in BitChord, keep the 22dp
                    // gap so the counter sits exactly where theirs does.
                    Spacer(Modifier.height(22.dp))
                    if (queueTotal > 0) {
                        PodMarquee(
                            text = "$queuePosition of $queueTotal",
                            color = lcd.text,
                            fontSize = 12.sp,
                            fontFamily = ClassipodTheme.helveticaBold,
                            fontWeight = FontWeight.Bold,
                            scroll = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // Lossless/hi-res only: qualityLine is null for lossy,
                    // so lossy streams render nothing here.
                    if (qualityLine != null) {
                        Spacer(Modifier.height(3.dp))
                        PodMarquee(
                            text = qualityLine.uppercase(),
                            color = lcd.dim,
                            fontSize = 10.sp,
                            fontFamily = ClassipodTheme.helveticaBold,
                            fontWeight = FontWeight.Bold,
                            scroll = true,
                            letterSpacing = 1.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
                }
            }
        }
        if (showVol && mode == 0) {
            NpVolumeBar(volume = volume, lcd = lcd)
        } else {
            NpSeekBar(
                positionMs = positionMs,
                durationMs = durationMs,
                lcd = lcd,
                onSeek = onSeek,
            )
        }
        Spacer(Modifier.height(10.dp))
        // Hint row: only what the wheel can actually do from here.
        Text(
            text = when (mode) {
                1 -> "ROTATE SCROLLS · CENTER QUEUE"
                2 -> "ROTATE PICKS · CENTER PLAYS"
                else -> when {
                    lyricsLive || hasLyrics -> "CENTER LYRICS · HOLD CENTER OPTIONS"
                    else -> "HOLD CENTER OPTIONS · HOLD ◀ ▶ SEEKS"
                }
            },
            fontFamily = ClassipodTheme.helveticaBold,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = 1.sp,
            color = lcd.dim.copy(alpha = 0.7f),
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/**
 * Synced lyrics sheet: the vocal line burns bright, the rest sits dim,
 * and the sheet walks itself down line-by-line — until you grab the
 * wheel, which buys 5s of manual control.
 */
@Composable
private fun QueueSheet(
    queue: List<Song>,
    queueIndex: Int,
    selected: Int,
    lcd: ClassipodTheme.Lcd,
    onPick: (Int) -> Unit,
    onPlayPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selected) {
        if (queue.isNotEmpty()) listState.animateScrollToItem(selected.coerceIn(0, queue.size - 1))
    }
    if (queue.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Queue is empty",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = ClassipodTheme.helveticaBold,
                color = lcd.dim,
            )
        }
        return
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        itemsIndexed(queue, key = { i, s -> "q:$i:${s.videoId}" }) { i, song ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (i == selected) lcd.selectedBg else Color.Transparent)
                    .clickable { onPick(i); onPlayPick() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (i == queueIndex) "▶" else "${i + 1}.",
                    fontSize = 12.sp,
                    color = if (i == selected) lcd.selectedText else lcd.dim,
                    fontFamily = ClassipodTheme.helvetica,
                    modifier = Modifier.width(28.dp),
                )
                Column(Modifier.weight(1f)) {
                    PodMarquee(
                        text = song.title,
                        color = if (i == selected) lcd.selectedText else lcd.text,
                        fontSize = 14.sp,
                        fontFamily = ClassipodTheme.helvetica,
                        fontWeight = FontWeight.Normal,
                        scroll = i == selected,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PodMarquee(
                        text = song.artist,
                        color = if (i == selected) lcd.selectedText.copy(alpha = 0.75f) else lcd.dim,
                        fontSize = 12.sp,
                        fontFamily = ClassipodTheme.helvetica,
                        fontWeight = FontWeight.Normal,
                        scroll = i == selected,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricsSheet(
    lines: List<LyricLine>?,
    positionMs: Long,
    followSuspendUntil: Long,
    listState: androidx.compose.foundation.lazy.LazyListState,
    lcd: ClassipodTheme.Lcd,
    modifier: Modifier = Modifier,
) {
    if (lines.isNullOrEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Finding lyrics…",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = ClassipodTheme.helveticaBold,
                color = lcd.dim,
            )
        }
        return
    }
    val activeIdx = lines.indexOfLast { it.timeMs <= positionMs }.takeIf { it >= 0 }
    LaunchedEffect(activeIdx) {
        if (activeIdx != null && System.currentTimeMillis() > followSuspendUntil) {
            listState.animateScrollToItem((activeIdx - 1).coerceAtLeast(0))
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp),
    ) {
        item(key = "lyr:top") { Spacer(Modifier.height(6.dp)) }
        itemsIndexed(lines.filter { !it.isGap }, key = { i, l -> "lyr:$i:${l.timeMs}" }) { _, line ->
            val active = activeIdx != null && lines.indexOf(line) == activeIdx
            Text(
                text = line.text,
                fontFamily = if (active) ClassipodTheme.helveticaBold else ClassipodTheme.helvetica,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                fontSize = if (active) 16.sp else 14.sp,
                lineHeight = 22.sp,
                color = if (active) lcd.text else lcd.dim.copy(alpha = 0.75f),
                modifier = Modifier.padding(vertical = 3.dp),
            )
        }
        item(key = "lyr:bottom") { Spacer(Modifier.height(40.dp)) }
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
 * Volume bar with the diamond knob: same 20dp bordered track as the
 * seek bar, blue diamond at the level. Shown for 2s after the wheel
 * moves, then the seek bar returns.
 */
@Composable
private fun NpVolumeBar(volume: Float, lcd: ClassipodTheme.Lcd) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${(volume * 100).toInt()}",
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
                .background(
                    if (lcd.dark) Brush.verticalGradient(ClassipodTheme.TRACK_DARK)
                    else Brush.verticalGradient(
                        0f to ClassipodTheme.TRACK_LIGHT[0],
                        0.6f to ClassipodTheme.TRACK_LIGHT[1],
                        1f to ClassipodTheme.TRACK_LIGHT[2],
                    ),
                )
                .border(
                    1.dp,
                    if (lcd.dark) ClassipodTheme.TRACK_DARK_BORDER
                    else ClassipodTheme.TRACK_BORDER,
                ),
        ) {
            val density = LocalDensity.current
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .align(Alignment.CenterStart)
                    .padding(start = 2.dp)
                    .graphicsLayer {
                        val knobPx = with(density) { 12.dp.toPx() }
                        translationX = volume.coerceIn(0f, 1f) * (size.width - knobPx)
                        rotationZ = 45f
                    }
                    .background(
                        Brush.verticalGradient(
                            0f to ClassipodTheme.PROGRESS_FILL[1],
                            1f to ClassipodTheme.PROGRESS_FILL[3],
                        ),
                    ),
            )
        }
        Spacer(Modifier.width(40.dp))
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
