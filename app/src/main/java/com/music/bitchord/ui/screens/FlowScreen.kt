package com.music.bitchord.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.data.flow.FLOW_TUNER_GENRES
import com.music.bitchord.data.flow.FlowMood
import com.music.bitchord.data.flow.FlowStatus
import com.music.bitchord.data.flow.FlowTuner
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.isSameTrackAs
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.PlayingAccent
import com.music.bitchord.ui.components.ROW_DIVIDER_INSET
import com.music.bitchord.ui.components.SongRow
import com.music.bitchord.ui.components.songListSkeleton
import kotlin.math.roundToInt

/**
 * Flow, per the approved proposal: dark glass page, left-aligned hero
 * ("Flow ∞" + mood subtitle), white Play pill with Shuffle / Like / Tune
 * circles, mood pill strip, tuner card, mix list.
 *
 * Two rules keep it honest:
 * - On the dark gradient, content is always white — never a theme color
 *   that can go light-on-light.
 * - Everywhere else uses stock Material controls in theme colors. No
 *   hand-rolled accent fills, which is what went white-on-white before.
 *
 * Tuner edits are drafts until Apply: mood, sliders and genre chips only
 * touch local state, and one tap commits everything with a single rebuild.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowScreen(
    tracksState: UiState<List<Song>>,
    mood: FlowMood,
    tuner: FlowTuner,
    status: FlowStatus,
    bannedIds: Set<String>,
    isSaved: Boolean,
    isGrowing: Boolean,
    rebuilding: Boolean,
    listState: LazyListState,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSave: () -> Unit,
    onNewMix: () -> Unit,
    onToggleLike: () -> Unit,
    currentLiked: Boolean,
    onApply: (FlowMood, Float, Float, Set<String>) -> Unit,
    onUnban: (String) -> Unit,
    onBan: (Song) -> Unit,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onRetry: () -> Unit,
    contentPadding: PaddingValues,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Drafts reset whenever the committed config changes (i.e. after Apply).
    var draftMood by remember(mood) { mutableStateOf(mood) }
    var draftDiscovery by remember(tuner.discovery) { mutableFloatStateOf(tuner.discovery) }
    var draftMemory by remember(tuner.memory) { mutableFloatStateOf(tuner.memory) }
    var draftExcluded by remember(tuner.excludedGenres) { mutableStateOf(tuner.excludedGenres) }
    var showTuner by remember { mutableStateOf(false) }
    val dirty = draftMood != mood || draftDiscovery != tuner.discovery ||
        draftMemory != tuner.memory || draftExcluded != tuner.excludedGenres
    val songs = (tracksState as? UiState.Success)?.data.orEmpty()

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        item(key = "flow:hero") {
            FlowHero(
                listState = listState,
                mood = draftMood,
                trackCount = songs.size,
                isSaved = isSaved,
                isGrowing = isGrowing,
                rebuilding = rebuilding,
                tunerDirty = dirty,
                tunerOpen = showTuner,
                currentLiked = currentLiked,
                onPlay = onPlay,
                onShuffle = onShuffle,
                onSave = onSave,
                onToggleLike = onToggleLike,
                onTune = { showTuner = !showTuner },
            )
        }
        item(key = "flow:moods") {
            FlowMoodStrip(
                mood = draftMood,
                onMood = { draftMood = it },
            )
        }
        if (showTuner) {
            item(key = "flow:tuner") {
                FlowTunerCard(
                    tuner = tuner.copy(
                        discovery = draftDiscovery,
                        memory = draftMemory,
                        excludedGenres = draftExcluded,
                    ),
                    onDiscovery = { draftDiscovery = it },
                    onMemory = { draftMemory = it },
                    onToggleGenre = { genre, enabled ->
                        draftExcluded = if (enabled) draftExcluded - genre.lowercase()
                        else draftExcluded + genre.lowercase()
                    },
                    dirty = dirty,
                    rebuilding = rebuilding,
                    onApply = { onApply(draftMood, draftDiscovery, draftMemory, draftExcluded) },
                )
            }
        }
        if (songs.isNotEmpty()) {
            item(key = "flow:tracks-header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.flow_mix_title, songs.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onNewMix) {
                        Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.flow_new_mix))
                    }
                }
            }
        }
        when (tracksState) {
            is UiState.Loading -> songListSkeleton(count = 8, keyPrefix = "skeleton:flow")
            is UiState.Error -> item(key = "flow:message") {
                MessageState(
                    message = tracksState.message,
                    actionLabel = stringResource(R.string.try_again),
                    onAction = onRetry,
                )
            }
            is UiState.Success -> {
                if (songs.isEmpty()) {
                    item(key = "flow:empty") {
                        FlowLockedCard(status = status)
                    }
                } else {
                    items(
                        count = songs.size,
                        key = { "flow:${songs[it].videoId}:$it" },
                    ) { index ->
                        val song = songs[index]
                        SongRow(
                            song = song,
                            onClick = { onSongClick(songs, index) },
                            onLongPress = { onSongLongPress(song) },
                            onSwipeToQueue = { onBan(song) },
                            isCurrent = song.isSameTrackAs(currentSong),
                            isPlaying = song.isSameTrackAs(currentSong) && isPlaying,
                            searchPlayingStyle = true,
                            activeTint = PlayingAccent,
                        )
                        if (index < songs.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }
        if (bannedIds.isNotEmpty()) {
            item(key = "flow:banned") {
                FlowBans(bannedIds = bannedIds, onUnban = onUnban)
            }
        }
        item(key = "flow:spacer") { Spacer(Modifier.height(24.dp)) }
    }
}

private val FLOW_ART_HEIGHT = 340.dp
private val FLOW_HEADER_DROP = 44.dp

/**
 * The hero backdrop: two color blobs drifting on a deep base, drawn on
 * canvas with radial falloff — no blur pass, no image decode, no network.
 * The animation values are read only inside this composable's own
 * drawBehind, so each frame redraws this box and nothing else.
 */
