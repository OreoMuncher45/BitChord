package com.music.bitchord.ui.classipod

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.isSameTrackAs
import kotlinx.coroutines.launch

/**
 * Flat track list with a wheel cursor: tap or center plays from the row
 * and opens Now Playing, exactly like Classipod's song lists.
 * Long-press opens the song menu.
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
                        .background(if (index == selected) lcd.selectedBg else Color.Transparent)
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
                        PodMarquee(
                            text = song.title,
                            color = if (index == selected) lcd.selectedText else lcd.text,
                            fontSize = 14.sp,
                            fontFamily = ClassipodTheme.helvetica,
                            fontWeight = FontWeight.Normal,
                            scroll = index == selected,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PodMarquee(
                            text = song.artist,
                            color = if (index == selected) lcd.selectedText.copy(alpha = 0.75f) else lcd.dim,
                            fontSize = 12.sp,
                            fontFamily = ClassipodTheme.helvetica,
                            fontWeight = FontWeight.Normal,
                            scroll = index == selected,
                            modifier = Modifier.fillMaxWidth(),
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
 * A self-paging track list for long playlists. Opens on the first page
 * (~100 rows) and follows continuations as the cursor nears the end, so
 * a 1,200-song Liked list never waits on all twelve round trips up
 * front. The header prints the feed's own total when the header names
 * one ("1,247 songs"), a growing floor with "+" while pages remain, and
 * the exact count once the last page lands. [onTotal] fires only with
 * exact figures, for menu badges that must never show the cap.
 */
@Composable
fun ClassipodPagedTracks(
    title: String,
    browseId: String,
    lcd: ClassipodTheme.Lcd,
    wheel: ClassipodWheelState,
    onPlay: (List<Song>, Int) -> Unit,
    onLongPress: (Song) -> Unit,
    onTotal: (Int) -> Unit,
    onBack: () -> Unit,
    currentSong: Song? = null,
    isPlaying: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var songs by remember(browseId) { mutableStateOf(emptyList<Song>()) }
    var token by remember(browseId) { mutableStateOf<String?>(FIRST_PAGE) }
    var loading by remember(browseId) { mutableStateOf(false) }
    var total by remember(browseId) { mutableStateOf<Int?>(null) }
    val exhausted = token == null && songs.isNotEmpty()

    fun loadMore() {
        val t = token ?: return
        if (loading) return
        loading = true
        scope.launch {
            val page = if (t == FIRST_PAGE) {
                com.music.bitchord.data.YtMusicRepository.browseSongs(browseId)
            } else {
                com.music.bitchord.data.YtMusicRepository.moreSongs(t)
            }.getOrNull()
            if (page != null) {
                songs = (songs + page.songs).distinctBy { it.videoId }
                token = page.continuation
                page.header?.subtitle?.let { parsePlaylistCount(it)?.let { n -> total = n } }
                if (page.continuation == null) total = songs.size
                total?.let(onTotal)
            }
            loading = false
        }
    }
    LaunchedEffect(browseId) { loadMore() }

    var selected by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    wheel.onStep = { dir ->
        if (songs.isNotEmpty()) selected = ((selected + dir) % songs.size + songs.size) % songs.size
    }
    wheel.onCenter = { songs.getOrNull(selected)?.let { onPlay(songs, selected) } }
    LaunchedEffect(selected) {
        if (songs.isNotEmpty()) listState.animateScrollToItem(selected)
    }
    val lastVisible by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
    }
    LaunchedEffect(lastVisible, songs.size) {
        if (songs.isNotEmpty() && lastVisible >= songs.size - 8) loadMore()
    }

    val headerTitle = when {
        total != null -> "$title · $total"
        token == null -> "$title · ${songs.size}"
        else -> "$title · ${songs.size}+"
    }
    Column(modifier = modifier.fillMaxSize().background(lcd.bg)) {
        ClassipodBar(title = headerTitle, lcd = lcd, onBack = onBack)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(songs, key = { index, song -> "${song.videoId}:$index" }) { index, song ->
                val current = song.isSameTrackAs(currentSong)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (index == selected) lcd.selectedBg else Color.Transparent)
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
                        PodMarquee(
                            text = song.title,
                            color = if (index == selected) lcd.selectedText else lcd.text,
                            fontSize = 14.sp,
                            fontFamily = ClassipodTheme.helvetica,
                            fontWeight = FontWeight.Normal,
                            scroll = index == selected,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PodMarquee(
                            text = song.artist,
                            color = if (index == selected) lcd.selectedText.copy(alpha = 0.75f) else lcd.dim,
                            fontSize = 12.sp,
                            fontFamily = ClassipodTheme.helvetica,
                            fontWeight = FontWeight.Normal,
                            scroll = index == selected,
                            modifier = Modifier.fillMaxWidth(),
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
            if (loading || songs.isEmpty()) {
                item(key = "paged:loading") { LoadingRow(lcd = lcd) }
            }
        }
    }
}

/** First-page sentinel: null token means the feed is exhausted. */
private const val FIRST_PAGE = "__first__"

/** "Playlist · 1,247 songs" → 1247. Null when the header names no count. */
private fun parsePlaylistCount(subtitle: String): Int? =
    Regex("""(\d[\d,]*)\s+songs?\b""", RegexOption.IGNORE_CASE)
        .find(subtitle)
        ?.groupValues
        ?.get(1)
        ?.filter(Char::isDigit)
        ?.toIntOrNull()

