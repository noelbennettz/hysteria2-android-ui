package us.leaf3stones.hy2droid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import us.leaf3stones.hy2droid.R
import us.leaf3stones.hy2droid.proxy.TunnelState
import us.leaf3stones.hy2droid.proxy.TunnelStats

/** The status card at the top of the home screen. */
@Composable
fun ConnectionCard(
    state: TunnelState,
    stats: TunnelStats,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor(state)),
                )
                Spacer(Modifier.width(10.dp))
                Text(text = statusLabel(state), style = MaterialTheme.typography.titleLarge)
            }

            if (state is TunnelState.Connected) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "↑ " + formatBytes(stats.tx) + "    ↓ " + formatBytes(stats.rx),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state is TunnelState.Error) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun statusLabel(state: TunnelState): String = when (state) {
    TunnelState.Idle -> stringResource(R.string.status_idle)
    TunnelState.Connecting -> stringResource(R.string.status_connecting)
    TunnelState.Connected -> stringResource(R.string.status_connected)
    TunnelState.Disconnecting -> stringResource(R.string.status_disconnecting)
    is TunnelState.Error -> stringResource(R.string.status_error)
}

@Composable
private fun statusColor(state: TunnelState): Color = when (state) {
    TunnelState.Connected -> MaterialTheme.colorScheme.primary
    TunnelState.Connecting, TunnelState.Disconnecting -> MaterialTheme.colorScheme.tertiary
    is TunnelState.Error -> MaterialTheme.colorScheme.error
    TunnelState.Idle -> MaterialTheme.colorScheme.outline
}

/** Human-readable byte count (B / KB / MB / GB). */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}
