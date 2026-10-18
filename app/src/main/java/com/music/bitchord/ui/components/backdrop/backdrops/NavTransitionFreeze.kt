/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */
package com.music.bitchord.ui.components.backdrop.backdrops

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import com.music.bitchord.ui.components.backdrop.lastNavWasPop
import kotlinx.coroutines.flow.collectLatest

// Covers the 260ms push plus frame-delivery slop; longer freezes leave stale glass visible.
private const val NavTransitionFreezeWindowNs = 330_000_000L

/** Freezes the app backdrop for a navigation push, which does not trigger [BackdropFreeze]'s scroll-based freeze. */
class NavTransitionFreeze {
    private val startedAtNs = longArrayOf(0L)

    val frozen: () -> Boolean = {
        val started = startedAtNs[0]
        started != 0L && System.nanoTime() - started < NavTransitionFreezeWindowNs
    }

    fun markTransitionStarted() {
        startedAtNs[0] = System.nanoTime()
    }
}

/** Freezes route pushes; predictive-back pops are gesture-driven and change route only after commit. */
@Composable
fun rememberNavTransitionFreeze(currentRoute: String?): NavTransitionFreeze {
    val freeze = remember { NavTransitionFreeze() }
    LaunchedEffect(currentRoute) {
        if (!lastNavWasPop()) {
            freeze.markTransitionStarted()
        }
    }
    return freeze
}

/**
 * Freezes backdrop recording while a pager changes tabs. Observe scroll state
 * in a coroutine so the draw-phase freeze check does not trigger invalidations.
 */
@Composable
fun rememberPagerTransitionFreeze(pagerState: PagerState): NavTransitionFreeze {
    val freeze = remember { NavTransitionFreeze() }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress }
            .collectLatest { scrolling ->
                while (scrolling) {
                    freeze.markTransitionStarted()
                    withFrameNanos { }
                }
            }
    }
    return freeze
}
