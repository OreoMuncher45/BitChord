package com.music.bitchord.ui.classipod

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.ToneGenerator
import android.os.BatteryManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * The iPod shell: status bar, LCD screen hosting a page stack, click wheel.
 *
 * Two input paths live at once, exactly per the brief: the wheel (rotary
 * scroll/volume, center select, MENU back, transport zones) and plain touch
 * (tap selects, system back pops). The wheel never owns state the touch path
 * cannot also reach — rotation only ever calls [onWheelStep], taps only ever
 * call the same handlers a tap would.
 */
/** Trace-proof identity for transitions: content growth must not replay them. */
private val ClassipodPage.transitionKey: Any
    get() = when (this) {
        is ClassipodPage.Menu -> listOf("m", title)
        is ClassipodPage.Tracks -> listOf("t", title, source)
        is ClassipodPage.CoverFlow -> listOf("c", title, albums.size)
        is ClassipodPage.PagedTracks -> listOf("p", title, browseId)
        else -> listOf("o", title)
    }

@Composable
fun ClassipodRoot(
    darkTheme: Boolean,
    stack: List<ClassipodPage>,
    onPush: (ClassipodPage) -> Unit,
    onPop: () -> Unit,
    onPopToRoot: () -> Unit,
    wheel: ClassipodWheelState,
    content: @Composable (ClassipodPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorwayName by AppSettings.classipodColorway.collectAsStateWithLifecycle()
    val way = ClassipodTheme.colorway(colorwayName)
    val lcd = ClassipodTheme.lcd(darkTheme)
    val reduceMotion by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    // System back pops the iPod stack, like MENU.
    BackHandler(enabled = stack.size > 1) { onPop() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(way.frameTop, way.frameBottom)))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        ClassipodStatusBar(lcd = lcd)
        Spacer(Modifier.height(8.dp))
        // The LCD: black bezel, screen content clipped inside.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black)
                .padding(3.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(lcd.bg),
        ) {
            // Push slides in from the right, pop from the left; reduced
            // motion fades. Keyed on identity, not contents, so a growing
            // paged list never replays the transition as rows land. Depth
            // scopes the keys so two same-titled pages never collide.
            val pageByKey = remember { mutableMapOf<Any, ClassipodPage>() }
            val keys = stack.mapIndexed { i, page ->
                (i to page.transitionKey).also { pageByKey[it] = page }
            }
            AnimatedContent(
                targetState = keys,
                transitionSpec = {
                    val push = targetState.size > initialState.size
                    if (reduceMotion) {
                        fadeIn() togetherWith fadeOut()
                    } else {
                        (slideInHorizontally { w -> if (push) w else -w } + fadeIn()) togetherWith
                            (slideOutHorizontally { w -> if (push) -w else w } + fadeOut())
                    }
                },
                contentAlignment = Alignment.TopStart,
                modifier = Modifier.fillMaxSize(),
            ) { ks ->
                content(ks.mapNotNull { pageByKey[it] }.lastOrNull() ?: stack.last())
            }
        }
        Spacer(Modifier.height(10.dp))
        ClickWheel(
            way = way,
            state = wheel,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

/** iPod status bar: play glyph, clock, battery — all live. */
@Composable
private fun ClassipodStatusBar(lcd: ClassipodTheme.Lcd, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var minute by remember { mutableIntStateOf((System.currentTimeMillis() / 60_000).toInt()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(20_000)
            minute = (System.currentTimeMillis() / 60_000).toInt()
        }
    }
    val battery = remember(minute) { readBatteryPct(context) }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "▶",
            color = ClassipodTheme.lcd(false).text.copy(alpha = 0.85f),
            fontSize = 11.sp,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date()),
            fontFamily = ClassipodTheme.helveticaBold,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.92f),
        )
        Spacer(Modifier.weight(1f))
        BatteryGlyph(pct = battery)
    }
}

internal fun readBatteryPct(context: Context): Int {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    return if (level >= 0 && scale > 0) (level * 100 / scale) else 100
}

@Composable
private fun BatteryGlyph(pct: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 24.dp, height = 12.dp)
                .borderThin(Color.White.copy(alpha = 0.85f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize(fraction = 1f)
                    .padding(2.dp)
                    .background(Color.White.copy(alpha = 0.85f))
                    .fillMaxWidth(fraction = pct / 100f),
            )
        }
        Box(
            modifier = Modifier
                .size(width = 2.dp, height = 5.dp)
                .background(Color.White.copy(alpha = 0.85f)),
        )
    }
}

private fun Modifier.borderThin(color: Color): Modifier =
    this.then(Modifier.border(1.dp, color, RoundedCornerShape(3.dp)))

/** Which wheel zone was tapped. */
enum class WheelZone { MENU, PREV, NEXT, PLAY_PAUSE, CENTER }

