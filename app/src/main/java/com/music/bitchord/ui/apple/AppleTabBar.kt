package com.music.bitchord.ui.apple

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.music.bitchord.ui.components.isGlassSupported
import com.music.bitchord.ui.components.liquidGlass
import com.music.bitchord.ui.components.lightweightLiquidGlass

/**
 * The floating tab bar.
 *
 * Three things about it are load-bearing and all three are load-bearing in the
 * original too:
 *
 * 1. **It is a floating capsule, inset from both edges and from the safe area**,
 *    not a full-width bar. Content scrolls *under* it and stays visible through
 *    the glass — which is the whole reason the glass exists here.
 * 2. **Search is not in the capsule.** It is a separate circle, offset to the
 *    right, half overlapping the capsule's end. Putting it inside would make
 *    the bar a conventional tab bar, and the asymmetry is most of what makes
 *    this bar recognisable.
 * 3. **Only the selected item is coloured**, and the selection does not move a
 *    background — the icon and its label take the accent and the rest stay
 *    secondary. There is no pill behind the selected tab.
 */
@Composable
fun AppleTabBar(
    tabs: List<AppleTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    onSearchSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalApplePalette.current
    val glassOn = isGlassSupported()

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(AppleSpace.TabBarHeight)
                .appleGlassSurface(palette, glassOn)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                AppleTabItem(
                    tab = tab,
                    selected = index == selectedIndex,
                    onClick = { onTabSelected(index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // The detached search circle. Same glass, same height, pulled right.
        Box(
            modifier = Modifier
                .size(AppleSpace.TabBarHeight)
                .appleGlassSurface(palette, glassOn),
            contentAlignment = Alignment.Center,
        ) {
            AppleTabItem(
                tab = AppleTab(Icons.Rounded.Search, label = null),
                selected = false,
                onClick = onSearchSelected,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Glass where the platform can do it, a filled capsule where it cannot.
 *
 * The fallback is not decoration: `Modifier.liquidGlass` needs a recorded
 * backdrop and API 31, and on API 26–30 a bar that rendered nothing at all
 * would leave the app without a tab bar. The fill alpha is matched to what the
 * glass composites to, so the bar does not visibly change shape between
 * devices — only its depth.
 */
@Composable
private fun Modifier.appleGlassSurface(palette: ApplePalette, glassOn: Boolean): Modifier =
    if (glassOn) {
        this.liquidGlass(AppleShape.Capsule)
    } else {
        this
            .clip(AppleShape.Capsule)
            .lightweightLiquidGlass(
                shape = AppleShape.Capsule,
                fallbackColor = palette.background.copy(alpha = 0.82f),
            )
    }

@Composable
private fun AppleTabItem(
    tab: AppleTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalApplePalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // Apple's press response: everything shrinks a little, including the label.
    // A background flash reads as a web button and is the single most common
    // tell of an app pretending to be native.
    val scale by animateFloatAsState(
        targetValue = if (pressed) AppleMotion.PRESS_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = 900f,
        ),
        label = "tabPress",
    )

    val tint = if (selected) palette.accent else palette.secondaryLabel

    Box(
        modifier = modifier
            .scale(scale)
            .clip(AppleShape.Capsule)
            .clickableNoRipple(interaction = interaction, onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
            if (tab.label != null) {
                Text(
                    text = tab.label,
                    style = AppleType.TabLabel,
                    color = tint,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

/**
 * A tap without Material's ripple.
 *
 * Apple has no ripple. The press feedback is the scale above; a ripple drawn on
 * top of it would be two answers to one gesture, and on a glass surface the
 * ripple's flat circle shows through the blur as a dull disc.
 */
private fun Modifier.clickableNoRipple(
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = interaction,
    indication = null,
    onClick = onClick,
)

/** A tab in the capsule. [label] null means the item is icon-only. */
data class AppleTab(val icon: ImageVector, val label: String?)
