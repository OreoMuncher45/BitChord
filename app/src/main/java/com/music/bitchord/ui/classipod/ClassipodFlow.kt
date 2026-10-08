package com.music.bitchord.ui.classipod

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.data.flow.FLOW_TUNER_GENRES
import com.music.bitchord.data.flow.FlowMood
import com.music.bitchord.data.flow.FlowStatus
import com.music.bitchord.data.flow.FlowTuner

/**
 * Flow on iPod: Start Flow, mood list, tuner rows, genres checklist,
 * Apply, New mix, Save — every option from the main UI, dressed as menus.
 *
 * Sliders become stepped rows (wheel moves the value, touch taps −/+):
 * an iPod has no sliders, but the values are the same ones Apply commits.
 */
@Composable
fun ClassipodFlowHome(
    mood: FlowMood,
    tuner: FlowTuner,
    status: FlowStatus,
    trackCount: Int,
    lcd: ClassipodTheme.Lcd,
    wheel: ClassipodWheelState,
    onStartFlow: () -> Unit,
    onApply: (FlowMood, Float, Float, Set<String>) -> Unit,
    onNewMix: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draftMood by remember(mood) { mutableStateOf(mood) }
    var draftDiscovery by remember(tuner.discovery) { mutableFloatStateOf(tuner.discovery) }
    var draftMemory by remember(tuner.memory) { mutableFloatStateOf(tuner.memory) }
    var draftExcluded by remember(tuner.excludedGenres) { mutableStateOf(tuner.excludedGenres) }
    var genresOpen by remember { mutableStateOf(false) }
    val dirty = draftMood != mood || draftDiscovery != tuner.discovery ||
        draftMemory != tuner.memory || draftExcluded != tuner.excludedGenres

    // Linear cursor over the visible rows for wheel travel.
    var cursor by remember { mutableIntStateOf(0) }
    val rows = remember(draftMood, genresOpen, dirty, trackCount, status) {
        buildList<FlowRow> {
            add(FlowRow.Action("▶ Start Flow", onStartFlow))
            FlowMood.entries.forEach { m ->
                add(FlowRow.Mood(m, m == draftMood) { draftMood = m })
            }
            add(
                FlowRow.Stepper(
                    "Discovery",
                    hint = "Fresh finds vs familiar",
                    value01 = draftDiscovery,
                    onChange = { draftDiscovery = it },
                ),
            )
            add(
                FlowRow.Stepper(
                    "Memory",
                    hint = "All-time likes vs recent plays",
                    value01 = draftMemory,
                    onChange = { draftMemory = it },
                ),
            )
            add(FlowRow.Toggle("Genres in my Flow", genresOpen) { genresOpen = !genresOpen })
            if (dirty) {
                add(
                    FlowRow.Action("✓ Apply & rebuild") {
                        onApply(draftMood, draftDiscovery, draftMemory, draftExcluded)
                    },
                )
            }
            add(FlowRow.Action("⟳ New mix ($trackCount)", onNewMix))
            add(FlowRow.Action("💾 Save mix", onSave))
            if (!status.unlocked) {
                add(FlowRow.Note("Unlocks at ${status.neededMore} more likes"))
            }
        }
    }
    wheel.onStep = { dir ->
        if (rows.isNotEmpty()) cursor = ((cursor + dir) % rows.size + rows.size) % rows.size
    }
    wheel.onCenter = { rows.getOrNull(cursor)?.activate() }

    Column(modifier = modifier.fillMaxSize().background(lcd.bg)) {
        ClassipodBar(title = "Flow", lcd = lcd, onBack = onBack)
        val listState = rememberLazyListState()
        androidx.compose.runtime.LaunchedEffect(cursor) {
            listState.animateScrollToItem(cursor)
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(rows.size) { index ->
                val row = rows[index]
                val selected = index == cursor
                when (row) {
                    is FlowRow.Action -> ClassipodRow(
                        title = row.label,
                        selected = selected,
                        lcd = lcd,
                        onClick = { row.onClick() },
                    )
                    is FlowRow.Note -> ClassipodRow(
                        title = row.text,
                        selected = false,
                        lcd = lcd,
                        onClick = {},
                    )
                    is FlowRow.Mood -> ClassipodRow(
                        title = (if (row.selected) "✓ " else "") + row.mood.label,
                        selected = selected,
                        lcd = lcd,
                        onClick = { row.onClick() },
                    )
                    is FlowRow.Stepper -> StepperRow(
                        title = row.title,
                        hint = row.hint,
                        value01 = row.value01,
                        selected = selected,
                        lcd = lcd,
                        onChange = row.onChange,
                    )
                    is FlowRow.Toggle -> ClassipodRow(
                        title = (if (row.open) "▾ " else "▸ ") + row.title,
                        selected = selected,
                        lcd = lcd,
                        onClick = { row.onClick() },
                    )
                }
                // Inline genre checklist under its toggle.
                if (row is FlowRow.Toggle && genresOpen) {
                    FLOW_TUNER_GENRES.forEach { genre ->
                        val on = genre.lowercase() !in draftExcluded
                        ClassipodRow(
                            title = (if (on) "✓ " else "○ ") + genre,
                            selected = false,
                            lcd = lcd,
                            onClick = {
                                draftExcluded = if (on) draftExcluded + genre.lowercase()
                                else draftExcluded - genre.lowercase()
                            },
                        )
                    }
                }
            }
        }
    }
}

