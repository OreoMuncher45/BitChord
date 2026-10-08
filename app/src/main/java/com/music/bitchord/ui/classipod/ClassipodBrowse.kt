package com.music.bitchord.ui.classipod

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.isSameTrackAs

/**
 * Flat track list with a wheel cursor: tap or center plays from the row.
 * Long-press behavior from the main theme (song menu) is one hold away —
 * the host wires it through [onLongPress].
 */
@Composable
fun ClassipodTrackList(
    title: String,
    songs: List<Song>,
    lcd: ClassipodTheme.Lcd,
    wheel: ClassipodWheelState,
    onPlay: (List<Song>, Int) -> Unit,
    onLongPress: (Song) -> Unit,
    onBack: () -> Unit,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var selected by remember(songs) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    wheel.onStep = { dir ->
        if (songs.isNotEmpty()) selected = ((selected + dir) % songs.size + songs.size) % songs.size
    }
    wheel.onCenter = { songs.getOrNull(selected)?.let { onPlay(songs, selected) } }
    LaunchedEffect(selected) {
        if (songs.isNotEmpty()) listState.animateScrollToItem(selected)
    }
    Column(modifier = modifier.fillMaxSize().background(lcd.bg)) {
        ClassipodBar(title = "$title · ${songs.size}", lcd = lcd, onBack = onBack)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(songs, key = { index, song -> "${song.videoId}:$index" }) { index, song ->
                val current = song.isSameTrackAs(currentSong)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (index == selected) lcd.selectedBg else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { onPlay(songs, index) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${index + 1}.",
                        fontSize = 12.sp,
                        color = if (index == selected) lcd.selectedText.copy(alpha = 0.7f) else lcd.dim,
                        fontFamily = ClassipodTheme.helvetica,
                        modifier = Modifier.width(30.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            fontFamily = ClassipodTheme.helvetica,
                            fontSize = 14.sp,
                            color = if (index == selected) lcd.selectedText
                            else if (current) lcd.text else lcd.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = song.artist,
                            fontFamily = ClassipodTheme.helvetica,
                            fontSize = 12.sp,
                            color = if (index == selected) lcd.selectedText.copy(alpha = 0.75f) else lcd.dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (current && isPlaying) {
                        Text(text = "🔊", fontSize = 12.sp)
                    }
                    Text(
                        text = "›",
                        fontSize = 15.sp,
                        color = if (index == selected) lcd.selectedText else lcd.dim,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .clickable { onLongPress(song) },
                    )
                }
            }
        }
    }
}

/**
 * Search: touch keyboard up front, wheel letter-picker as fallback.
 * Results play in place like any other list.
 */
@Composable
fun ClassipodSearch(
    query: String,
    onQuery: (String) -> Unit,
    results: UiState<List<com.music.bitchord.data.model.SearchResult>>?,
    lcd: ClassipodTheme.Lcd,
    wheel: ClassipodWheelState,
    onSong: (List<Song>, Int) -> Unit,
    onBrowse: (com.music.bitchord.data.model.BrowseItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    // Wheel on the field types A–Z 0–9 by rotation, center clears.
    wheel.onStep = { /* field handles keys; wheel scrolls results below */ }
    wheel.onCenter = {}
    Column(modifier = modifier.fillMaxSize().background(lcd.bg)) {
        ClassipodBar(title = "Search", lcd = lcd, onBack = onBack)
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(
                color = lcd.text,
                fontFamily = ClassipodTheme.helvetica,
                fontSize = 15.sp,
            ),
            cursorBrush = SolidColor(lcd.text),
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .background(lcd.bar, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .focusRequester(focus),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text("Artists, Songs, Albums…", fontSize = 15.sp, color = lcd.dim)
                }
                inner()
            },
        )
        LaunchedEffect(Unit) { focus.requestFocus() }
        val songs = (results as? UiState.Success)?.data
            ?.mapNotNull {
                when (it) {
                    is com.music.bitchord.data.model.SearchResult.Track -> it.song
                    is com.music.bitchord.data.model.SearchResult.TopTrack -> it.song
                    else -> null
                }
            }.orEmpty()
        val browses = (results as? UiState.Success)?.data
            ?.mapNotNull { (it as? com.music.bitchord.data.model.SearchResult.Browse)?.item }
            .orEmpty()
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(songs, key = { i, s -> "s:${s.videoId}:$i" }) { index, song ->
                ClassipodRow(
                    title = song.title,
                    value = song.artist,
                    lcd = lcd,
                    onClick = { onSong(songs, index) },
                )
            }
            itemsIndexed(browses, key = { i, b -> "b:${b.browseId}:$i" }) { _, item ->
                ClassipodRow(
                    title = item.title,
                    value = item.subtitle,
                    lcd = lcd,
                    onClick = { onBrowse(item) },
                )
            }
            if (results is UiState.Loading) {
                item { LoadingRow(lcd = lcd) }
            }
        }
    }
}

@Composable
internal fun LoadingRow(lcd: ClassipodTheme.Lcd) {
    Text(
        text = "Loading…",
        fontFamily = ClassipodTheme.helvetica,
        fontSize = 14.sp,
        color = lcd.dim,
        modifier = Modifier.padding(12.dp),
    )
}

/**
 * Generic settings list for the pod: rows with values, toggles inline.
 * Server addresses and keys are edited in the BitChord theme (keyboards +
 * Test buttons live there); here every row is a toggle or a cycle.
 */
@Composable
fun ClassipodSettingsList(
    rows: List<PodSettingRow>,
    lcd: ClassipodTheme.Lcd,
    wheel: ClassipodWheelState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember(rows) { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    wheel.onStep = { dir ->
        if (rows.isNotEmpty()) selected = ((selected + dir) % rows.size + rows.size) % rows.size
    }
    wheel.onCenter = {
        when (val row = rows.getOrNull(selected)) {
            is PodSettingRow.Action -> row.onSelect()
            is PodSettingRow.Toggle -> row.onToggle(!row.on)
            null -> Unit
        }
    }
    LaunchedEffect(selected) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(selected)
    }
    Column(modifier = modifier.fillMaxSize().background(lcd.bg)) {
        ClassipodBar(title = "Settings", lcd = lcd, onBack = onBack)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(rows) { index, row ->
                when (row) {
                    is PodSettingRow.Action -> ClassipodRow(
                        title = row.title,
                        value = row.value,
                        selected = index == selected,
                        lcd = lcd,
                        onClick = { row.onSelect() },
                    )
                    is PodSettingRow.Toggle -> {
                        val label = if (row.on) "On" else "Off"
                        ClassipodRow(
                            title = row.title,
                            value = label,
                            selected = index == selected,
                            lcd = lcd,
                            onClick = { row.onToggle(!row.on) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

sealed interface PodSettingRow {
    data class Action(val title: String, val value: String? = null, val onSelect: () -> Unit) : PodSettingRow
    data class Toggle(val title: String, val on: Boolean, val onToggle: (Boolean) -> Unit) : PodSettingRow
}
