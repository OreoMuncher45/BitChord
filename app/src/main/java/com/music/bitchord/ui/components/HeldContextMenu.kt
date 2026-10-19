package com.music.bitchord.ui.components

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlin.math.roundToInt

/**
 * The menu a *held* item lifts into, in the shape Apple Music uses: the item
 * itself pops out of its list, the page behind it blurs, and the actions hang
 * off it.
 *
 * What lifts is the item exactly as it was drawn — see [HeldItem] — so a
 * grid tile comes up as that tile and a search row as that row. Both surfaces
 * it lifts onto are the app's liquid glass — see [heldMenuSurface] — so they
 * read as picked up off the page rather than pasted over its blur, and share
 * the one backdrop the rest of the app's glass samples instead of taking one
 * of their own.
 *
 * Only a hold opens this. The ⋮ keeps opening the bottom sheet — a tap on a
 * small button is a request for the full menu, while a hold on the item itself
 * is a gesture about *that item*, answered where the finger is.
 *
 * The menu opens *out of* what was pressed rather than appearing beside it:
 * [HeldItem] is already the record of where the finger was, and the menu starts
 * as the plate the card is standing on and unfolds into its own rectangle and
 * its own corner radius. See [heldMenuMorph] and [heldMenuMorphingShape]. The
 * card keeps scaling about its centre, because it is a picture of the item and
 * has to stay over the thing it is a picture of.
 *
 * [item] and [held] come and go together: non-null while the menu is wanted,
 * null once anything has closed it. The last pair is kept here so the menu can
 * play its way back into the list after the caller has already let go of it.
 *
 * Both of the app's accessibility switches are honoured: "Reduce animation"
 * (or the system's animator scale at zero) turns the lift into a short fade
 * with nothing moving, and "Reduce dynamic blur" swaps the blurred page for a
 * plain dimming scrim and the glass for the flat plate — which is also the
 * cost that setting exists to remove.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun <T : Any> HeldContextMenu(
    item: T?,
    held: HeldItem?,
    hazeState: HazeState,
    onDismiss: () -> Unit,
    /** Tapping the lifted item — open what it stands for. Null leaves it inert. */
    onPreviewClick: ((T) -> Unit)?,
    actions: @Composable (T) -> Unit,
) {
    val requested = if (item != null && held != null) item to held else null
    var last by remember { mutableStateOf<Pair<T, HeldItem>?>(null) }
    if (requested != null && requested != last) {
        SideEffect { last = requested }
    }
    val shown = requested ?: last ?: return
    val (menuItem, heldItem) = shown
    val open = requested != null

    val context = LocalContext.current
    val appReduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    val systemAnimationsOff = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val reduceMotion = appReduceAnimation || systemAnimationsOff
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()

    val progress = remember { Animatable(0f) }
    LaunchedEffect(open) {
        if (open) {
            progress.animateTo(
                1f,
                if (reduceMotion) {
                    tween(durationMillis = 140, easing = LinearEasing)
                } else {
                    // Under-damped on purpose: the small overshoot is the "pop"
                    // of the item coming off the page.
                    spring(dampingRatio = 0.72f, stiffness = 420f)
                },
            )
        } else {
            progress.animateTo(
                0f,
                tween(durationMillis = if (reduceMotion) 110 else 230, easing = FastOutSlowInEasing),
            )
            last = null
        }
    }

    BackHandler(enabled = open, onBack = onDismiss)

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // What the menu stands on where the app is not drawing glass. Plain white
    // or black at half strength, so the blur shows through as frost rather
    // than the surface greys reading as a flat slab over it. With no blur
    // behind there is nothing for that to frost, so the plate firms up to stay
    // legible over the bare scrim.
    val plate = (if (dark) Color.Black else Color.White).copy(alpha = if (reduceDynamicBlur) 0.85f else 0.5f)
    val hairline = if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)
    // The scrim does less work when the blur is there to push the page back,
    // and more when it is the only thing doing it.
    val scrimColor = when {
        reduceDynamicBlur && dark -> Color.Black.copy(alpha = 0.62f)
        reduceDynamicBlur -> Color.Black.copy(alpha = 0.36f)
        dark -> Color.Black.copy(alpha = 0.30f)
        else -> Color.Black.copy(alpha = 0.10f)
    }
    val cardShape = RoundedCornerShape(CARD_RADIUS)
    val menuShape = RoundedCornerShape(MENU_RADIUS)
    val platePadding = 8.dp
    // The card keeps its own radius for the whole of the lift; the menu's runs
    // from the card's to its own as it opens, so both are needed in pixels.
    val density = LocalDensity.current
    val cardRadiusPx = with(density) { CARD_RADIUS.toPx() }
    val menuRadiusPx = with(density) { MENU_RADIUS.toPx() }
    // Glass draws its own elevation shadow, so the lift shadow below is only
    // paid for on the surfaces that are not getting one — the double render
    // the nav bar's own comment is about paying for.
    val glass = heldMenuUsesGlass(reduceDynamicBlur)

    val insets = WindowInsets.safeDrawing
    var overlayOffset by remember { mutableStateOf(Offset.Zero) }

    Layout(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { overlayOffset = it.positionInWindow() }
            // Once it is on its way out nothing on it answers any more — a
            // second tap on a row mid-exit would run its action twice.
            .then(if (open) Modifier else Modifier.pointerInput(Unit) { swallowAll() }),
        content = {
            // The page, pushed back. One alpha fades the whole of it rather
            // than a blur radius being animated per frame.
            val backdropAlpha = { progress.value.coerceIn(0f, 1f) }
            Spacer(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (reduceDynamicBlur) {
                            Modifier
                        } else {
                            Modifier.optimizedHazeEffect(
                                state = hazeState,
                                style = HazeMaterials.ultraThin(MaterialTheme.colorScheme.background),
                            ) {
                                alpha = backdropAlpha()
                            }
                        },
                    )
                    .graphicsLayer { alpha = backdropAlpha() }
                    .background(scrimColor)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = open,
                        onClick = onDismiss,
                    ),
            )
            // The held item, on its plate. Sized by the layout below to the
            // item's own bounds plus the plate's margin. Its shape is the
            // item's own plate radius for the whole lift — only the menu
            // morphs its corners, because only the menu changes what it is.
            Box(
                Modifier
                    .then(
                        if (reduceMotion || glass) {
                            Modifier
                        } else {
                            Modifier.shadow(20.dp, cardShape, clip = false)
                        },
                    )
                    .pointerInput(Unit) {}
                    .heldMenuSurface(cardShape, plate, hairline, reduceDynamicBlur)
                    .then(
                        if (onPreviewClick != null) {
                            Modifier.clickable(enabled = open) { onPreviewClick(menuItem) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(platePadding),
            ) {
                heldItem.image?.let { image ->
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            // The menu's own shape for this frame, and with it the surface.
            // Built here rather than above so that reading the radius is its
            // own restart scope: what recomposes each frame of the morph is
            // this one modifier, not the menu's rows underneath it.
            val menuShapeFrame = heldMenuMorphingShape(
                animates = !reduceMotion,
                resting = menuShape,
                radiusPx = {
                    heldMenuCornerRadiusPx(progress.value, cardRadiusPx, menuRadiusPx)
                },
            )
            Column(
                Modifier
                    // Taps between rows land on the menu, not the scrim under it.
                    .pointerInput(Unit) {}
                    .heldMenuSurface(menuShapeFrame, plate, hairline, reduceDynamicBlur),
            ) {
                actions(menuItem)
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val margin = 12.dp.roundToPx()
        val gap = 10.dp.roundToPx()
        val pad = platePadding.roundToPx()
        val top = insets.getTop(this) + 8.dp.roundToPx()
        val bottom = height - insets.getBottom(this) - 8.dp.roundToPx()

        val backdrop = measurables[0].measure(Constraints.fixed(width, height))

        // The held item, in this layout's own coordinates.
        val itemBounds = heldItem.bounds.translate(-overlayOffset)
        // The plate at the item's own size, so at rest the picture on it is
        // pixel for pixel what was held; a full-width row is then scaled down
        // just enough for the plate to clear the screen edges.
        val plateW = (itemBounds.width.roundToInt() + pad * 2).coerceAtLeast(1)
        val plateH = (itemBounds.height.roundToInt() + pad * 2).coerceAtLeast(1)
        val card = measurables[1].measure(Constraints.fixed(plateW, plateH))
        val liftScale = ((width - margin * 2f) / plateW).coerceAtMost(1f)
        val visualW = plateW * liftScale
        val visualH = plateH * liftScale

        val menuWidth = (width - margin * 2).coerceAtMost(280.dp.roundToPx()).coerceAtLeast(0)
        val menuMaxHeight = (bottom - top - visualH.roundToInt() - gap).coerceAtLeast(0)
        val menu = measurables[2].measure(
            Constraints(minWidth = menuWidth, maxWidth = menuWidth, maxHeight = menuMaxHeight),
        )

        // Where the lifted item rests. It stays where it was held whenever the
        // menu fits beside it; the menu goes below by preference and above
        // when the item is low on screen, and only when neither fits does the
        // item climb to make room.
        val visualLeft = (itemBounds.center.x - visualW / 2f)
            .coerceIn(margin.toFloat(), (width - margin - visualW).coerceAtLeast(margin.toFloat()))
        var visualTop = (itemBounds.center.y - visualH / 2f)
            .coerceIn(top.toFloat(), (bottom - visualH).coerceAtLeast(top.toFloat()))
        val menuBelow: Boolean
        when {
            visualTop + visualH + gap + menu.height <= bottom -> menuBelow = true
            visualTop - gap - menu.height >= top -> menuBelow = false
            else -> {
                menuBelow = true
                visualTop = (bottom - menu.height - gap - visualH).coerceAtLeast(top.toFloat())
            }
        }
        val visualCenterX = visualLeft + visualW / 2f
        val visualCenterY = visualTop + visualH / 2f

        // The menu hangs off the item's nearer edge: its start for an item on
        // the left of the screen, its end for one on the right — a grid's
        // right-hand column keeps its menu under itself.
        val alignEnd = visualCenterX > width / 2f
        val menuX = (if (alignEnd) visualLeft + visualW - menuWidth else visualLeft).roundToInt()
            .coerceIn(margin, (width - margin - menuWidth).coerceAtLeast(margin))
        val menuY = if (menuBelow) {
            (visualTop + visualH + gap).roundToInt()
        } else {
            (visualTop - gap - menu.height).roundToInt()
        }

        // The card is placed at its natural size and scaled about its centre,
        // so its centre is where the visual rectangle's is.
        val cardX = (visualCenterX - plateW / 2f).roundToInt()
        val cardY = (visualCenterY - plateH / 2f).roundToInt()
        // How far the card travels back to sit over what was held. At rest
        // in the list it is unscaled, so the picture lands exactly on the item.
        val fromDx = itemBounds.center.x - visualCenterX
        val fromDy = itemBounds.center.y - visualCenterY

        // The rectangle the menu opens out of: the plate as it is at the very
        // start of the lift, when the card is unscaled and sitting exactly
        // over what was held. Derived rather than measured because it is the
        // same plate, and reading it off the card's own travel is what keeps
        // the two ends of the morph agreeing to the pixel.
        val originRect = Rect(
            itemBounds.left - pad,
            itemBounds.top - pad,
            itemBounds.left - pad + plateW,
            itemBounds.top - pad + plateH,
        )
        // Where the menu ends up, as a rectangle rather than a corner, so the
        // morph at each end is the shape of something already on screen.
        val menuRect = Rect(
            menuX.toFloat(),
            menuY.toFloat(),
            (menuX + menu.width).toFloat(),
            (menuY + menu.height).toFloat(),
        )

        layout(width, height) {
            backdrop.place(0, 0)
            card.placeWithLayer(cardX, cardY) {
                val p = progress.value
                if (reduceMotion) {
                    scaleX = liftScale
                    scaleY = liftScale
                    alpha = p.coerceIn(0f, 1f)
                } else {
                    val scale = lerp(1f, liftScale, p)
                    scaleX = scale
                    scaleY = scale
                    translationX = lerp(fromDx, 0f, p)
                    translationY = lerp(fromDy, 0f, p)
                    // Quick, because it starts over the very item it copies:
                    // only the plate and shadow arriving are new.
                    alpha = (p * 3f).coerceIn(0f, 1f)
                }
            }
            menu.placeWithLayer(menuX, menuY) {
                val p = progress.value
                alpha = ((p - 0.1f) / 0.9f).coerceIn(0f, 1f)
                if (!reduceMotion) {
                    // The menu is not arriving somewhere: it is the plate the
                    // finger was on, opening out to where the layout put it.
                    // Pinned to its top-left corner at that end and to its own
                    // at this one, so the corner the press happened in stays
                    // put and the far edges travel — see [heldMenuMorph].
                    val morph = heldMenuMorph(p, originRect, menuRect)
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = morph.scaleX
                    scaleY = morph.scaleY
                    translationX = morph.translationX
                    translationY = morph.translationY
                }
            }
        }
    }
}

private suspend fun PointerInputScope.swallowAll() {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/**
 * The plate's corner radius, and the menu's — named because the two are the
 * ends of one animation rather than two unrelated numbers: the menu opens as
 * the card's plate and closes the corner slightly tighter as it becomes its
 * own surface. Both the shape and the morph are built from these, so the
 * drawn corner and the animated one cannot drift apart.
 */
private val CARD_RADIUS = 20.dp
private val MENU_RADIUS = 16.dp
