/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx AppFloatingNavBar/FloatingNavBar, adapted to BitChord's
 * tab ints, Song model, transport and strings.
 */

@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.music.bitchord.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.model.Song
import com.music.bitchord.ui.SearchSource
import com.music.bitchord.ui.components.backdrop.catalog.components.LocalLiquidBottomTabScale
import com.music.bitchord.ui.components.backdrop.catalog.utils.InteractiveHighlight
import com.music.bitchord.ui.components.foldingtabbar.FoldingTabBar
import com.music.bitchord.ui.components.foldingtabbar.FoldingTabCell
import com.music.bitchord.ui.components.foldingtabbar.FoldingTabGroup
import com.music.bitchord.ui.components.foldingtabbar.FloatingTabBarDefaults
import com.music.bitchord.ui.components.foldingtabbar.clipWhilePressed
import com.music.bitchord.ui.components.floatingtabbar.FloatingTabBarScrollConnection
import com.music.bitchord.ui.player.PlayerDock
import kotlinx.coroutines.delay

private val NavBarShape = RoundedCornerShape(percent = 50)
private val NavBarSearchBarHeight = 48.dp
private val NavBarRowHeight = 68.dp
private const val KeyboardOpenDelayMs = 220L

@Composable
fun GlassFoldingNavBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    scrollConnection: FloatingTabBarScrollConnection,
    search: NavSearchState,
    placeholder: String,
    song: Song?,
    isPlaying: Boolean,
    isLoading: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onExpand: () -> Unit,
    controlsLocked: Boolean = false,
    onBlockedControl: () -> Unit = {},
    dock: PlayerDock? = null,
    pull: MiniPlayerPull? = null,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = search.keyboardActive,
        modifier = modifier,
        transitionSpec = {
            if (targetState) {
                (slideInVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                ) { it / 2 } + fadeIn()) togetherWith
                    (slideOutVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                    ) { it } + fadeOut())
            } else {
                (slideInVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                ) { it } + fadeIn()) togetherWith
                    (slideOutVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                    ) { it / 2 } + fadeOut())
            }
        },
        label = "glassFoldingNavSearchKeyboard",
    ) { keyboardActive ->
        if (keyboardActive) {
            NavBarSearchInputBar(
                state = search,
                placeholder = placeholder,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            GlassFoldingNavBarChrome(
                tabs = tabs,
                selectedIndex = selectedIndex,
                onTabSelected = onTabSelected,
                scrollConnection = scrollConnection,
                search = search,
                placeholder = placeholder,
                song = song,
                isPlaying = isPlaying,
                isLoading = isLoading,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPrevious = onPrevious,
                onExpand = onExpand,
                controlsLocked = controlsLocked,
                onBlockedControl = onBlockedControl,
                dock = dock,
                pull = pull,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun GlassFoldingNavBarChrome(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    scrollConnection: FloatingTabBarScrollConnection,
    search: NavSearchState,
    placeholder: String,
    song: Song?,
    isPlaying: Boolean,
    isLoading: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onExpand: () -> Unit,
    controlsLocked: Boolean,
    onBlockedControl: () -> Unit,
    dock: PlayerDock?,
    pull: MiniPlayerPull?,
    modifier: Modifier,
) {
    BackHandler(enabled = search.visualActive, onBack = search.onExit)

    val contentColor = glassContentColor()
    val selectedColor = contentColor
    val unselectedColor = contentColor.copy(alpha = 0.65f)
    val glassSurface: @Composable () -> Modifier = { Modifier.liquidGlass(shape = NavBarShape) }
    val searchIndex = tabs.lastIndex

    val playerAccessory: (@Composable (Modifier, Boolean) -> Unit)? =
        song?.let { current ->
            { accessoryModifier, isInline ->
                GlassNowPlaying(
                    song = current,
                    isInline = isInline,
                    isPlaying = isPlaying,
                    isLoading = isLoading,
                    contentColor = contentColor,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onExpand = onExpand,
                    controlsLocked = controlsLocked,
                    onBlockedControl = onBlockedControl,
                    dock = dock,
                    pull = pull,
                    modifier = accessoryModifier
                        .fillMaxWidth()
                        .then(glassSurface()),
                )
            }
        }

    FoldingTabBar(
        selectedTabKey = selectedIndex,
        scrollConnection = scrollConnection,
        rowHeight = NavBarRowHeight,
        tabBarContentModifier = glassSurface(),
        colors = FloatingTabBarDefaults.colors(
            backgroundColor = Color.Transparent,
            accessoryBackgroundColor = Color.Transparent,
        ),
        shapes = FloatingTabBarDefaults.shapes(
            tabBarShape = NavBarShape,
            accessoryShape = NavBarShape,
        ),
        sizes = FloatingTabBarDefaults.sizes(),
        elevations = FloatingTabBarDefaults.elevations(
            inlineElevation = 0.dp,
            expandedElevation = 0.dp,
        ),
        modifier = modifier
            .windowInsetsPadding(
                WindowInsets.navigationBars.union(WindowInsets(bottom = 15.dp)),
            )
            .padding(horizontal = BAR_GUTTER)
            .padding(bottom = 2.dp),
        accessory = playerAccessory,
        searchMode = search.visualActive,
        searchBarContent = { contentModifier ->
            SearchBarPlaceholder(
                state = search,
                placeholder = placeholder,
                contentColor = contentColor,
                modifier = contentModifier,
            )
        },
        expandedTabs = { foldingTabs, surfaceBackdrop ->
            // FoldingTabGroup samples this lambda from a LaunchedEffect started
            // once, so a plain closure would keep the first composition's tab
            // forever — the crystal stayed put while navigation changed.
            val selected by rememberUpdatedState(
                foldingTabs.indexOfFirst { it.key == selectedIndex }.coerceAtLeast(0),
            )
            FoldingTabGroup(
                selectedTabIndex = { selected },
                onTabSelected = { index ->
                    foldingTabs.getOrNull(index)?.onClick?.invoke()
                },
                backdrop = LocalAppBackdrop.current,
                surfaceBackdrop = surfaceBackdrop,
                tabsCount = foldingTabs.size,
                accentColor = selectedColor,
                containerColor = Color.Transparent,
                height = NavBarRowHeight,
                indicatorVisible = true,
            ) {
                foldingTabs.forEach { tab ->
                    FoldingTabCell(onClick = tab.onClick, onLongClick = null) {
                        tab.icon()
                        tab.title()
                    }
                }
            }
        },
    ) {
        tabs.forEachIndexed { index, tab ->
            val isSelected = index == selectedIndex
            tab(
                key = index,
                title = {
                    Text(
                        text = tab.label,
                        color = if (isSelected) selectedColor else unselectedColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 10.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    )
                },
                icon = {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = if (isSelected) selectedColor else unselectedColor,
                        modifier = Modifier.size(28.dp),
                    )
                },
                onClick = {
                    when {
                        index == searchIndex ->
                            if (search.visualActive) search.onTapBar() else search.onTapSearchIcon()
                        search.visualActive -> {
                            search.onExit()
                            onTabSelected(index)
                        }
                        else -> onTabSelected(index)
                    }
                },
            )
        }
    }
}

@Composable
private fun SearchBarPlaceholder(
    state: NavSearchState,
    placeholder: String,
    contentColor: Color,
    modifier: Modifier,
) {
    val glowScope = rememberCoroutineScope()
    val glow = remember(glowScope) { InteractiveHighlight(animationScope = glowScope) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clipWhilePressed(glow, NavBarShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = state.onTapBar,
            )
            .padding(start = 4.dp, end = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = state.onExit,
                )
                .padding(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.ArrowBack,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.weight(1f)) {
            if (state.query.isNotEmpty()) {
                Text(
                    text = state.query,
                    style = TextStyle(color = contentColor, fontSize = 17.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = placeholder,
                    style = TextStyle(color = contentColor.copy(alpha = 0.6f), fontSize = 17.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state.canToggleSource) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = state.onToggleSource,
                    )
                    .padding(9.dp),
            ) {
                Icon(
                    imageVector = if (state.searchSource == SearchSource.LIBRARY) {
                        Icons.Rounded.LibraryMusic
                    } else {
                        Icons.Rounded.Language
                    },
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun NavBarSearchInputBar(
    state: NavSearchState,
    placeholder: String,
    modifier: Modifier,
) {
    val contentColor = glassContentColor()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // imePadding already lifts the bar clear of the keyboard. The navigation
    // bar inset is still reported while the IME is up, and adding it on top
    // floated the field a gesture-bar's height above the keyboard instead of
    // docking it. Keep the gesture inset only when no keyboard is showing.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    BackHandler {
        focusManager.clearFocus()
        keyboardController?.hide()
        state.onCloseKeyboard()
    }

    LaunchedEffect(Unit) {
        delay(KeyboardOpenDelayMs)
        state.focusRequester.requestFocus()
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .imePadding()
            .then(if (imeVisible) Modifier else Modifier.navigationBarsPadding())
            .padding(horizontal = 16.dp)
            .padding(top = 6.dp, bottom = if (imeVisible) 0.dp else 2.dp)
            .height(NavBarSearchBarHeight)
            .clip(NavBarShape)
            .liquidGlass(shape = NavBarShape)
            .padding(start = 11.dp, end = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = state.onExit,
                )
                .padding(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.ArrowBack,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            if (state.query.isEmpty()) {
                Text(
                    text = placeholder,
                    style = TextStyle(color = contentColor.copy(alpha = 0.6f), fontSize = 17.sp),
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = state.query,
                onValueChange = state.onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = contentColor, fontSize = 17.sp),
                cursorBrush = SolidColor(contentColor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { submitSearch(state, focusManager, keyboardController) },
                    onDone = { submitSearch(state, focusManager, keyboardController) },
                    onGo = { submitSearch(state, focusManager, keyboardController) },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged {
                        if (it.isFocused && !state.keyboardActive) state.onTapBar()
                    }
                    .focusRequester(state.focusRequester),
            )
        }
        if (state.query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { state.onQueryChange("") },
                    )
                    .padding(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (state.canToggleSource) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = state.onToggleSource,
                    )
                    .padding(12.dp),
            ) {
                Icon(
                    imageVector = if (state.searchSource == SearchSource.LIBRARY) {
                        Icons.Rounded.LibraryMusic
                    } else {
                        Icons.Rounded.Language
                    },
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun submitSearch(
    state: NavSearchState,
    focusManager: androidx.compose.ui.focus.FocusManager,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
) {
    focusManager.clearFocus()
    keyboardController?.hide()
    state.onSubmit()
}