private sealed interface FlowRow {
    fun activate()

    data class Action(val label: String, val onClick: () -> Unit) : FlowRow {
        override fun activate() = onClick()
    }

    data class Note(val text: String) : FlowRow {
        override fun activate() {}
    }

    data class Mood(val mood: FlowMood, val selected: Boolean, val onClick: () -> Unit) : FlowRow {
        override fun activate() = onClick()
    }

    data class Stepper(
        val title: String,
        val hint: String,
        val value01: Float,
        val onChange: (Float) -> Unit,
    ) : FlowRow {
        override fun activate() {}
    }

    data class Toggle(val title: String, val open: Boolean, val onClick: () -> Unit) : FlowRow {
        override fun activate() = onClick()
    }
}

/** Stepped value row: −/+ ends (touch) and wheel travel both move it. */
@Composable
private fun StepperRow(
    title: String,
    hint: String,
    value01: Float,
    selected: Boolean,
    lcd: ClassipodTheme.Lcd,
    onChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) lcd.selectedBg else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "−",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) lcd.selectedText else lcd.dim,
                modifier = Modifier
                    .clickable { onChange((value01 - 0.1f).coerceIn(0f, 1f)) }
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
            Text(
                text = title,
                fontFamily = ClassipodTheme.helvetica,
                fontSize = ClassipodTheme.ROW_SIZE,
                color = if (selected) lcd.selectedText else lcd.text,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(value01 * 10).toInt()}/10",
                fontSize = 12.sp,
                color = if (selected) lcd.selectedText.copy(alpha = 0.8f) else lcd.dim,
                fontFamily = ClassipodTheme.helvetica,
            )
            Text(
                text = "+",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) lcd.selectedText else lcd.dim,
                modifier = Modifier
                    .clickable { onChange((value01 + 0.1f).coerceIn(0f, 1f)) }
                    .padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
        // Ten-block meter, pure iPod.
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 34.dp, end = 34.dp)) {
            repeat(10) { i ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .padding(horizontal = 1.dp)
                        .background(
                            if (i < (value01 * 10).toInt()) {
                                if (selected) lcd.selectedText else lcd.text
                            } else {
                                lcd.dim.copy(alpha = 0.3f)
                            },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
        Text(
            text = hint,
            fontSize = 11.sp,
            color = if (selected) lcd.selectedText.copy(alpha = 0.75f) else lcd.dim,
            fontFamily = ClassipodTheme.helvetica,
            modifier = Modifier.padding(start = 34.dp, top = 2.dp),
        )
    }
}
