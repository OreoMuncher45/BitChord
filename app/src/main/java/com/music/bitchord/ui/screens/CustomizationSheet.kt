package com.music.bitchord.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AppUi
import com.music.bitchord.data.settings.LibrarySort
import com.music.bitchord.data.settings.ThemeMode
import com.music.bitchord.ui.components.AurisSlider
import kotlin.math.roundToInt

/**
 * The Customisation hub: one sheet with the four surfaces that have colour or
 * order choices, rather than scattering them through the general settings list.
 * Kept in its own file because the sheet is already the largest composable in
 * the app and every row here would otherwise be compiled into its parent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomizationSheet(
    homeShelfTitles: List<String>,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.custom_now_playing),
        stringResource(R.string.custom_home),
        stringResource(R.string.custom_library),
        stringResource(R.string.custom_theme),
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                text = stringResource(R.string.customization),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 14.dp),
            )
            SegmentedControl(
                options = tabs,
                selectedIndex = tab,
                onSelect = { tab = it },
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Spacer(Modifier.size(18.dp))
            when (tab) {
                0 -> NowPlayingCustomization()
                1 -> HomeCustomization(homeShelfTitles)
                2 -> LibraryCustomization()
                else -> ThemeCustomization()
            }
            Spacer(Modifier.size(28.dp))
        }
    }
}

@Composable
private fun NowPlayingCustomization() {
    val accent by AppSettings.playerAccentColor.collectAsStateWithLifecycle()
    val seekbar by AppSettings.playerSeekbarColor.collectAsStateWithLifecycle()
    val text by AppSettings.playerTextColor.collectAsStateWithLifecycle()
    val lyricsColor by AppSettings.playerLyricsColor.collectAsStateWithLifecycle()
    val recents by AppSettings.recentPlayerColors.collectAsStateWithLifecycle()
    val blockAi by AppSettings.blockAiMusic.collectAsStateWithLifecycle()
    val smoothness by AppSettings.transportSmoothness.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<AppSettings.PlayerColorTarget?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.custom_now_playing_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
        )
        ColorSettingRow(
            label = stringResource(R.string.custom_buttons),
            value = accent,
            onPick = { picking = AppSettings.PlayerColorTarget.ACCENT },
        )
        ColorSettingRow(
            label = stringResource(R.string.custom_seekbar),
            value = seekbar,
            onPick = { picking = AppSettings.PlayerColorTarget.SEEKBAR },
        )
        ColorSettingRow(
            label = stringResource(R.string.custom_text),
            value = text,
            onPick = { picking = AppSettings.PlayerColorTarget.TEXT },
        )
        ColorSettingRow(
            label = stringResource(R.string.custom_lyrics),
            value = lyricsColor,
            onPick = { picking = AppSettings.PlayerColorTarget.LYRICS },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        TransportSmoothnessSetting(smoothness)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.block_ai_music),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.block_ai_music_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = blockAi, onCheckedChange = AppSettings::setBlockAiMusic)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        if (recents.isNotEmpty()) {
            Text(
                text = stringResource(R.string.custom_recent),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 18.dp, start = 4.dp, bottom = 8.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                recents.take(8).forEach { argb ->
                    Swatch(
                        color = Color(argb),
                        selected = argb == accent,
                        onClick = { AppSettings.setPlayerColor(AppSettings.PlayerColorTarget.ACCENT, argb) },
                    )
                }
            }
        }
        TextButton(
            onClick = { AppSettings.resetPlayerColors() },
            modifier = Modifier.padding(top = 14.dp),
        ) {
            Icon(Icons.Rounded.RestartAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.custom_reset_colors))
        }
    }

    picking?.let { target ->
        ColorPickerDialog(
            title = when (target) {
                AppSettings.PlayerColorTarget.ACCENT -> stringResource(R.string.custom_buttons)
                AppSettings.PlayerColorTarget.SEEKBAR -> stringResource(R.string.custom_seekbar)
                AppSettings.PlayerColorTarget.TEXT -> stringResource(R.string.custom_text)
                AppSettings.PlayerColorTarget.LYRICS -> stringResource(R.string.custom_lyrics)
            },
            selected = when (target) {
                AppSettings.PlayerColorTarget.ACCENT -> accent
                AppSettings.PlayerColorTarget.SEEKBAR -> seekbar
                AppSettings.PlayerColorTarget.TEXT -> text
                AppSettings.PlayerColorTarget.LYRICS -> lyricsColor
            },
            onPick = {
                AppSettings.setPlayerColor(target, it)
                picking = null
            },
            onReset = {
                AppSettings.setPlayerColor(target, null)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun TransportSmoothnessSetting(value: Float) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.custom_transport_smoothness),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.custom_transport_smoothness_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "${(value * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AurisSlider(
            value = value,
            onValueChange = AppSettings::setTransportSmoothness,
            valueRange = 0f..1f,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 38.dp, top = 2.dp),
        )
        TextButton(
            onClick = {
                AppSettings.setTransportSmoothness(AppSettings.DEFAULT_TRANSPORT_SMOOTHNESS)
            },
            modifier = Modifier.padding(start = 30.dp),
        ) {
            Icon(Icons.Rounded.RestartAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.custom_smoothness_reset))
        }
    }
}

@Composable
private fun ColorSettingRow(label: String, value: Int?, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Palette,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(
                    color = value?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                ),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.change),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
}

@Composable
private fun ColorPickerDialog(
    title: String,
    selected: Int?,
    onPick: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val presets = remember {
        listOf(
            0xFFFFFFFF.toInt(), 0xFFE5E5E5.toInt(), 0xFF9E9E9E.toInt(),
            0xFF121212.toInt(), 0xFFFF5252.toInt(), 0xFFFF7043.toInt(),
            0xFFFFB300.toInt(), 0xFFFFEB3B.toInt(), 0xFF9CCC65.toInt(),
            0xFF26A69A.toInt(), 0xFF00BCD4.toInt(), 0xFF29B6F6.toInt(),
            0xFF5C6BC0.toInt(), 0xFFAB47BC.toInt(), 0xFFEC407A.toInt(),
            0xFFFF8A80.toInt(), 0xFF80CBC4.toInt(), 0xFFB39DDB.toInt(),
        )
    }
    val recents by AppSettings.recentPlayerColors.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(presets.chunked(6)) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { argb ->
                                Swatch(
                                    color = Color(argb),
                                    selected = argb == selected,
                                    onClick = { onPick(argb) },
                                )
                            }
                        }
                    }
                    if (recents.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.custom_recent),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        items(recents.chunked(6)) { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                row.forEach { argb ->
                                    Swatch(
                                        color = Color(argb),
                                        selected = argb == selected,
                                        onClick = { onPick(argb) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onReset) { Text(stringResource(R.string.custom_use_artwork)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(color, CircleShape)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

@Composable
private fun HomeCustomization(homeShelfTitles: List<String>) {
    val saved by AppSettings.homeShelfOrder.collectAsStateWithLifecycle()
    val homeFlowVisible by AppSettings.homeFlowVisible.collectAsStateWithLifecycle()
    val current = remember(homeShelfTitles, saved) {
        val rank = saved.withIndex().associate { (i, title) -> title to i }
        homeShelfTitles.distinct().sortedBy { rank[it] ?: Int.MAX_VALUE }
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.custom_home_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.show_flow_on_home),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = homeFlowVisible,
                onCheckedChange = AppSettings::setHomeFlowVisible,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        if (current.isEmpty()) {
            Text(
                text = stringResource(R.string.custom_home_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(4.dp, 16.dp),
            )
        } else {
            current.forEachIndexed { index, title ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "$index. $title",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                    )
                    IconButton(
                        enabled = index > 0,
                        onClick = { AppSettings.setHomeShelfOrder(current.moved(index, index - 1)) },
                    ) {
                        Icon(Icons.Rounded.ArrowUpward, contentDescription = null)
                    }
                    IconButton(
                        enabled = index < current.lastIndex,
                        onClick = { AppSettings.setHomeShelfOrder(current.moved(index, index + 1)) },
                    ) {
                        Icon(Icons.Rounded.ArrowDownward, contentDescription = null)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            }
            TextButton(onClick = { AppSettings.setHomeShelfOrder(emptyList()) }) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.custom_reset_order))
            }
        }
    }
}

@Composable
private fun LibraryCustomization() {
    val sort by AppSettings.librarySort.collectAsStateWithLifecycle()
    val replayVisible by AppSettings.libraryReplayVisible.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.custom_library_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.custom_show_replay),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.custom_show_replay_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = replayVisible,
                onCheckedChange = AppSettings::setLibraryReplayVisible,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        Text(
            text = stringResource(R.string.custom_library_sort),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(4.dp, 16.dp, 4.dp, 8.dp),
        )
        SegmentedControl(
            options = listOf(
                stringResource(R.string.sort_default),
                stringResource(R.string.sort_title_asc),
                stringResource(R.string.sort_title_desc),
            ),
            selectedIndex = LibrarySort.entries.indexOf(sort),
            onSelect = { AppSettings.setLibrarySort(LibrarySort.entries[it]) },
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun ThemeCustomization() {
    val appUi by AppSettings.appUi.collectAsStateWithLifecycle()
    val theme by AppSettings.themeMode.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.custom_theme_help),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
        )
        Text(
            text = stringResource(R.string.app_ui),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(4.dp, 4.dp, 4.dp, 8.dp),
        )
        SegmentedControl(
            options = AppUi.entries.map { it.label },
            selectedIndex = AppUi.entries.indexOf(appUi),
            onSelect = { AppSettings.setAppUi(AppUi.entries[it]) },
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.size(18.dp))
        Text(
            text = stringResource(R.string.theme),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(4.dp, 4.dp, 4.dp, 8.dp),
        )
        SegmentedControl(
            options = listOf(
                stringResource(R.string.system),
                stringResource(R.string.light),
                stringResource(R.string.dark),
            ),
            selectedIndex = ThemeMode.entries.indexOf(theme),
            onSelect = { AppSettings.setThemeMode(ThemeMode.entries[it]) },
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

private fun List<String>.moved(from: Int, to: Int): List<String> {
    if (from == to || from !in indices || to !in indices) return this
    val out = toMutableList()
    val item = out.removeAt(from)
    out.add(to, item)
    return out
}