@Composable
private fun FlowGradientBackdrop(modifier: Modifier = Modifier) {
    val drift = rememberInfiniteTransition(label = "flowDrift")
    val t1 by drift.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(14000), RepeatMode.Reverse),
        label = "flowDriftA",
    )
    val t2 by drift.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(19000), RepeatMode.Reverse),
        label = "flowDriftB",
    )
    Box(
        modifier.fillMaxSize().drawBehind {
            drawRect(Color(0xFF14101F))
            val r = size.maxDimension * 0.75f
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color(0xFF7C4DFF).copy(alpha = 0.85f),
                    1f to Color.Transparent,
                    center = Offset(size.width * (0.15f + 0.35f * t1), size.height * (0.25f + 0.2f * t2)),
                    radius = r,
                ),
                radius = r,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color(0xFF00BCD4).copy(alpha = 0.55f),
                    1f to Color.Transparent,
                    center = Offset(size.width * (0.85f - 0.3f * t2), size.height * (0.7f - 0.25f * t1)),
                    radius = r,
                ),
                radius = r,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color(0xFFFF6E40).copy(alpha = 0.35f),
                    1f to Color.Transparent,
                    center = Offset(size.width * (0.5f + 0.25f * (t1 - t2)), size.height * 0.55f),
                    radius = r * 0.7f,
                ),
                radius = r * 0.7f,
            )
        },
    )
}

/**
 * Left-aligned hero over the gradient: FLOW masthead top-left, big title,
 * mood subtitle, white Play pill with Shuffle / Like / Tune glass circles.
 * Everything on the gradient is white — fixed, never themed.
 */
