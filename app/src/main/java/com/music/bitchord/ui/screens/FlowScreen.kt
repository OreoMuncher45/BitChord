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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
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
import com.music.bitchord.ui.components.SongRow
import com.music.bitchord.ui.components.songListSkeleton
import com.music.bitchord.ui.theme.ArtworkPalette
import com.music.bitchord.ui.theme.rememberArtworkPalette

/**
 * Flow, dressed like an Apple Music page: full-bleed artwork hero washing
 * into the page tint, centered title, and the Play • Shuffle • Save • Tune
 * circle row — the same furniture as [DetailScreen]'s release pages.
 *
 * Tuner edits are drafts until Apply: mood, sliders and genre switches only
 * touch local state, and one tap commits everything with a single rebuild —
 * dragging a slider never replays the whole mix under your thumb. The tuner
 * itself lives behind the Tune circle so the page opens clean.
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
    var draftFavBias by remember(tuner.favoritesBias) { mutableFloatStateOf(tuner.favoritesBias) }
    var draftExcluded by remember(tuner.excludedGenres) { mutableStateOf(tuner.excludedGenres) }
    var showTuner by remember { mutableStateOf(false) }
    val dirty = draftMood != mood || draftDiscovery != tuner.discovery ||
        draftFavBias != tuner.favoritesBias || draftExcluded != tuner.excludedGenres
    val songs = (tracksState as? UiState.Success)?.data.orEmpty()
    // No borrowed art, no decode: the hero is a pure GPU gradient, so the
    // page opens instantly even with an empty mix. Palette falls back to
    // theme colors without artwork to read.
    val palette = rememberArtworkPalette(null)

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(palette.background),
        contentPadding = contentPadding,
    ) {
        item(key = "flow:hero") {
            FlowHero(
                palette = palette,
                listState = listState,
                mood = draftMood,
                trackCount = songs.size,
                isSaved = isSaved,
                isGrowing = isGrowing,
                rebuilding = rebuilding,
                tunerDirty = dirty,
                tunerOpen = showTuner,
                onPlay = onPlay,
                onShuffle = onShuffle,
                onSave = onSave,
                onTune = { showTuner = !showTuner },
            )
        }
        item(key = "flow:moods") {
            FlowMoodStrip(
                mood = draftMood,
                palette = palette,
                onMood = { draftMood = it },
            )
        }
        if (showTuner) {
            item(key = "flow:tuner") {
                FlowTunerCard(
                    tuner = tuner.copy(
                        discovery = draftDiscovery,
                        favoritesBias = draftFavBias,
                        excludedGenres = draftExcluded,
                    ),
                    palette = palette,
                    onDiscovery = { draftDiscovery = it },
                    onFavoritesBias = { draftFavBias = it },
                    onToggleGenre = { genre, enabled ->
                        draftExcluded = if (enabled) draftExcluded - genre.lowercase()
                        else draftExcluded + genre.lowercase()
                    },
                    dirty = dirty,
                    rebuilding = rebuilding,
                    onApply = { onApply(draftMood, draftDiscovery, draftFavBias, draftExcluded) },
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
                        color = palette.onBackground,
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
                        FlowLockedCard(status = status, palette = palette)
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
                            rowBackground = Color.Transparent,
                            subtitleColor = palette.onBackgroundVariant,
                            isCurrent = song.isSameTrackAs(currentSong),
                            isPlaying = song.isSameTrackAs(currentSong) && isPlaying,
                            searchPlayingStyle = true,
                            activeTint = palette.accent,
                        )
                        if (index < songs.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = PAGE_GUTTER + 56.dp),
                                thickness = 0.5.dp,
                                color = palette.divider,
                            )
                        }
                    }
                }
            }
        }
        if (bannedIds.isNotEmpty()) {
            item(key = "flow:banned") {
                FlowBans(bannedIds = bannedIds, palette = palette, onUnban = onUnban)
            }
        }
        item(key = "flow:spacer") { Spacer(Modifier.height(24.dp)) }
    }
}

private val FLOW_ART_HEIGHT = 340.dp
private val FLOW_HEADER_DROP = 44.dp

/**
 * Full-bleed artwork washing into the page tint, centered title, accent
 * mood line, small-caps meta, and the Apple action row: Save • Shuffle •
 * Play • Tune. Mirrors the release header's construction (parallax art,
 * eased scrim, controls pinned under the art with zero gap to the rows).
 */
