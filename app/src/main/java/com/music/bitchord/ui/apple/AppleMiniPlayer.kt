package com.music.bitchord.ui.apple

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.components.isGlassSupported
import com.music.bitchord.ui.components.liquidGlass
import com.music.bitchord.ui.components.lightweightLiquidGlass

/**
 * The mini player: a floating capsule above the tab bar.
 *
 * It is a *separate* bar from the tab bar in Apple's design, not a fifth tab,
 * and that is worth stating because it is easy to fold the two together and
 * lose the layering. The song sits above the navigation, visually a separate
 * object, and the two move together only when the whole chrome is scrolled away.
 *
 * Artwork, then two lines of text, then the two transport controls. No
 * progress, no scrubber — tapping anywhere opens the full player, which is
 * where the scrubber lives.
 */
@Composable
fun AppleMiniPlayer(
    song: Song,
    isPlaying: Boolean,
    isLoading: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalApplePalette.current
    val glassOn = isGlassSupported()
    val shape: Shape = AppleShape.Capsule

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(AppleSpace.MiniPlayerHeight)
            .then(
                if (glassOn) {
                    Modifier.liquidGlass(AppleShape.Capsule)
                } else {
                    Modifier.clip(AppleShape.Capsule)
                        .lightweightLiquidGlass(
                            shape = AppleShape.Capsule,
                            fallbackColor = palette.background.copy(alpha = 0.82f),
                        )
                },
            )
            .clip(AppleShape.Capsule)
            .applePressable(onClick = onExpand)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .padding(vertical = 4.dp)
                .size(48.dp)
                .clip(AppleShape.Card),
            contentAlignment = Alignment.Center,
        ) {
            if (!song.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = song.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(palette.fillSecondary),
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = song.title,
                style = AppleType.RowTitle.copy(fontSize = AppleType.RowTitle.fontSize * 0.88f),
                color = palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = song.artist,
                style = AppleType.RowSubtitle.copy(fontSize = AppleType.RowSubtitle.fontSize * 0.93f),
                color = palette.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        GlassIconButton(
            icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            onClick = onPlayPause,
            enabled = !isLoading,
        )
        GlassIconButton(
            icon = Icons.Rounded.SkipNext,
            contentDescription = "Next",
            onClick = onNext,
        )
    }
}

@Composable
private fun GlassIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val palette = LocalApplePalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) AppleMotion.PRESS_SCALE else 1f,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 900f),
        label = "transportPress",
    )
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(40.dp)
            .scale(scale)
            .clip(AppleShape.Capsule)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) palette.label else palette.tertiaryLabel,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Scale on press, no ripple — see [AppleTabBar]. */
private fun Modifier.applePressable(onClick: () -> Unit): Modifier =
    this.then(
        Modifier.clickable(
            interactionSource = MutableInteractionSource(),
            indication = null,
            onClick = onClick,
        ),
    )
