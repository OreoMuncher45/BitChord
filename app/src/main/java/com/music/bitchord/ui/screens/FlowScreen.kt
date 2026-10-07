package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.flow.FLOW_TUNER_GENRES
import com.music.bitchord.data.flow.FlowMood
import com.music.bitchord.data.flow.FlowStatus
import com.music.bitchord.data.flow.FlowTuner
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.isSameTrackAs
import com.music.bitchord.ui.components.MessageState
import com.music.bitchord.ui.components.PlayingAccent
import com.music.bitchord.ui.components.ROW_DIVIDER_INSET
import com.music.bitchord.ui.components.SongRow
import com.music.bitchord.ui.components.songListSkeleton

/**
 * Flow temporary playlist: auto-created on tap, keeps growing via AutoPlay
 * until saved. Mood chips + discovery/personalization + genre tuner all
 * rebuild the mix live.
 *
 * Reference art (Deezer Flow, Google):
 * - https://www.deezer.com/explore/en-us/features/flow (official page)
 * - https://support.deezer.com/hc/en-gb/articles/115004367189 (moods wheel)
 * - https://mozaika.design/inspiration/deezer-mobile-deezer-moods-flow-based-selection-wheel
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    onSave: () -> Unit,
    onMood: (FlowMood) -> Unit,
    onDiscovery: (Float) -> Unit,
    onFavoritesBias: (Float) -> Unit,
    onToggleGenre: (String, Boolean) -> Unit,
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
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item(key = "flow:header") {
            FlowHeader(
                status = status,
                isSaved = isSaved,
                isGrowing = isGrowing,
                rebuilding = rebuilding,
                onPlay = onPlay,
                onSave = onSave,
            )
        }
        item(key = "flow:moods") {
            FlowMoods(mood = mood, onMood = onMood)
        }
        item(key = "flow:tuner") {
            FlowTunerCard(
                tuner = tuner,
                onDiscovery = onDiscovery,
                onFavoritesBias = onFavoritesBias,
                onToggleGenre = onToggleGenre,
            )
        }
        if (bannedIds.isNotEmpty()) {
            item(key = "flow:banned") {
                FlowBans(bannedIds = bannedIds, onUnban = onUnban)
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
                val songs = tracksState.data
                if (songs.isEmpty()) {
                    item(key = "flow:empty") {
                        MessageState(
                            message = stringResource(R.string.flow_locked_title),
                            actionLabel = null,
                            onAction = {},
                        )
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
        item(key = "flow:spacer") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun FlowHeader(
    status: FlowStatus,
    isSaved: Boolean,
    isGrowing: Boolean,
    rebuilding: Boolean,
    onPlay: () -> Unit,
    onSave: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(64.dp).clip(CircleShape)
                        .background(
                            Brush.sweepGradient(
                                listOf(
                                    Color(0xFF7C4DFF), Color(0xFF00BCD4),
                                    Color(0xFF69F0AE), Color(0xFFFFD54F),
                                    Color(0xFFFF6E40), Color(0xFF7C4DFF),
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.AllInclusive, null, tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.flow), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        if (isSaved) stringResource(R.string.saved) else stringResource(R.string.flow_temp_playlist),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isGrowing && !isSaved) {
                        Text(
                            stringResource(R.string.flow_growing),
                            style = MaterialTheme.typography.bodySmall,
                            color = PlayingAccent,
                        )
                    }
                }
                if (rebuilding) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.play))
                }
                OutlinedButton(onClick = onSave, enabled = !isSaved, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Save, null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(if (isSaved) R.string.saved else R.string.flow_save))
                }
            }
            if (!status.unlocked) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.flow_locked_subtitle, status.neededMore),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun FlowMoods(mood: FlowMood, onMood: (FlowMood) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.flow_moods), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // First row: wheel center + 3 moods; second row handled by wrap below via Column
        }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FlowMood.entries.forEach { m ->
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
    onFavoritesBias: (Float) -> Unit,
    onToggleGenre: (String, Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.flow_tuner), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.flow_personal), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(80.dp))
                Slider(value = tuner.discovery, onValueChange = onDiscovery, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.flow_adventurous), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(90.dp))
            }
            Text(stringResource(R.string.flow_discovery_subtitle), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.flow_favorites), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(80.dp))
                Slider(value = tuner.favoritesBias, onValueChange = onFavoritesBias, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.flow_genres), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            FLOW_TUNER_GENRES.forEach { genre ->
                val enabled = tuner.isGenreEnabled(genre)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(genre, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = enabled, onCheckedChange = { onToggleGenre(genre, it) })
                }
            }
        }
    }
}

@Composable
private fun FlowBans(bannedIds: Set<String>, onUnban: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
            if (bannedIds.isEmpty()) {
                Text(stringResource(R.string.flow_empty_banned), style = MaterialTheme.typography.bodySmall)
            } else {
                bannedIds.take(20).forEach { id ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(id.take(11), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        AssistChip(onClick = { onUnban(id) }, label = { Text(stringResource(R.string.remove)) })
                    }
                }
            }
        }
    }
}
