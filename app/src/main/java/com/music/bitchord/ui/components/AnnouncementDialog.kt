package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.bitchord.R
import com.music.bitchord.data.settings.AppSettings
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/** Enough for a full notice before it starts scrolling, without swallowing the screen. */
private val ANNOUNCEMENT_MAX_HEIGHT = 300.dp

/**
 * A server-driven notice, shown once per announcement id.
 *
 * Deliberately the same frosted card as [UpdateAvailableDialog] — a popup the
 * app raises on its own is one kind of thing, and two visual languages for it
 * would make the announcement look like an error. The only difference is the
 * absence of a progress row and install actions: an announcement is read and
 * dismissed, or read and followed through its optional link.
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AnnouncementDialog(
    title: String,
    body: String,
    actionLabel: String?,
    url: String?,
    hazeState: HazeState,
    onDismiss: () -> Unit,
    onOpenUrl: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(ALERT_CORNER)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SCRIM_COLOR)
            // Informational, so backing out is always allowed; the link is an
            // invitation, never a requirement.
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(ALERT_WIDTH)
                .clip(shape)
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(MaterialTheme.colorScheme.surface)
                    } else {
                        Modifier.optimizedHazeEffect(
                            state = hazeState,
                            style = HazeMaterials.regular(MaterialTheme.colorScheme.surface),
                        )
                    },
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 19.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.W600,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Box(
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .heightIn(max = ANNOUNCEMENT_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            AlertRule()
            if (url != null) {
                AlertAction(
                    label = actionLabel
                        ?: stringResource(R.string.announcement_learn_more),
                    emphasised = true,
                    onClick = onOpenUrl,
                )
                AlertRule()
                AlertAction(
                    label = stringResource(R.string.announcement_ok),
                    emphasised = false,
                    onClick = onDismiss,
                )
            } else {
                AlertAction(
                    label = stringResource(R.string.announcement_ok),
                    emphasised = true,
                    onClick = onDismiss,
                )
            }
        }
    }
}
