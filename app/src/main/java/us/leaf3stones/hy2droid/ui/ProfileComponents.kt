package us.leaf3stones.hy2droid.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import us.leaf3stones.hy2droid.R
import us.leaf3stones.hy2droid.data.HysteriaUri
import us.leaf3stones.hy2droid.data.Profile

/** A single profile row with an overflow menu (test latency / edit / share / delete). */
@Composable
fun ProfileRow(
    profile: Profile,
    selected: Boolean,
    inUse: Boolean,
    showAddress: Boolean,
    latencyMs: Int?,
    latencyMeasured: Boolean,
    latencyTesting: Boolean,
    onClick: () -> Unit,
    onTestLatency: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val colors = if (selected) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    } else {
        CardDefaults.cardColors()
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = colors,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = profile.name, style = MaterialTheme.typography.titleMedium)
                    if (inUse) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.profile_in_use),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                // Hidden by default: most people never need to read the IP, and
                // it is the noisiest line on the screen (Settings → Appearance).
                if (showAddress) {
                    Text(
                        text = profile.authority,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // The measured latency sits on the same row as the menu that starts
            // the test, so the number and the action stay together.
            when {
                latencyTesting -> LatencyLabel(
                    text = stringResource(R.string.latency_testing),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                latencyMeasured -> {
                    val ms = latencyMs
                    LatencyLabel(
                        text = if (ms != null) {
                            stringResource(R.string.latency_ms, ms)
                        } else {
                            stringResource(R.string.latency_failed)
                        },
                        color = if (ms != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.action_more),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_edit)) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_test_latency)) },
                        onClick = {
                            menuOpen = false
                            onTestLatency()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_share)) },
                        onClick = {
                            menuOpen = false
                            onShare()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/** Small latency badge, kept next to the menu that triggers the measurement. */
@Composable
private fun LatencyLabel(text: String, color: ComposeColor) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.padding(end = 4.dp),
    )
}

/** Placeholder shown when there are no profiles yet. */
@Composable
fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.empty_profiles),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Dialog showing the profile's hysteria2 share link as text + QR, with a copy button. */
@Composable
fun ShareDialog(profile: Profile, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val link = remember(profile) { HysteriaUri.build(profile) }

    // The QR and the link carry the auth secret.
    SecureWindow()
    val qrBitmap = remember(link) {
        runCatching {
            val size = 512
            val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.share_title),
                        modifier = Modifier.size(220.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                }
                SelectionContainer { Text(link) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                context.copySensitive(link)
                Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
                onDismiss()
            }) {
                Text(stringResource(R.string.action_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        },
    )
}
