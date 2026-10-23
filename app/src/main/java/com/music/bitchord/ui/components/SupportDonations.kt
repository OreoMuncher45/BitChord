package com.music.bitchord.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.R

/**
 * Donation rails for keeping BitChord Next alive. Same addresses as the
 * README — one source of truth here so the app and the repo can never
 * disagree about where money goes.
 */
object SupportDonations {

    data class Rail(val label: String, val address: String, val note: String)

    val rails = listOf(
        Rail(
            label = "Nano (XNO)",
            address = "nano_1zqdw3qf1z8k3jx8jintaiwpo3yz7zqh1me4ph5j439ts8hsppx8dzy4xcsz",
            note = "instant, feeless, no minimum",
        ),
        Rail(
            label = "USDC on Base",
            address = "0x3f262ee685ced4a8270cece45ebdfdb2b18f54b5",
            note = "EVM",
        ),
        Rail(
            label = "Zcash (ZEC)",
            address = "u1z9k30yyvy63f5w0jypt02kvvw6dcpcgprlhzmsgc57mw6qc8rtuc5tfd9ny4atqhr448udexhkuc8xgl0z5rd9njuxnwl7kh2ahqwcqlydt9dpr4t40eawr5st74as5jed669993epsnwuejnrwv4yrkx065pqvmt0cr8gdwggcv2djp",
            note = "shielded unified address",
        ),
    )

    fun copyAddress(context: Context, address: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("donation address", address))
        Toast.makeText(context, context.getString(R.string.address_copied), Toast.LENGTH_SHORT).show()
    }

    private const val FILE = "bitchord_support"
    private const val KEY_SEEN = "support_popup_seen"

    fun isSeen(context: Context): Boolean =
        runCatching {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_SEEN, false)
        }.getOrDefault(true)

    fun markSeen(context: Context) {
        runCatching {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SEEN, true).apply()
        }
    }
}

/**
 * First-launch popup: this app is free, who built what, and where
 * donations go. Shown once; reopenable from Settings → Support.
 */
@Composable
fun SupportPopup(
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.support_popup_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.support_popup_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                SupportDonations.rails.forEach { rail ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${rail.label} · ${rail.note}",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = rail.address,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                            )
                        }
                        IconButton(onClick = { SupportDonations.copyAddress(context, rail.address) }) {
                            Icon(
                                Icons.Rounded.ContentCopy,
                                contentDescription = stringResource(R.string.copy_address),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

/** One truncated address row for Settings → Support (tap copies). */
@Composable
fun DonationRow(rail: SupportDonations.Rail) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${rail.label} · ${rail.note}",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = rail.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = { SupportDonations.copyAddress(context, rail.address) }) {
            Icon(
                Icons.Rounded.ContentCopy,
                contentDescription = stringResource(R.string.copy_address),
            )
        }
    }
}
