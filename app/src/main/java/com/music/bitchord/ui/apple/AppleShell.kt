package com.music.bitchord.ui.apple

import androidx.compose.animation.AnimatedContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.model.Song

/**
 * The Apple Music shell.
 *
 * Built as its own composable with its own state, exactly the way the Classipod
 * shell is, and mounted at the same single branch point in the activity. That
 * is deliberate: a shell that shares state with the other two cannot be
 * redesigned without the risk of redesigning them, and "default, with the other
 * two still there" only works if this one is genuinely separate.
 *
 * What belongs to this shell and nothing else: the tab set, the floating bars,
 * the collapsing large title, and the destination stack. What it does *not*
 * own is playback, the library, the view model, or any of the sheets — those
 * are passed in or raised through callbacks, which is what keeps three shells
 * able to share one app.
 */
@Composable
fun AppleShell(
    darkTheme: Boolean,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onSearchSelected: () -> Unit,
    currentSong: Song?,
    isPlaying: Boolean,
    isLoading: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onExpandPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    /** The four page bodies, already bound to their state. */
    content: @Composable (PaddingValues) -> Unit,
) {
    val palette = LocalApplePalette.current
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()

    val tabs = rememberAppleTabs()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background),
    ) {
        // The page, inset by exactly the space the floating chrome occupies so
        // the last row is never trapped under the bar. The bars themselves
        // float over this rather than sitting below it.
        val bottomInset = navBarPadding.calculateBottomPadding() +
            AppleSpace.TabBarHeight + AppleSpace.BarInset +
            if (currentSong != null) {
                AppleSpace.MiniPlayerHeight + AppleSpace.BetweenBars
            } else {
                0.dp
            }
        val pagePadding = PaddingValues(
            start = 0.dp,
            end = 0.dp,
            top = statusBarPadding.calculateTopPadding(),
            bottom = bottomInset,
        )

        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                // A tab switch is a cut, not a movement. Anything that slides
                // makes the outgoing page appear to still be there, which on a
                // dense feed reads as a glitch rather than a transition.
                fadeIn(tween(AppleMotion.tabSwitch.inWholeMilliseconds.toInt())) togetherWith
                    fadeOut(tween(AppleMotion.fade.inWholeMilliseconds.toInt()))
            },
            label = "appleTab",
            modifier = Modifier.fillMaxSize(),
        ) { tab ->
            content(pagePadding)
        }

        // Floating chrome, anchored to the bottom, drawn over the page.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = navBarPadding.calculateBottomPadding() + AppleSpace.BarInset),
            verticalArrangement = Arrangement.spacedBy(AppleSpace.BetweenBars),
        ) {
            if (currentSong != null) {
                AppleMiniPlayer(
                    song = currentSong,
                    isPlaying = isPlaying,
                    isLoading = isLoading,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onExpand = onExpandPlayer,
                )
            }
            AppleTabBar(
                tabs = tabs,
                selectedIndex = selectedTab,
                onTabSelected = onTabSelected,
                onSearchSelected = onSearchSelected,
            )
        }
    }
}

/** The four tabs in the capsule, in Apple's order, with Search detached. */
@Composable
private fun rememberAppleTabs(): List<AppleTab> {
    val home = androidx.compose.ui.res.stringResource(com.music.bitchord.R.string.app_ui_apple_home)
    val newTab = androidx.compose.ui.res.stringResource(com.music.bitchord.R.string.app_ui_apple_new)
    val radio = androidx.compose.ui.res.stringResource(com.music.bitchord.R.string.app_ui_apple_radio)
    val library = androidx.compose.ui.res.stringResource(com.music.bitchord.R.string.app_ui_apple_library)
    return remember(home, newTab, radio, library) {
        listOf(
            AppleTab(Icons.Rounded.Home, home),
            AppleTab(Icons.Rounded.Apps, newTab),
            AppleTab(Icons.Rounded.Radio, radio),
            AppleTab(Icons.Rounded.LibraryMusic, library),
        )
    }
}

/** Placeholder kept honest: the tab index space is the app's, not this shell's. */
internal const val APPLE_TAB_COUNT = 4