/**
 * Text color that survives on top of [accent]: the theme's primary is white
 * in dark mode, so hardcoding white content went white-on-white. Luminance
 * decides, per surface, every time.
 */
private fun contentOn(accent: Color): Color =
    if (accent.luminance() > 0.5f) Color.Black else Color.White

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

@Composable
private fun FlowHero(
    palette: ArtworkPalette,
    listState: LazyListState,
    mood: FlowMood,
    trackCount: Int,
    isSaved: Boolean,
    isGrowing: Boolean,
    rebuilding: Boolean,
    tunerDirty: Boolean,
    tunerOpen: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSave: () -> Unit,
    onTune: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().clipToBounds()) {
        // Parallax art, parked far up once scrolled past.
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
            // The ∞ mark, then the page wash taking over at the foot.
            Icon(
                Icons.Rounded.AllInclusive, null, tint = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.size(64.dp).align(Alignment.Center),
            )
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        0.80f to palette.wash.copy(alpha = 0.72f),
                        1.00f to palette.wash,
                    ),
                ),
            )
            // FLOW, top left, small caps — the page's own masthead.
            Text(
                text = stringResource(R.string.flow).uppercase(),
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 3.sp),
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.TopStart).padding(start = PAGE_GUTTER, top = 12.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.fillMaxWidth().height(FLOW_ART_HEIGHT - 148.dp + FLOW_HEADER_DROP))
            Text(
                text = stringResource(R.string.flow),
                style = MaterialTheme.typography.headlineMedium,
                color = palette.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (mood == FlowMood.FLOW) {
                    stringResource(R.string.flow_subtitle)
                } else {
                    mood.label
                },
                style = MaterialTheme.typography.titleMedium,
                color = palette.accent,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = PAGE_GUTTER),
            )
            Spacer(Modifier.height(5.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            ) {
                Text(
                    text = when {
                        isSaved -> stringResource(R.string.saved)
                        trackCount > 0 -> stringResource(R.string.flow_temp_meta, trackCount)
                        else -> stringResource(R.string.flow_temp_playlist)
                    },
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
                    color = palette.onBackgroundVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isGrowing && !isSaved && trackCount > 0) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(palette.accent))
                    Text(
                        text = stringResource(R.string.flow_growing),
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
                        color = palette.accent,
                    )
                }
                if (rebuilding) {
                    CircularProgressIndicator(
                        Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = palette.onBackgroundVariant,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlowCircle(
                    icon = if (isSaved) Icons.Rounded.AllInclusive else Icons.Rounded.Save,
                    description = stringResource(if (isSaved) R.string.saved else R.string.flow_save),
                    palette = palette,
                    enabled = !isSaved,
                    onClick = onSave,
                )
                FlowCircle(
                    icon = Icons.Rounded.Shuffle,
                    description = stringResource(R.string.shuffle),
                    palette = palette,
                    onClick = onShuffle,
                )
                // The Play pill: the one filled control, twice the presence.
                Button(
                    onClick = onPlay,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accent,
                        contentColor = contentOn(palette.accent),
                    ),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.play),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Box(contentAlignment = Alignment.TopEnd) {
                    FlowCircle(
                        icon = Icons.Rounded.Tune,
                        description = stringResource(R.string.flow_tuner),
                        palette = palette,
                        selected = tunerOpen,
                        onClick = onTune,
                    )
                    if (tunerDirty) {
                        Box(
                            Modifier
                                .padding(top = 2.dp, end = 2.dp)
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(palette.accent)
                                .border(1.5.dp, palette.wash, CircleShape),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FlowCircle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    palette: ArtworkPalette,
    onClick: () -> Unit,
    enabled: Boolean = true,
    selected: Boolean = false,
    size: Dp = 50.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (selected) palette.accent.copy(alpha = 0.25f) else palette.elevated.copy(alpha = 0.6f))
            .border(0.5.dp, palette.divider, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .let { if (!enabled) it else it },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, description,
            tint = if (selected) palette.accent else palette.onBackground.copy(alpha = if (enabled) 1f else 0.4f),
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Moods as a horizontal pill strip — one swipe, no page taken. */
@Composable
private fun FlowMoodStrip(
    mood: FlowMood,
    palette: ArtworkPalette,
    onMood: (FlowMood) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
        Text(
            text = stringResource(R.string.flow_moods),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = palette.onBackground,
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
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = palette.elevated.copy(alpha = 0.6f),
                        labelColor = palette.onBackgroundVariant,
                        selectedContainerColor = palette.accent,
                        selectedLabelColor = contentOn(palette.accent),
                    ),
                )
            }
        }
    }
}

