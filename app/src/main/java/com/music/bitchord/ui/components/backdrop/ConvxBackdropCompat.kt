/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Compatibility shims for the vendored Convx backdrop engine.
 *
 * The backdrop engine in this package is Convx's Liquid Glass renderer, vendored
 * from Convx (which itself vendors Kyant0/backdrop v2.0.0, Apache-2.0). It is
 * deliberately kept byte-identical to upstream apart from its package name, so
 * that it can be re-synced by re-copying and rewriting `com.convx.music.ui.
 * component.backdrop` -> `com.music.bitchord.ui.components.backdrop`.
 *
 * Convx's engine expects two pieces of app-level context that live outside the
 * engine. Those are provided here, adapted to BitChord's theme, so the engine
 * itself needs no edits.
 */

package com.music.bitchord.ui.components.backdrop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.compositionLocalOf

/**
 * Mirrors Convx's `ui.utils.isMorphClosing` flag.
 *
 * Set true while a navigation transition is *closing* (popping) rather than
 * pushing, so backdrop layers captured during the transition are frozen in the
 * right state. Convx drives this from its nav host; BitChord's shells do not
 * currently drive it, so it simply stays false and the engine degrades to
 * live sampling.
 */
private var isMorphClosing by mutableStateOf(false)

/** See [lastNavWasPop]. Call with `true` from a nav transition's pop path. */
fun markNavTransitionIsPop(isPop: Boolean) {
    isMorphClosing = isPop
}

/** See [markNavTransitionIsPop]. Mirrors Convx's `ui.utils.lastNavWasPop`. */
fun lastNavWasPop(): Boolean = isMorphClosing

/**
 * Background colour of whatever sits behind the glass, used by the engine to
 * derive a plausible ambient tint when no artwork-derived tint is available.
 *
 * Convx declares this in its root package; the engine reads it by import.
 */
/**
 * Mirrors Convx's `AppCompositionLocals.LocalAmbientBackgroundColor`.
 *
 * Holds a `MutableState<Color>` and defaults to [Color.Unspecified]; screens
 * publish their artwork tint here for sibling chrome (nav bar, mini player) to
 * read. Unspecified is meaningful here — it means "no tint published", which is
 * different from a published black.
 */
private val ambientBackgroundColorState = mutableStateOf(Color.Unspecified)

/** See [ambientBackgroundColorState]. */
val LocalAmbientBackgroundColor: androidx.compose.runtime.ProvidableCompositionLocal<androidx.compose.runtime.MutableState<Color>> =
    androidx.compose.runtime.staticCompositionLocalOf { ambientBackgroundColorState }

/**
 * Mirrors Convx's `ui.component.LocalSampledChromeDark` (ChromeToneSampler.kt).
 *
 * Convx samples the actual pixels behind its chrome and reports here whether
 * that content is dark; when non-null it wins over the ambient heuristic.
 * BitChord has no sampler, so it stays null and the ambient path is used.
 */
val LocalSampledChromeDark = compositionLocalOf<Boolean?> { null }

/**
 * Mirrors Convx's `LocalAmbientDarkChrome`, an ambient "this screen is showing
 * dark artwork" flag consulted only when no sampled answer is available.
 */
private val ambientDarkChromeState = mutableStateOf(false)

/** See [LocalAmbientDarkChrome]. */
val LocalAmbientDarkChrome: androidx.compose.runtime.ProvidableCompositionLocal<androidx.compose.runtime.MutableState<Boolean>> =
    androidx.compose.runtime.staticCompositionLocalOf { ambientDarkChromeState }

