package com.music.bitchord.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import java.util.Locale

/**
 * Manager for artists hidden from search, Flow and recommendations.
 *
 * Kept as its own sheet rather than a block inside the settings list: the list
 * can grow without bound, and putting it in the scroll column would make every
 * settings row pay for it. Hiding an act is still done from the artist page;
 * this screen exists to undo it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedArtistsSheet(onDismiss: () -> Unit) {
    val entries by AppSettings.blockedArtists.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    val rows = remember(entries, query) {
        entries
            .map { it to AppSettings.blockedArtistName(it) }
            .filter { (_, name) ->
                query.isBlank() || name.contains(query.trim(), ignoreCase = true)
            }
            .sortedBy { it.second.lowercase(Locale.ROOT) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.blocked_artists),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.blocked_artists_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            if (entries.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.blocked_artists_search)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                )
            }

            if (rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.blocked_artists_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
                return@Column
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .padding(top = 8.dp),
            ) {
                items(rows, key = { it.first }) { (entry, name) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 12.dp),
                        )
                        TextButton(onClick = { AppSettings.unblockArtist(entry) }) {
                            Text(stringResource(R.string.unblock))
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}
