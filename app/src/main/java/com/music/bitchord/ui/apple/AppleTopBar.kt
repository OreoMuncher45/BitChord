package com.music.bitchord.ui.apple

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import coil3.compose.AsyncImage

/**
 * The bar at the top of a page.
 *
 * A **large title** that is part of the page and scrolls with it, exactly as
 * Apple's does: at rest the words are 34pt and sit in the page's own margin; as
 * the page scrolls they shrink and slide under the status bar until they are a
 * 17pt title in a bar with a hairline under it. The progress is the scroll
 * offset — not an animation of the scroll offset — so the title is under your
 * thumb and cannot be seen to animate.
 *
 * The account button is on the right of the large title, not in a bar above it.
 * Losing it is how a shell ends up with no way into Settings at all, which is
 * the whole reason this exists.
 */
@Composable
fun AppleTopBar(
    title: String,
    onBack: (() -> Unit)?,
    onOpenAccount: () -> Unit,
    avatarUrl: String?,
    /** 0 at rest, 1 fully collapsed. */
    collapse: Float,
    /**
     * Swiping the avatar sideways steps between signed-in accounts.
     *
     * A gesture with no visible affordance, and the reason it exists is that it
     * is the only fast way to move between channels once several are signed in.
     * Tap still opens Settings, so the gesture is an accelerator rather than a
     * hidden requirement.
     */
    onSwipeProfile: (forward: Boolean) -> Boolean = { false },
    /**
     * Account menu rows, in display order. Each opens the destination the
     * matching callback raises. Empty means the avatar opens Settings only.
     */
    accountActions: List<AppleAccountAction> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val palette = LocalApplePalette.current
    val interaction = remember { MutableInteractionSource() }
    var menuOpen by remember { mutableStateOf(false) }

    // The title does not animate: it is placed by the scroll offset directly.
    val alpha = (1f - collapse * 1.6f).coerceIn(0f, 1f)

    Box(modifier = modifier.fillMaxWidth().statusBarsPadding()) {
        // Collapsed title, fading in as the large one leaves.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (onBack != null) {
                AppleCircleButton(
                    onClick = onBack,
                    interaction = interaction,
                    label = "Back",
                    content = {
                        Icon(
                            Icons.Rounded.ChevronLeft,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(26.dp),
                        )
                    },
                )
            }
            Text(
                text = title,
                style = AppleType.CollapsedTitle,
                color = palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = if (onBack != null) 2.dp else 16.dp),
            )
        }

        // Large title, with the account button on its right.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 16.dp)
                .align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = title,
                style = AppleType.LargeTitle,
                color = palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer { this.alpha = alpha },
            )
            AppleCircleButton(
                onClick = {
                    if (accountActions.isEmpty()) onOpenAccount() else menuOpen = true
                },
                label = "Account",
                onSwipe = onSwipeProfile,
                interaction = remember { MutableInteractionSource() },
                content = {
                    if (!avatarUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(32.dp).clip(CircleShape),
                        )
                    } else {
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(palette.fillSecondary),
                        )
                    }
                },
            )

        }

        // Anchored to the outer Box, not the title Row: the menu drops below the
        // avatar and the Row's own layout would squeeze it to zero width.
        if (menuOpen && accountActions.isNotEmpty()) {
            AppleAccountMenu(
                actions = accountActions,
                onDismiss = { menuOpen = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 52.dp, end = 8.dp),
            )
        }
    }
}

/** One row in the account menu. */
data class AppleAccountAction(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * The account menu, anchored under the avatar.
 *
 * Backdrop-dismissed so a tap outside closes it, and it raises the same
 * destinations the other shells raise, so an item means the same thing here as
 * it does anywhere else in the app.
 */
@Composable
private fun AppleAccountMenu(
    actions: List<AppleAccountAction>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalApplePalette.current
    Box(modifier = modifier) {
        // The dismiss target is a scrim *behind* the menu, not a pointer handler
        // on it. A handler on the menu itself competes with each row's own click
        // for the same events and swallows them — the rows become dead, which is
        // the same failure as the account button's drag detector, one level down.
        Box(
            Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Column(
            modifier = Modifier
                .widthIn(min = 180.dp)
                .clip(AppleShape.Sheet)
                .background(palette.backgroundElevated)
                .border(0.5.dp, palette.separator, AppleShape.Sheet)
                .padding(vertical = 4.dp),
        ) {
            actions.forEach { action ->
                Text(
                    text = action.label,
                    style = AppleType.RowTitle,
                    color = palette.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onDismiss()
                            action.onClick()
                        }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                )
            }
        }
    }
}

@Composable
private fun AppleCircleButton(
    onClick: () -> Unit,
    interaction: MutableInteractionSource,
    content: @Composable () -> Unit,
    onSwipe: ((forward: Boolean) -> Boolean)? = null,
    label: String? = null,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(
                if (label != null) {
                    Modifier.semantics {
                        contentDescription = label
                        role = Role.Button
                    }
                } else {
                    Modifier
                },
            )
            .then(
                if (onSwipe == null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier.appleTapOrSwipe(onTap = onClick, onSwipe = onSwipe)
                },
            ),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

/**
 * Tap-or-swipe in a single pointer handler.
 *
 * These cannot be layered as `pointerInput(detectHorizontalDragGestures)`
 * before `clickable`: the drag detector awaits the down event and consumes it,
 * so the clickable downstream of it in the chain never sees a clean tap and the
 * button goes dead. Stacking it the other way round is no better — the drag
 * still competes for the same events. So the two are distinguished here, from
 * one gesture, on horizontal travel: past the threshold it is a swipe and the
 * tap is abandoned; under it, a release is a tap.
 *
 * The swipe fires once per gesture. Firing per delta event would step through
 * every account the finger crossed in a single drag.
 */
private fun Modifier.appleTapOrSwipe(
    onTap: () -> Unit,
    onSwipe: (forward: Boolean) -> Boolean,
): Modifier = pointerInput(onTap, onSwipe) {
    val tracker = ProfileGestureTracker()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        tracker.reset()
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
                // The decision of *what* a release means lives in
                // ProfileGestureTracker, which is testable without a compositor.
                if (!tracker.onRelease().wasSwipe) onTap()
                change.consume()
                break
            }
            tracker.onDrag(change.positionChange().x)
        }
    }
}
