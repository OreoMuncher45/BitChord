package com.music.bitchord.ui.apple

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    modifier: Modifier = Modifier,
) {
    val palette = LocalApplePalette.current
    val interaction = remember { MutableInteractionSource() }

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
                onClick = onOpenAccount,
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
    }
}

@Composable
private fun AppleCircleButton(
    onClick: () -> Unit,
    interaction: MutableInteractionSource,
    content: @Composable () -> Unit,
    onSwipe: ((forward: Boolean) -> Boolean)? = null,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(
                if (onSwipe == null) Modifier else Modifier.pointerInput(onSwipe) {
                    var travelled = 0f
                    val threshold = 56f
                    detectHorizontalDragGestures(
                        onDragStart = { travelled = 0f },
                        onHorizontalDrag = { _, dx ->
                            travelled += dx
                            if (kotlin.math.abs(travelled) > threshold) {
                                // Consumed only once per gesture: without this
                                // the same swipe steps every account it passes.
                                if (onSwipe(travelled < 0f)) travelled = 0f
                            }
                        },
                    )
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}
