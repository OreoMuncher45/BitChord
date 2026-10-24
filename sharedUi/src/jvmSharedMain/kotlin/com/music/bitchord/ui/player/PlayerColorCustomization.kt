package com.music.bitchord.ui.player

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colours the listener pinned on the Now Playing screen.
 *
 * A null role means "follow the artwork", which is the player's original
 * behaviour. The roles are separate on purpose: recolouring the scrubber should
 * not also recolour the artist name, and the transport is the part most people
 * mean when they say the buttons are the wrong colour.
 */
data class PlayerColorOverrides(
    val accent: Color? = null,
    val seekbar: Color? = null,
    val text: Color? = null,
    /** The one-line lyric above the scrubber; null follows the artwork ink. */
    val lyrics: Color? = null,
)

internal val LocalPlayerAccent = staticCompositionLocalOf<Color?> { null }
internal val LocalPlayerSeekbar = staticCompositionLocalOf<Color?> { null }
internal val LocalPlayerText = staticCompositionLocalOf<Color?> { null }
internal val LocalPlayerLyrics = staticCompositionLocalOf<Color?> { null }

/**
 * The opacity roles that go with the artwork palette. Defaults match the
 * player's own table, so any surface outside the expanded player is unchanged.
 */
internal val LocalPlayerAlphas = staticCompositionLocalOf { NowPlayingAlphas() }
