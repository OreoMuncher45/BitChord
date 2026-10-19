package com.music.bitchord.ui.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Person
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
 * ## Where the drawables come from
 *
 * The vectors under `sharedUi/src/commonMain/composeResources/drawable/` were
 * vendored from Convx (GPL-3.0, see `NOTICE`) and carry Convx's file names, so
 * each one is traceable back to `/home/ubuntu/convx-repo/app/src/main/res/drawable/`.
 * They are copies rather than references because this module cannot see the app
 * module's `R` — and because the desktop draws the player from this module too.
 * Each was normalised twice on the way in, and only twice: `android:tint` is an
 * Android theme attribute with no meaning elsewhere, and Compose Multiplatform's
 * vector parser throws on a colour that is not written `#`. The paths are the
 * ones Convx shipped, unaltered.
 *
 * Not every role is a Convx glyph. Those without one stay on [BitChordIcons]
 * and say so where they are declared — see [Shuffle].
 */
object PlayerIcons {

    // ---- Transport -------------------------------------------------------

    /**
     * The play button, while paused.
     *
     * Convx's `flamingo_play` is laid out on a 38-unit box and `flamingo_pause`
     * on a 32, and neither fills its own, so the pair does not read at one
     * weight. Left as shipped: the glyph is not this file's to redraw, and
     * pointing [Play] and [Pause] at a matched pair is the fix when there is
     * one.
     */
    val Play = PlayerIcon.Drawable(Res.drawable.flamingo_play)

    /** The play button, while playing. See [Play]. */
    val Pause = PlayerIcon.Drawable(Res.drawable.flamingo_pause)

    /** Step forward. `apple_skip_next`. */
    val SkipNext = PlayerIcon.Drawable(Res.drawable.apple_skip_next)

    /** Step back. `apple_skip_previous`. */
    val SkipPrevious = PlayerIcon.Drawable(Res.drawable.apple_skip_previous)

    // ---- Volume ----------------------------------------------------------

    /**
     * The speaker at the low end of the volume row.
     *
     * `volume_down` and `volume_up` are not the same family — the first is
     * Convx's thin Material-derived speaker, the second a filled glyph off the
     * 960 box the skip arrows come from — so the row's two ends do not match.
     * Muted shares the low glyph's body and so does read as its pair. Swapping
     * the row as a unit means changing these three together.
     */
    val VolumeLow = PlayerIcon.Drawable(Res.drawable.volume_down)

    /** The speaker at the high end of the volume row. See [VolumeLow]. */
    val VolumeHigh = PlayerIcon.Drawable(Res.drawable.volume_up)

    /** The volume row's speaker at zero, and the output sheet's. See [VolumeLow]. */
    val VolumeMuted = PlayerIcon.Drawable(Res.drawable.volume_mute)

    // ---- Queue, lyrics, ellipsis -----------------------------------------

    /** The queue, in the player's bottom row. `apple_queue`. */
    val Queue = PlayerIcon.Drawable(Res.drawable.apple_queue)

    /** Lyrics, in the player's bottom row. `flamingo_lyrics`. */
    val Lyrics = PlayerIcon.Drawable(Res.drawable.flamingo_lyrics)

    /**
     * The three-dot menu, in the credits row.
     *
     * `flamingo_more` carries its own 10%-alpha disc behind the dots. That is
     * drawn on top of the disc [CircleGlyph] already paints behind it, so this
     * one is a little heavier than its neighbour's.
     */
    val More = PlayerIcon.Drawable(Res.drawable.flamingo_more)

    // ---- Like ------------------------------------------------------------

    /**
     * The star, liked. Apple's is `star.fill`; Material's [Icons.Rounded.Star]
     * is the same five-point star, so it is taken from the vector pool rather
     * than a Convx drawable — Convx's `favorite` is a heart, and it shipped as
     * the Liked glyph while its own comment called it a star.
     *
     * Filled rather than merely tinted, which is why it is two entries and not
     * one tinted twice: the player draws every glyph white on album artwork,
     * and a colour change is the one signal the backdrop can swallow.
     */
    val Liked = PlayerIcon.Vector(Icons.Rounded.Star)

    /** The star, not liked — Apple's `star`, the same outline. */
    val LikedOff = PlayerIcon.Vector(Icons.Rounded.StarBorder)

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
     * AirPlay, as a route the player can hand the sound to. `cast`.
     *
     * The output sheet shows one glyph whether or not a route is connected, and
     * carries the state in the row's own tint — so this is the plain AirPlay
     * mark rather than the connected variant.
     */
    val AirPlay = PlayerIcon.Drawable(Res.drawable.cast)

    // ---- Playback modes --------------------------------------------------

    /**
     * Shuffle, in the three-up capsule.
     *
     * Convx ships no shuffle glyph, so this stays on [BitChordIcons] — which is
     * also why it stays a [PlayerIcon.Vector] until there is one to swap in.
     */
    val Shuffle = PlayerIcon.Vector(BitChordIcons.Shuffle)

    /** Repeat all / off, in the three-up capsule. As [Shuffle]. */
    val Repeat = PlayerIcon.Vector(BitChordIcons.Repeat)

    /** Autoplay's lemniscate, in the three-up capsule. As [Shuffle]. */
    val Autoplay = PlayerIcon.Vector(BitChordIcons.Infinity)

    /**
     * Where the sound leaves by, in the output capsule. As [Shuffle].
     *
     * Drawn below the capsule's nominal icon size, because Headphones is a
     * tall, narrow glyph — `PILL_HEADPHONES_SIZE` in `PlayerControls.kt` is
     * the optical correction, and it belongs there with the row.
     */
    val Output = PlayerIcon.Vector(Icons.Rounded.Headphones)

    /**
     * Who else the sound reaches, in the output capsule. As [Shuffle].
     *
     * Person rather than Groups: the three-person glyph is drawn half the
     * height of Headphones and wider than the segment holding it, so the two
     * halves of the capsule never looked like a pair.
     */
    val Party = PlayerIcon.Vector(Icons.Rounded.Person)

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