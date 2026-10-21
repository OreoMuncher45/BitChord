package com.music.bitchord.ui.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.icons.BitChordIcons
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * One glyph, whichever kind it arrived as.
 *
 * The player's controls are drawn from two pools: drawable resources, which is
 * what the transport already used, and the hand-built [BitChordIcons] vectors,
 * which is what everything else used. A control may be on either pool — and
 * when the icon set changes, it moves between them.
 *
 * Call sites ask for a [PlayerIcon] and never learn which pool answered, so
 * swapping the set is a change to [PlayerIcons] alone.
 */
sealed interface PlayerIcon {

    /** The glyph as something paintable. Recomposes only when the icon does. */
    @Composable
    fun painter(): Painter

    /** A resource entry — the swap target for anything the icon set ships. */
    data class Drawable(val resource: DrawableResource) : PlayerIcon {
        @Composable
        override fun painter(): Painter = painterResource(resource)
    }

    /**
     * An [ImageVector] with no resource of its own.
     *
     * Only for roles the icon set has no glyph for. A control that has one
     * belongs in [Drawable], or the next swap will miss it.
     */
    data class Vector(val image: ImageVector) : PlayerIcon {
        @Composable
        override fun painter(): Painter = rememberVectorPainter(image)
    }
}

/**
 * Every glyph the Now Playing player draws, named for what it *does*.
 *
 * ## Why this exists
 *
 * The icon set is an open question — see `docs/APPLE_UI_PORT_PLAN.md`. Convx's
 * drawables are lookalikes and were accepted as a starting point only because
 * this file makes them cheap to replace. Apple's own set is SF Symbols, whose
 * licence permits use in applications built for Apple platforms but not
 * redistribution inside an Android APK. **That has not been cleared.** Do not
 * assume it has.
 *
 * So the set is chosen here, once, and named for the role rather than for the
 * shape it currently draws: [Play] and [Pause] are what they mean, whatever
 * they turn out to be drawn as. Replacing the whole set — with SF Symbols, with
 * better lookalikes, with anything — is an edit to this file and nothing else.
 * No call site names a drawable, and no layout names a glyph.
 *
 * ## Where the glyphs come from
 *
 * The Now Playing controls are hand-built vectors in [BitChordIcons]: one
 * family so the transport, bottom row, volume and mode controls share a weight
 * and optical size. A handful of roles still use the vendored drawables under
 * `sharedUi/src/commonMain/composeResources/drawable/` (originally from Convx,
 * GPL-3.0 — see `NOTICE`); those are the skip arrows, translate and the
 * sing-along microphone.
 */
object PlayerIcons {

    // ---- Transport -------------------------------------------------------

    /** The play button, while paused. */
    val Play = PlayerIcon.Vector(BitChordIcons.PlayerPlay)

    /** The play button, while playing. See [Play]. */
    val Pause = PlayerIcon.Vector(BitChordIcons.PlayerPause)

    /** Step forward. `apple_skip_next`. */
    val SkipNext = PlayerIcon.Drawable(Res.drawable.apple_skip_next)

    /** Step back. `apple_skip_previous`. */
    val SkipPrevious = PlayerIcon.Drawable(Res.drawable.apple_skip_previous)

    // ---- Volume ----------------------------------------------------------

    /** The speaker at the low end of the volume row. */
    val VolumeLow = PlayerIcon.Vector(BitChordIcons.PlayerVolumeLow)

    /** The speaker at the high end of the volume row. See [VolumeLow]. */
    val VolumeHigh = PlayerIcon.Vector(BitChordIcons.PlayerVolumeHigh)

    /** The volume row's speaker at zero, and the output sheet's. See [VolumeLow]. */
    val VolumeMuted = PlayerIcon.Vector(BitChordIcons.PlayerVolumeMuted)

    // ---- Queue, lyrics, ellipsis -----------------------------------------

    /** The queue, in the player's bottom row. */
    val Queue = PlayerIcon.Vector(BitChordIcons.PlayerQueue)

    /** Lyrics, in the player's bottom row. */
    val Lyrics = PlayerIcon.Vector(BitChordIcons.PlayerLyrics)

    /** The three-dot menu, in the credits row. */
    val More = PlayerIcon.Vector(BitChordIcons.PlayerEllipsis)

    // ---- Like ------------------------------------------------------------

    /**
     * The star, liked.
     *
     * Filled rather than merely tinted, which is why it is two entries and not
     * one tinted twice: the player draws every glyph white on album artwork,
     * and a colour change is the one signal the backdrop can swallow.
     */
    val Liked = PlayerIcon.Vector(BitChordIcons.PlayerStarFilled)

    /** The star, not liked — the same sharp outline. */
    val LikedOff = PlayerIcon.Vector(BitChordIcons.PlayerStar)

    // ---- The credits row's second button ---------------------------------

    /**
     * Revert to the original, which is what the ellipsis button becomes while
     * a track is playing something other than its own master.
     *
     * Convx ships no undo glyph, so this stays on Material — see [Shuffle] for
     * why a role without a swap target is a [PlayerIcon.Vector].
     */
    val Revert = PlayerIcon.Vector(Icons.AutoMirrored.Rounded.Undo)

    // ---- Output ----------------------------------------------------------

    /**
     * AirPlay, as a route the player can hand the sound to.
     *
     * The output sheet shows one glyph whether or not a route is connected, and
     * carries the state in the row's own tint — so this is the plain AirPlay
     * mark rather than the connected variant.
     */
    val AirPlay = PlayerIcon.Vector(BitChordIcons.PlayerAirPlay)

    // ---- Playback modes --------------------------------------------------

    /** Shuffle, in the three-up capsule. */
    val Shuffle = PlayerIcon.Vector(BitChordIcons.PlayerShuffle)

    /** Repeat all / off, in the three-up capsule. */
    val Repeat = PlayerIcon.Vector(BitChordIcons.PlayerRepeat)

    /** Autoplay's lemniscate, in the three-up capsule. */
    val Autoplay = PlayerIcon.Vector(BitChordIcons.PlayerAutoplay)

    /**
     * A neutral audio-output glyph, for the quality label's fallback.
     *
     * The player's output control is now AirPlay — see [AirPlay].
     */
    val Output = PlayerIcon.Vector(Icons.Rounded.Headphones)

    /** The party row in the output sheet and the Listen Together menu row. */
    val Party = PlayerIcon.Vector(Icons.Rounded.Groups)

    // ---- Placeholder -----------------------------------------------------

    /**
     * What stands in for artwork that has not arrived.
     *
     * Not a control, and not something a set would ship — but it is drawn in
     * the artwork's own box in both layouts, and leaving it out of the map is
     * how a second icon pool quietly grows back.
     */
    val NoArtwork = PlayerIcon.Vector(BitChordIcons.MusicNote)

    // ---- Roles whose only call site is frozen ----------------------------

    /**
     * The translate pill on the lyrics panel. `translate`.
     *
     * Wired to nothing yet: the pill is drawn by `PlayerLyrics.kt`, which this
     * work was told not to touch. The entry exists so that swapping the set is
     * still one file when that file is next in scope — the alternative is a
     * glyph added later with nobody checking what it was meant to be.
     */
    val Translate = PlayerIcon.Drawable(Res.drawable.translate)

    /**
     * The sing-along microphone on the lyrics panel. `mic`.
     *
     * As [Translate]: drawn from `PlayerLyrics.kt`, so reachable but not yet
     * reached.
     */
    val Microphone = PlayerIcon.Drawable(Res.drawable.mic)
}