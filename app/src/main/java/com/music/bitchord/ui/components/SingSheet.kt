package com.music.bitchord.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextAlign
import com.music.bitchord.playback.SingMethod
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import kotlin.math.roundToInt

/**
 * Apple Music Sing equivalent: a vocal-level slider for the track playing
 * right now.
 *
 * Dragging it down pulls the center-mixed lead vocal out of the mix via
 * [com.music.bitchord.playback.VocalCutProcessor] — a band-limited cut that
 * spares bass, air and drum transients, live in ExoPlayer's pipeline. Backing vocals mixed off
 * center survive longer than the lead, which is what makes a mid-slider
 * position read as a duet rather than as everything getting quieter.
 */
@Composable
fun SingSheet(onDismiss: () -> Unit) {
    val level by AppSettings.singLevel.collectAsStateWithLifecycle()
    val method by AppSettings.singMethod.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 8.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = if (level > 0.001f) Icons.Rounded.Mic else Icons.Rounded.MicOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.sing_title),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
            text = stringResource(R.string.sing_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        // Which remover, above how hard. The order is worst-case to best-case
        // for a dense modern mix, which is also the order a listener meets
        // them: the plain one first because it is what most karaoke does and
        // what a clean recording rewards, the current one last because it is
        // the default and the safest on anything busy.
        MethodSelector(
            selected = method,
            onSelect = AppSettings::setSingMethod,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                when (method) {
                    SingMethod.ORIGINAL -> R.string.sing_method_original_note
                    SingMethod.BAND_LIMITED -> R.string.sing_method_band_note
                    SingMethod.VOICE_GATE -> R.string.sing_method_gate_note
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.sing_level_percent, (level * 100).roundToInt()),
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
        )
        Slider(
            value = level,
            onValueChange = { AppSettings.setSingLevel(it) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.sing_full_vocals),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.sing_no_vocals),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.sing_duet_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = {
            AppSettings.setSingLevel(0f)
            onDismiss()
        }) {
            Text(stringResource(R.string.sing_turn_off))
        }
    }
}

/**
 * Three ways to take a vocal out, as a row of chips rather than a dropdown:
 * three options, and the difference between them is audible within seconds of
 * switching, which makes the choice worth making in front of the listener
 * rather than buried in settings.
 */
@Composable
private fun MethodSelector(
    selected: SingMethod,
    onSelect: (SingMethod) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SingMethod.entries.forEach { option ->
            val isSelected = option == selected
            Surface(
                onClick = { onSelect(option) },
                shape = MaterialTheme.shapes.small,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(
                        when (option) {
                            SingMethod.ORIGINAL -> R.string.sing_method_original
                            SingMethod.BAND_LIMITED -> R.string.sing_method_band
                            SingMethod.VOICE_GATE -> R.string.sing_method_gate
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                )
            }
        }
    }
}