@Composable
private fun FlowTunerCard(
    tuner: FlowTuner,
    palette: ArtworkPalette,
    onDiscovery: (Float) -> Unit,
    onFavoritesBias: (Float) -> Unit,
    onToggleGenre: (String, Boolean) -> Unit,
    dirty: Boolean,
    rebuilding: Boolean,
    onApply: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = palette.elevated.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Tune, null, tint = palette.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.flow_tuner),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.onBackground,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.flow_draft_hint),
                style = MaterialTheme.typography.bodySmall,
                color = palette.onBackgroundVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.flow_personal),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onBackgroundVariant,
                    modifier = Modifier.width(80.dp),
                )
                Slider(
                    value = tuner.discovery,
                    onValueChange = onDiscovery,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = palette.accent,
                        activeTrackColor = palette.accent,
                    ),
                )
                Text(
                    stringResource(R.string.flow_adventurous),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onBackgroundVariant,
                )
            }
            Text(
                stringResource(R.string.flow_discovery_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = palette.onBackgroundVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.flow_favorites),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onBackgroundVariant,
                    modifier = Modifier.width(80.dp),
                )
                Slider(
                    value = tuner.favoritesBias,
                    onValueChange = onFavoritesBias,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = palette.accent,
                        activeTrackColor = palette.accent,
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.flow_genres),
                style = MaterialTheme.typography.titleSmall,
                color = palette.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            FLOW_TUNER_GENRES.forEach { genre ->
                val enabled = tuner.isGenreEnabled(genre)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        genre,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.onBackground,
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = { onToggleGenre(genre, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = contentOn(palette.accent),
                            checkedTrackColor = palette.accent,
                            checkedBorderColor = palette.accent,
                        ),
                    )
                }
            }
            if (dirty) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onApply,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !rebuilding,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accent,
                        contentColor = contentOn(palette.accent),
                    ),
                ) {
                    Icon(Icons.Rounded.Refresh, null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.flow_apply))
                }
            }
        }
    }
}

@Composable
private fun FlowLockedCard(status: FlowStatus, palette: ArtworkPalette) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = palette.elevated.copy(alpha = 0.55f)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.AllInclusive, null, tint = palette.accent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.flow_locked_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.flow_locked_subtitle, status.neededMore),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBackgroundVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FlowBans(
    bannedIds: Set<String>,
    palette: ArtworkPalette,
    onUnban: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = palette.elevated.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Block, null, tint = palette.onBackgroundVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.flow_banned),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.onBackground,
                )
            }
            Spacer(Modifier.height(8.dp))
            bannedIds.take(20).forEach { id ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        id.take(11),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.onBackgroundVariant,
                    )
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