@Composable
private fun FlowHero(
    listState: LazyListState,
    mood: FlowMood,
    trackCount: Int,
    isSaved: Boolean,
    isGrowing: Boolean,
    rebuilding: Boolean,
    tunerDirty: Boolean,
    tunerOpen: Boolean,
    currentLiked: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSave: () -> Unit,
    onToggleLike: () -> Unit,
    onTune: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().clipToBounds()) {
        // Parallax gradient, parked far up once scrolled past.
        val artPx = with(androidx.compose.ui.platform.LocalDensity.current) {
            FLOW_ART_HEIGHT.toPx()
        }
        val top = if (listState.firstVisibleItemIndex == 0) {
            -listState.firstVisibleItemScrollOffset.toFloat()
        } else {
            -artPx * 2f
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(FLOW_ART_HEIGHT)
                .offset { IntOffset(0, top.roundToInt()) },
        ) {
            FlowGradientBackdrop()
            // FLOW masthead, top left.
            Text(
                text = stringResource(R.string.flow).uppercase(),
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 3.sp),
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.TopStart).padding(start = PAGE_GUTTER, top = 12.dp),
            )
            // Bottom scrim into the page behind.
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1.00f to MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                    ),
                ),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 14.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Spacer(Modifier.fillMaxWidth().height(FLOW_ART_HEIGHT - 190.dp + FLOW_HEADER_DROP))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER),
            ) {
                Text(
                    text = stringResource(R.string.flow),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Rounded.AllInclusive, null, tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(30.dp),
                )
            }
            Text(
                text = if (mood == FlowMood.FLOW) {
                    stringResource(R.string.flow_subtitle)
                } else {
                    mood.label + " · " + stringResource(R.string.flow_subtitle)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER),
            )
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = PAGE_GUTTER),
            ) {
                Text(
                    text = when {
                        isSaved -> stringResource(R.string.saved)
                        trackCount > 0 -> stringResource(R.string.flow_temp_meta, trackCount)
                        else -> stringResource(R.string.flow_temp_playlist)
                    },
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
                    color = Color.White.copy(alpha = 0.6f),
                )
                if (isGrowing && !isSaved && trackCount > 0) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.8f)))
                    Text(
                        text = stringResource(R.string.flow_growing),
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
                if (rebuilding) {
                    CircularProgressIndicator(
                        Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The white Play pill. Theme-proof on purpose: white pill +
                // black content reads on every gradient, in every theme.
                Button(
                    onClick = onPlay,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.play),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                FlowGlassCircle(
                    icon = Icons.Rounded.Shuffle,
                    description = stringResource(R.string.shuffle),
                    onClick = onShuffle,
                )
                FlowGlassCircle(
                    icon = if (currentLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    description = stringResource(R.string.like),
                    onClick = onToggleLike,
                    filled = currentLiked,
                )
                Box(contentAlignment = Alignment.TopEnd) {
                    FlowGlassCircle(
                        icon = Icons.Rounded.Tune,
                        description = stringResource(R.string.flow_tuner),
                        onClick = onTune,
                        selected = tunerOpen,
                    )
                    if (tunerDirty) {
                        Box(
                            Modifier
                                .padding(top = 2.dp, end = 2.dp)
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .border(1.5.dp, Color.Black.copy(alpha = 0.4f), CircleShape),
                        )
                    }
                }
            }
            if (isSaved) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onSave,
                    modifier = Modifier.padding(horizontal = PAGE_GUTTER),
                ) {
                    Text(stringResource(R.string.saved))
                }
            } else {
                // Save lives on long-press of the pill row's overflow? No —
                // it gets its own quiet row so it is never hunted for.
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onSave, modifier = Modifier.padding(horizontal = PAGE_GUTTER - 12.dp)) {
                    Text(
                        stringResource(R.string.flow_save),
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}

/** Frosted-glass circle: white wash, hairline rim, white glyph. */
@Composable
private fun FlowGlassCircle(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
    filled: Boolean = false,
    size: Dp = 50.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (selected) 0.22f else 0.12f))
            .border(0.5.dp, Color.White.copy(alpha = 0.25f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, description,
            tint = if (filled) Color(0xFFFF5B6E) else Color.White.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Moods as a horizontal pill strip — one swipe, no page taken. */
@Composable
private fun FlowMoodStrip(
    mood: FlowMood,
    onMood: (FlowMood) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
        Text(
            text = stringResource(R.string.flow_moods),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = PAGE_GUTTER),
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
        ) {
            items(FlowMood.entries.toList()) { m ->
                FilterChip(
                    selected = m == mood,
                    onClick = { onMood(m) },
                    label = { Text(m.label) },
                )
            }
        }
    }
}

@Composable
private fun FlowTunerCard(
    tuner: FlowTuner,
    onDiscovery: (Float) -> Unit,
    onMemory: (Float) -> Unit,
    onToggleGenre: (String, Boolean) -> Unit,
    dirty: Boolean,
    rebuilding: Boolean,
    onApply: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                stringResource(R.string.flow_tuner),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.flow_draft_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    stringResource(R.string.flow_personal),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(R.string.flow_adventurous),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            Slider(value = tuner.discovery, onValueChange = onDiscovery, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.flow_discovery_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    stringResource(R.string.flow_alltime),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stringResource(R.string.flow_recent),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
            Slider(value = tuner.memory, onValueChange = onMemory, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.flow_memory_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.flow_genres),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            @OptIn(ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FLOW_TUNER_GENRES.forEach { genre ->
                    FilterChip(
                        selected = tuner.isGenreEnabled(genre),
                        onClick = { onToggleGenre(genre, !tuner.isGenreEnabled(genre)) },
                        label = { Text(genre) },
                    )
                }
            }
            if (dirty) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onApply,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !rebuilding,
                ) {
                    if (rebuilding) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.flow_apply))
                }
            }
        }
    }
}

@Composable
private fun FlowLockedCard(status: FlowStatus) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.AllInclusive, null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.flow_locked_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.flow_locked_subtitle, status.neededMore),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FlowBans(
    bannedIds: Set<String>,
    onUnban: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Block, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.flow_banned),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            bannedIds.take(20).forEach { id ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(id.take(11), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    AssistChip(onClick = { onUnban(id) }, label = { Text(stringResource(R.string.remove)) })
                }
            }
        }
    }
}

/** Name prompt for saving the temp mix — no more "Flow — Flow". */
@Composable
fun FlowSaveDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.flow_save_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.playlist_name)) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