/**
 * Mutable wheel behaviour owned by the current screen: list screens scroll
 * on rotation, Now Playing scrubs-or-volumes. Screens set [onStep] and the
 * transport callbacks; the wheel itself is stateless chrome.
 */
class ClassipodWheelState {
    var onStep: (dir: Int) -> Unit = {}
    var onMenu: () -> Unit = {}
    var onCenter: () -> Unit = {}
    /** Center long-press: song options on Now Playing. Reset on leave. */
    var onCenterLongPress: () -> Unit = {}
    /**
     * Zone hold: holding PREV/NEXT seeks backward/forward until release
     * (real iPod behavior). Start fires once past the hold timeout, stop
     * on lift. Reset on leave.
     */
    var onSeekHoldStart: (dir: Int) -> Unit = {}
    var onSeekHoldStop: () -> Unit = {}
    var onPrev: () -> Unit = {}
    var onNext: () -> Unit = {}
    var onPlayPause: () -> Unit = {}
}

/** Click feedback: the real iPod click sample (see [ClassipodClicks]). */
fun playClick(context: Context) {
    ClassipodClicks.play(context)
}

/**
 * The click wheel: outer ring (rotary scroll), center select, MENU/prev/next/
 * play zones. Rotation is angle math around the center — drag in circles to
 * scroll, tap zones to fire. A full turn is [stepsPerTurn] steps.
 */
@Composable
fun ClickWheel(
    way: ClassipodTheme.Colorway,
    state: ClassipodWheelState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val stepsPerTurn by AppSettings.classipodWheelSteps.collectAsStateWithLifecycle()
    BoxWithConstraints(modifier = modifier) {
        val diameter = minOf(maxWidth, 264.dp)
        val radiusPx = with(LocalDensity.current) { (diameter / 2).toPx() }
        var lastAngle by remember { mutableStateOf<Float?>(null) }
        var acc by remember { mutableStateOf(0f) }
        var downAt by remember { mutableStateOf(Offset.Zero) }
        var downTime by remember { mutableStateOf(0L) }

        fun angleOf(p: Offset, c: Offset): Float {
            val a = Math.toDegrees(atan2((p.y - c.y).toDouble(), (p.x - c.x).toDouble())).toFloat()
            return ((a % 360) + 360) % 360
        }

        /**
         * A dispatch that can never kill the input loop. Before this guard
         * existed, a throwing center/menu callback ended the await loop
         * below — which reads exactly as a dead middle button with the
         * ring glyphs and rotary still alive, because those live in other
         * handlers. Cancellation still propagates: only real faults are
         * logged and swallowed.
         */
        fun guarded(action: () -> Unit) {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TrackLog.e("Classipod", "wheel dispatch failed", e)
            }
        }

        fun tapRing(a: Float) {
            when {
                a in 235f..305f -> state.onMenu()
                a in 125f..235f -> state.onPrev()
                a <= 55f || a >= 305f -> state.onNext()
                else -> state.onPlayPause()
            }
        }

        Box(
            modifier = Modifier
                .size(diameter)
                .pointerInput(stepsPerTurn) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            lastAngle = null
                            acc = 0f
                            downAt = offset
                            downTime = System.currentTimeMillis()
                        },
                        onDrag = { change, _ ->
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val a = angleOf(change.position, c)
                            val prev = lastAngle
                            lastAngle = a
                            if (prev != null) {
                                var d = a - prev
                                if (d > 180) d -= 360
                                if (d < -180) d += 360
                                acc += d
                                val stepDeg = 360f / stepsPerTurn
                                while (acc >= stepDeg) {
                                    acc -= stepDeg
                                    state.onStep(1)
                                    playClick(context)
                                }
                                while (acc <= -stepDeg) {
                                    acc += stepDeg
                                    state.onStep(-1)
                                    playClick(context)
                                }
                            }
                        },
                        onDragEnd = { lastAngle = null },
                        onDragCancel = { lastAngle = null },
                    )
                }
                .pointerInput(Unit) {
                    // Press state machine, rebuilt: quick lift = tap, 400ms
                    // hold = long-press. Center hold opens the song menu,
                    // PREV/NEXT hold seeks until lift (real iPod behavior).
                    // A null lift means the rotary consumed the gesture —
                    // circling never taps. Every dispatch runs guarded and a
                    // started hold ALWAYS sees its stop (try/finally, so even
                    // disposal ends it) — a runaway seek loop hammering the
                    // player forever used to read as a "bugged whole app".
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown()
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val dist = kotlin.math.hypot(
                                down.position.x - c.x, down.position.y - c.y,
                            )
                            val angle = angleOf(down.position, c)
                            var timedOut = false
                            val up = try {
                                withTimeout(400L) { waitForUpOrCancellation() }
                            } catch (e: TimeoutCancellationException) {
                                timedOut = true
                                null
                            }
                            if (!timedOut && up == null) {
                                Unit // rotary drag took it — not a press
                            } else if (!timedOut) {
                                guarded { playClick(context) }
                                if (dist < radiusPx * 0.38f) {
                                    guarded { state.onCenter() }
                                } else {
                                    guarded { tapRing(angle) }
                                }
                            } else {
                                guarded { playClick(context) }
                                if (dist < radiusPx * 0.38f) {
                                    guarded { state.onCenterLongPress() }
                                } else if (angle in 235f..305f) {
                                    guarded { state.onMenu() }
                                } else {
                                    val dir = when {
                                        angle in 125f..235f -> -1
                                        angle <= 55f || angle >= 305f -> 1
                                        else -> 0
                                    }
                                    if (dir == 0) {
                                        guarded { state.onPlayPause() }
                                        waitForUpOrCancellation()
                                    } else {
                                        guarded { state.onSeekHoldStart(dir) }
                                        try {
                                            waitForUpOrCancellation()
                                        } finally {
                                            guarded { state.onSeekHoldStop() }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.verticalGradient(listOf(way.wheelRingTop, way.wheelRingBottom)),
                    radius = size.minDimension / 2f,
                )
                drawCircle(
                    brush = Brush.verticalGradient(listOf(way.wheelCenterTop, way.wheelCenterBottom)),
                    radius = size.minDimension / 2f * 0.38f,
                )
                drawCircle(
                    color = way.glyph.copy(alpha = 0.25f),
                    radius = size.minDimension / 2f * 0.38f,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            // Zone glyphs: MENU top, prev/next flanks, play bottom.
            val glyph = way.glyph
            WheelGlyph(text = "MENU", fontSize = 11, color = glyph, modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)) { state.onMenu() }
            WheelGlyph(text = "⏮", fontSize = 18, color = glyph, modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp)) { state.onPrev() }
            WheelGlyph(text = "⏭", fontSize = 18, color = glyph, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) { state.onNext() }
            WheelGlyph(text = "▶❚❚", fontSize = 15, color = glyph, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) { state.onPlayPause() }
        }
    }
}

