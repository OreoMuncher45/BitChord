/*
 * The glass rendering itself is Kyant0/backdrop (Apache-2.0), vendored at
 * [com.music.bitchord.ui.components.backdrop] — see that package for the
 * upstream attribution. This file is the integration glue, adapted from
 * EchoMusicApp/Echo-Music's GlassEffectConfig/Modifier.liquidGlass
 * (GPL-3.0), cut down from Echo's full per-component/vibrancy-slider config
 * to the single on/off switch BitChord exposes in Settings. It is used by the
 * floating nav bar and, while enabled, the app-wide floating top controls.
 */
package com.music.bitchord.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.components.backdrop.Backdrop
import com.music.bitchord.ui.components.backdrop.backdrops.LayerBackdrop
import com.music.bitchord.ui.components.backdrop.effects.colorControls
import com.music.bitchord.ui.components.backdrop.highlight.Highlight
import com.music.bitchord.ui.components.backdrop.highlight.HighlightElement
import com.music.bitchord.ui.components.backdrop.internal.ShapeProvider
import com.music.bitchord.ui.components.backdrop.shadow.Shadow
import androidx.compose.ui.unit.dp

/** Whether the liquid glass nav bar is turned on — see [AppSettings.liquidGlass]. */
val LocalLiquidGlassEnabled = staticCompositionLocalOf { false }

/**
 * Where a [liquidGlass] surface composed under it also records the glass it
 * draws — the sampled, blurred and tinted backdrop, without its rim or shadow —
 * so a lens above it can refract that surface rather than the page behind it.
 * The tab bar's travelling selection pill is the one reader; see
 * [com.music.bitchord.ui.components.floatingtabbar.GlassSelectionPill].
 */
internal val LocalGlassExport = compositionLocalOf<LayerBackdrop?> { null }

private const val SURFACE_OPACITY = 0.4f


/**
 * The hairline along a bar's edge, and what stands in for the glass rim
 * wherever the glass itself is not drawn.
 *
 * A surface filled with the theme's own `surface` colour has no edge of its own
 * against a dark page — it is the same near-black the page is. The glass gets
 * its edge from [Highlight], and this is that edge for everything that does not.
 */
internal val GLASS_EDGE_WIDTH = 0.5.dp
internal val GLASS_EDGE_COLOR = Color.White.copy(alpha = 0.10f)

/**
 * Icon and label colour for content sitting on a glass surface.
 *
 * Glass shows whatever is behind it rather than the theme's surface colour, so
 * the usual onSurface greys have nothing dependable to sit against. Pure black
 * or white off the theme's luminance is the only tint that holds against
 * arbitrary artwork, and it is what Echo's own glass nav bar uses.
 */
@Composable
fun glassContentColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color.Black else Color.White

/**
 * Selected-tab indicator colour for a glass surface: the inverse of
 * [glassContentColor] rather than the same tint at lower alpha — white in
 * light theme, black in dark theme, so the pill reads as a shaded scrim
 * rather than as more of the same tint already on the glyph and label.
 */
@Composable
fun glassIndicatorColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color.White else Color.Black

/**
 * A lightweight visual match for liquid glass over a stable background.
 *
 * This keeps the same translucent tint, directional highlight and hairline as
 * [liquidGlass], but intentionally performs no backdrop capture, blur, lens
 * refraction or shadow rendering. When Liquid Glass is disabled or unsupported,
 * [fallbackColor] preserves the control's existing filled appearance.
 */
@Composable
fun Modifier.lightweightLiquidGlass(
    shape: CornerBasedShape,
    fallbackColor: Color,
): Modifier {
    val useGlass = LocalLiquidGlassEnabled.current && isGlassSupported()
    val glassTint = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        Color(0xFFFAFAFA)
    } else {
        Color(0xFF121212)
    }
    val shapeProvider = ShapeProvider { shape }

    return clip(shape)
        .background(
            color = if (useGlass) glassTint.copy(alpha = SURFACE_OPACITY) else fallbackColor,
            shape = shape,
        )
        .then(
            if (useGlass) {
                HighlightElement(
                    shapeProvider = shapeProvider,
                    highlight = { Highlight.Default },
                )
            } else {
                Modifier
            },
        )
        .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
}

/**
 * BitChord-facing glass surface. Delegates to the vendored Convx
 * [Modifier.liquidGlass], which is the authoritative implementation; this
 * wrapper only pins BitChord's house defaults so existing call sites keep a
 * one-argument shape API.
 *
 * Convx's signature is `liquidGlass(config, shape, ...)`; its defaults already
 * match Apple's nav spec (3dp blur, .60/.69 lens), so the config is passed
 * through unchanged.
 *
 * Unlike the Legacy shell's glass, this does not read the Liquid Glass toggle:
 * see [LocalLiquidGlassEnabled]. The Apple shell's chrome is glass rather than
 * a glass option over something else, so gating it left the whole Convx engine
 * unreachable behind a switch that defaults off.
 *
 * The freeze this delegate drives, which the engine always had the machinery
 * for and nothing in the app used: while the Now Playing sheet is in motion —
 * pulled up from the mini player, opened from a tap, closed by anything, or
 * dragged by hand — every surface here holds its last backdrop capture
 * instead of re-recording it per frame. That is the swipe jank fix. The swipe
 * moves the very content these surfaces sample — the docked artwork flies
 * between the mini player and the player, changing the app window every frame
 * of the spring — so each surface was paying a full layer record per frame
 * for the whole of the motion (the measured cost this pattern exists for:
 * ~47ms of record per frame without a freeze, ~2ms with one held). SpotiFLAC
 * makes the same trade by another door: their lens takes `interaction: false`
 * for the duration of a gesture. The signal is [GlassMotion.sheet], stamped
 * per frame from [com.music.bitchord.ui.player.PlayerSheetMotion].
 *
 * The cost of that is intended, and must not be "fixed": frozen glass shows
 * the capture it already holds — a static snapshot, without live refraction —
 * so during the swipe the lens does not track what passes underneath it. A
 * swipe that stays smooth is worth far more than a lens that tracks the
 * finger at the price of the swipe itself. The sheet's settle clears the
 * freeze, and the engine's thaw poll redraws the surface the moment it
 * lifts, so the glass goes live again with nothing left over.
 *
 * [LocalAppBackdropFrozen] is composed with, not replaced: a provider above
 * may one day freeze for its own reasons (a nav transition, say), and either
 * freeze is enough on its own.
 */
@Composable
fun Modifier.liquidGlass(shape: CornerBasedShape): Modifier {
    // The provider's flag is read here in composition and closed over, not
    // read inside the lambda: CompositionLocals belong to composition, and
    // the frozen lambda runs in the draw phase.
    val appFrozen = LocalAppBackdropFrozen.current
    return liquidGlass(
        // The provider, not a fresh default: [AppSettings.liquidGlassIntensity]
        // scales every knob the engine has — blur, lens, vibrancy, rim — at
        // the one site MainActivity provides it, so the setting is live for
        // every surface that routes through this delegate and intensity 1 is
        // bit-identical to the old defaults. Without this line the setting
        // persists, the config is provided, and nothing consumes it — the
        // dead-control failure mode.
        LocalGlassEffectConfig.current,
        shape,
        frozen = { GlassMotion.sheet.frozen() || appFrozen() },
    )
}