/**
 * Search, 1-for-1 from Classipod's SearchScreen:
 * - Row 0 is the default tile: shows the query + hit count, tapping it
 *   toggles the bottom input bar open/closed.
 * - The input bar overlays the bottom; results filter live as you type.
 * - Picking a track plays it and opens Now Playing; artists/albums open
 *   their pages; long-press opens the song menu.
 */
@Composable
fun ClassipodSearch(
    query: String,
    onQuery: (String) -> Unit,
    results: UiState<List<com.music.bitchord.data.model.SearchResult>>?,
    lcd: ClassipodTheme.Lcd,
    wheel: ClassipodWheelState,
    onSong: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onBrowse: (com.music.bitchord.data.model.BrowseItem) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var inputOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(0) }
    val songs = (results as? UiState.Success)?.data
        ?.mapNotNull {
            when (it) {
                is com.music.bitchord.data.model.SearchResult.Track -> SearchEntry.Song(it.song)
                is com.music.bitchord.data.model.SearchResult.TopTrack -> SearchEntry.Song(it.song)
                is com.music.bitchord.data.model.SearchResult.Browse ->
                    SearchEntry.Browse(it.item)
            }
        }.orEmpty()
    // Row 0 = default tile, rows 1..n = hits.
    val rowCount = songs.size + 1
    wheel.onStep = { dir ->
        selected = ((selected + dir) % rowCount + rowCount) % rowCount
    }
    wheel.onCenter = {
        if (selected == 0) {
            inputOpen = !inputOpen
        } else {
            songs.getOrNull(selected - 1)?.let { entry ->
                when (entry) {
                    is SearchEntry.Song -> {
                        val list = songs.filterIsInstance<SearchEntry.Song>().map { it.song }
                        val at = list.indexOfFirst { it.videoId == entry.song.videoId }.takeIf { it >= 0 } ?: 0
                        onSong(list, at)
                    }
                    is SearchEntry.Browse -> onBrowse(entry.item)
                }
            }
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(selected) { listState.animateScrollToItem(selected) }

    Box(modifier = modifier.fillMaxSize().background(lcd.bg)) {
        Column(Modifier.fillMaxSize()) {
            ClassipodBar(
                title = if (query.isBlank()) "Search" else "Results: ${songs.size}",
                lcd = lcd,
                onBack = onBack,
            )
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                item(key = "search:default") {
                    ClassipodRow(
                        title = query.ifBlank { "Search" },
                        value = if (query.isBlank()) null else "${songs.size}",
                        selected = selected == 0,
                        lcd = lcd,
                        onClick = { inputOpen = !inputOpen },
                    )
                }
                itemsIndexed(songs, key = { i, e -> "hit:$i:${e.key()}" }) { index, entry ->
                    val row = index + 1
                    when (entry) {
                        is SearchEntry.Song -> ClassipodRow(
                            title = entry.song.title,
                            value = entry.song.artist,
                            selected = selected == row,
                            lcd = lcd,
                            onClick = {
                                val list = songs.filterIsInstance<SearchEntry.Song>().map { it.song }
                                val at = list.indexOfFirst { it.videoId == entry.song.videoId }
                                    .takeIf { it >= 0 } ?: 0
                                onSong(list, at)
                            },
                        )
                        is SearchEntry.Browse -> ClassipodRow(
                            title = entry.item.title,
                            value = entry.item.subtitle,
                            selected = selected == row,
                            lcd = lcd,
                            onClick = { onBrowse(entry.item) },
                        )
                    }
                }
            if (results is UiState.Loading) {
                item(key = "search:loading") { LoadingRow(lcd = lcd) }
            }
        }
        // Rotary letter strip, like the real thing: tap a letter to search it.
        LetterStrip(
            lcd = lcd,
            onLetter = onQuery,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
    }
        // Bottom input bar overlay, toggled by row 0.
        if (inputOpen) {
            SearchInputBar(
                query = query,
                onQuery = onQuery,
                lcd = lcd,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

private sealed interface SearchEntry {
    data class Song(val song: com.music.bitchord.data.model.Song) : SearchEntry
    data class Browse(val item: com.music.bitchord.data.model.BrowseItem) : SearchEntry

    fun key(): String = when (this) {
        is Song -> "s:${song.videoId}"
        is Browse -> "b:${item.browseId}"
    }
}

/** Bottom overlay text bar: tap to type, live results above. */
@Composable
private fun SearchInputBar(
    query: String,
    onQuery: (String) -> Unit,
    lcd: ClassipodTheme.Lcd,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
            color = lcd.text,
            fontFamily = ClassipodTheme.helvetica,
            fontSize = 15.sp,
        ),
        cursorBrush = SolidColor(lcd.text),
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(lcd.bar, RoundedCornerShape(8.dp))
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
}

@Composable
private fun LetterStrip(
    lcd: ClassipodTheme.Lcd,
    onLetter: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val letters = remember { ('A'..'Z').map { it.toString() } }
    androidx.compose.foundation.lazy.LazyRow(
        modifier = modifier.padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(letters) { _, letter ->
            Text(
                text = letter,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = lcd.dim,
                fontFamily = ClassipodTheme.helvetica,
                modifier = Modifier.clickable { onLetter(letter) }.padding(2.dp),
            )
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