@Composable
private fun WheelGlyph(
    text: String,
    fontSize: Int,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Text(
        text = text,
        color = color,
        fontSize = fontSize.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = ClassipodTheme.helveticaBold,
        textAlign = TextAlign.Center,
        modifier = modifier.clickable(onClick = onClick),
    )
}

/**
 * Standard LCD list row, 1-for-1 from Classipod's DisplayListTile: 30dp,
 * bold 16sp, blue gradient + hairline borders + white text + chevron only
 * when selected.
 */
@Composable
fun ClassipodRow(
    title: String,
    value: String? = null,
    selected: Boolean = false,
    lcd: ClassipodTheme.Lcd,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(30.dp)
            .then(
                if (selected) {
                    Modifier
                        .border(1.dp, ClassipodTheme.SELECT_BORDER_TOP)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    ClassipodTheme.SELECT_GRAD_TOP,
                                    ClassipodTheme.SELECT_GRAD_MID,
                                    ClassipodTheme.SELECT_GRAD_BOTTOM,
                                ),
                            ),
                        )
                } else {
                    Modifier.background(Color.Transparent)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PodMarquee(
            text = title,
            color = if (selected) Color.White else lcd.text,
            fontSize = ClassipodTheme.ROW_SIZE,
            fontFamily = ClassipodTheme.helveticaBold,
            fontWeight = FontWeight.Bold,
            scroll = selected,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                text = value,
                fontFamily = ClassipodTheme.helvetica,
                fontSize = ClassipodTheme.SMALL_SIZE,
                color = if (selected) Color.White.copy(alpha = 0.85f) else lcd.dim,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
        }
        if (selected) {
            Text(
                text = "›",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
    }
}

/**
 * LCD header bar: silver gradient, centered bold title — verbatim from
 * Classipod's status-bar gradient (FAFAFA → D1D1D1 → ABABAB).
 */
@Composable
fun ClassipodBar(
    title: String,
    lcd: ClassipodTheme.Lcd,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        ClassipodTheme.STATUS_GRAD_TOP,
                        ClassipodTheme.STATUS_GRAD_MID,
                        ClassipodTheme.STATUS_GRAD_BOTTOM,
                    ),
                ),
            )
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (onBack != null) "‹" else "",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF3A3A3A),
            modifier = Modifier
                .width(24.dp)
                .clickable(enabled = onBack != null) { onBack?.invoke() },
            textAlign = TextAlign.Center,
        )
        Text(
            text = title,
            fontFamily = ClassipodTheme.helveticaBold,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = Color(0xFF1A1A1A),
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (trailing != null) {
            trailing()
        } else {
            Spacer(Modifier.width(24.dp))
        }
    }
}
