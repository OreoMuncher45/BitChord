package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.audio.JamesDspNative
import com.music.bitchord.ui.components.AurisSlider
import kotlin.math.roundToInt

/** Reverb presets exposed in v1, mapped to libjamesdsp's SF2 preset indices. */
private data class ReverbChoice(val labelRes: Int, val preset: Int)

private val REVERB_CHOICES = listOf(
    ReverbChoice(R.string.jamesdsp_off, -1),
    ReverbChoice(R.string.jamesdsp_room, 9),
    ReverbChoice(R.string.jamesdsp_hall, 3),
    ReverbChoice(R.string.jamesdsp_large_hall, 5),
    ReverbChoice(R.string.jamesdsp_plate, 15),
    ReverbChoice(R.string.jamesdsp_long, 17),
)

/**
 * JamesDSP's opt-in controls.
 *
 * The engine itself lives in the native playback chain, so this sheet only
 * publishes settings and never touches audio. Defaults are neutral: the master
 * switch alone is a no-op apart from the safety limiter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JamesDspSheet(onDismiss: () -> Unit) {
    val enabled by AppSettings.jamesDspEnabled.collectAsStateWithLifecycle()
    val bass by AppSettings.jamesDspBassGainDb.collectAsStateWithLifecycle()
    val stereo by AppSettings.jamesDspStereoLevel.collectAsStateWithLifecycle()
    val tube by AppSettings.jamesDspTubeLevel.collectAsStateWithLifecycle()
    val reverb by AppSettings.jamesDspReverbPreset.collectAsStateWithLifecycle()
    val preGain by AppSettings.jamesDspPreGainDb.collectAsStateWithLifecycle()
    val limiter by AppSettings.jamesDspLimiter.collectAsStateWithLifecycle()
    val available = remember { JamesDspNative.available }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, bottom = 30.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.jamesdsp),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.jamesdsp_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled && available,
                    enabled = available,
                    onCheckedChange = AppSettings::setJamesDspEnabled,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (available) {
                    stringResource(R.string.jamesdsp_description)
                } else {
                    stringResource(R.string.jamesdsp_unavailable)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (available) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Spacer(Modifier.height(18.dp))

            EffectSlider(
                title = stringResource(R.string.jamesdsp_bass),
                value = bass,
                range = 0f..15f,
                valueLabel = "${bass.roundToInt()} dB",
                enabled = enabled && available,
                onChange = AppSettings::setJamesDspBassGain,
            )
            EffectSlider(
                title = stringResource(R.string.jamesdsp_stereo),
                value = stereo,
                range = 0f..100f,
                valueLabel = "${stereo.roundToInt()}%",
                enabled = enabled && available,
                onChange = AppSettings::setJamesDspStereoLevel,
            )
            EffectSlider(
                title = stringResource(R.string.jamesdsp_tube),
                value = tube,
                range = 0f..100f,
                valueLabel = "${tube.roundToInt()}%",
                enabled = enabled && available,
                onChange = AppSettings::setJamesDspTubeLevel,
            )
            EffectSlider(
                title = stringResource(R.string.jamesdsp_pregain),
                value = preGain,
                range = -15f..15f,
                valueLabel = String.format("%+.1f dB", preGain),
                enabled = enabled && available,
                onChange = AppSettings::setJamesDspPreGain,
            )

            Text(
                text = stringResource(R.string.jamesdsp_reverb),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(REVERB_CHOICES) { choice ->
                    FilterChip(
                        selected = reverb == choice.preset,
                        enabled = enabled && available,
                        onClick = { AppSettings.setJamesDspReverbPreset(choice.preset) },
                        label = { Text(stringResource(choice.labelRes)) },
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.jamesdsp_limiter),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.jamesdsp_limiter_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = limiter,
                    enabled = enabled && available,
                    onCheckedChange = AppSettings::setJamesDspLimiter,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.jamesdsp_license),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    onClick = {
                        AppSettings.setJamesDspBassGain(0f)
                        AppSettings.setJamesDspStereoLevel(0f)
                        AppSettings.setJamesDspTubeLevel(0f)
                        AppSettings.setJamesDspReverbPreset(-1)
                        AppSettings.setJamesDspPreGain(0f)
                        AppSettings.setJamesDspLimiter(true)
                    },
                    enabled = available,
                ) {
                    Text(stringResource(R.string.jamesdsp_reset))
                }
            }
        }
    }
}

@Composable
private fun EffectSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AurisSlider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
