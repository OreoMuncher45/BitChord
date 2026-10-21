/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.bitchord.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import com.music.bitchord.ui.SearchSource

@Immutable
data class NavSearchState(
    val visualActive: Boolean = false,
    val keyboardActive: Boolean = false,
    val query: String = "",
    val onQueryChange: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val searchSource: SearchSource = SearchSource.YOUTUBE,
    val onToggleSource: () -> Unit = {},
    val canToggleSource: Boolean = true,
    val onTapSearchIcon: () -> Unit = {},
    val onTapBar: () -> Unit = {},
    val onExit: () -> Unit = {},
    val onCloseKeyboard: () -> Unit = {},
    val focusRequester: FocusRequester = FocusRequester(),
)

val LocalNavSearchState = staticCompositionLocalOf { NavSearchState() }
