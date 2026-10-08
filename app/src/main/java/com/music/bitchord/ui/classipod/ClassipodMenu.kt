package com.music.bitchord.ui.classipod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * One iPod screen. Menus push children; leaves perform actions. The whole
 * streaming workflow is reachable: browse, search, Flow, queue, likes,
 * downloads, sleep, quality, sources, theme.
 */
sealed interface ClassipodPage {
    val title: String

    data class Menu(
        override val title: String,
        val items: List<MenuItem>,
    ) : ClassipodPage

    data class Tracks(
        override val title: String,
        val songs: List<com.music.bitchord.data.model.Song>,
        /** Where "play from here" points: the queue this list becomes. */
        val source: com.music.bitchord.playback.QueueSource,
    ) : ClassipodPage

    data object NowPlaying : ClassipodPage {
        override val title: String get() = "Now Playing"
    }

    data object FlowHome : ClassipodPage {
        override val title: String get() = "Flow"
    }

    data object FlowTuner : ClassipodPage {
        override val title: String get() = "Flow Tuner"
    }

    data object Search : ClassipodPage {
        override val title: String get() = "Search"
    }

    data object UpNext : ClassipodPage {
        override val title: String get() = "Up Next"
    }

    data object SleepTimer : ClassipodPage {
        override val title: String get() = "Sleep Timer"
    }

    data object PodSettings : ClassipodPage {
        override val title: String get() = "Settings"
    }
}

data class MenuItem(
    val title: String,
    val value: String? = null,
    val onSelect: () -> Unit = {},
)

/**
 * Renders a [ClassipodPage.Menu]: header bar + selectable rows with a
 * wheel-driven cursor. Touch taps select directly; the wheel moves
 * [selected] and center confirms — both call the same [MenuItem.onSelect].
 */
@Composable
fun ClassipodMenuPage(
    page: ClassipodPage.Menu,
    lcd: ClassipodTheme.Lcd,
    wheel: ClassipodWheelState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember(page) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    wheel.onStep = { dir ->
        if (page.items.isNotEmpty()) {
            selected = ((selected + dir) % page.items.size + page.items.size) % page.items.size
        }
    }
    wheel.onCenter = { page.items.getOrNull(selected)?.onSelect?.invoke() }
    LaunchedEffect(selected) {
        if (page.items.isNotEmpty()) listState.animateScrollToItem(selected)
    }
    Column(modifier = modifier.fillMaxSize().background(lcd.bg)) {
        ClassipodBar(title = page.title, lcd = lcd, onBack = onBack)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(page.items) { index, item ->
                ClassipodRow(
                    title = item.title,
                    value = item.value,
                    selected = index == selected,
                    lcd = lcd,
                    onClick = item.onSelect,
                )
            }
        }
    }
}
