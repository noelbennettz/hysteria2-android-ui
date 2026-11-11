package us.leaf3stones.hy2droid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import us.leaf3stones.hy2droid.R
import us.leaf3stones.hy2droid.data.Profile
import us.leaf3stones.hy2droid.data.ProfileOrdering
import us.leaf3stones.hy2droid.proxy.TunnelState

/** The main screen: status card, profile list and the single connect/disconnect + add controls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onAdd: () -> Unit,
    onEdit: (Profile) -> Unit,
    onConnect: (Profile) -> Unit,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit,
    onScan: () -> Unit,
) {
    val profiles by viewModel.profiles.collectAsState()
    val selectedId by viewModel.selectedId.collectAsState()
    val tunnelState by viewModel.tunnelState.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val showServerAddress by viewModel.showServerAddress.collectAsState()
    val showTraffic by viewModel.showTraffic.collectAsState()
    val autoTestLatency by viewModel.autoTestLatency.collectAsState()
    val latency by viewModel.latency.collectAsState()
    val latencyTesting by viewModel.latencyTesting.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    var shareProfile by remember { mutableStateOf<Profile?>(null) }
    var pendingDelete by remember { mutableStateOf<Profile?>(null) }

    // Settings → Network: measure every node once per app start. The ViewModel
    // guards against repeating it on recomposition.
    LaunchedEffect(autoTestLatency) {
        viewModel.maybeAutoTestLatency(autoTestLatency)
    }

    // Only reorder when the user opted in: without latency numbers the stored
    // order is the order they arranged themselves.
    val orderedProfiles = remember(profiles, latency, autoTestLatency) {
        if (autoTestLatency) ProfileOrdering.byLatency(profiles, latency) else profiles
    }

    // The first profile is treated as selected when nothing explicit is stored.
    val selectedProfile = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()
    // What is actually up right now, which is not necessarily the selection: a tap
    // on another row only moves the selection.
    val activeProfile = profiles.firstOrNull { it.id == activeProfileId }
    val active = tunnelState is TunnelState.Connected ||
        tunnelState is TunnelState.Connecting ||
        tunnelState is TunnelState.Disconnecting

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_title),
                        )
                    }
                    IconButton(onClick = onScan) {
                        Icon(
                            imageVector = Icons.Outlined.QrCode,
                            contentDescription = stringResource(R.string.action_scan_import),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Also shown when the list is empty but a tunnel is up: otherwise
                // deleting the running profile leaves no way to disconnect from the
                // app at all.
                if (selectedProfile != null || active) {
                    FloatingActionButton(
                        onClick = {
                            if (active) onDisconnect() else selectedProfile?.let(onConnect)
                        },
                        containerColor = if (active) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PowerSettingsNew,
                            contentDescription = stringResource(
                                if (active) R.string.action_disconnect else R.string.action_connect,
                            ),
                            tint = if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                FloatingActionButton(onClick = onAdd) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.action_add_profile),
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            ConnectionCard(
                state = tunnelState,
                stats = stats,
                showTraffic = showTraffic,
                profileName = activeProfile?.let { it.name.ifBlank { it.authority } },
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.profiles_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            if (profiles.isEmpty()) {
                EmptyState(Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(orderedProfiles, key = { it.id }) { profile ->
                        ProfileRow(
                            profile = profile,
                            selected = profile.id == selectedProfile?.id,
                            inUse = active && profile.id == activeProfileId,
                            showAddress = showServerAddress,
                            latencyMs = latency[profile.id],
                            latencyMeasured = latency.containsKey(profile.id),
                            latencyTesting = profile.id in latencyTesting,
                            onClick = { viewModel.select(profile.id) },
                            onTestLatency = { viewModel.testLatency(profile) },
                            onEdit = { onEdit(profile) },
                            onShare = { shareProfile = profile },
                            onDelete = { pendingDelete = profile },
                        )
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
    }

    shareProfile?.let { profile ->
        ShareDialog(profile = profile, onDismiss = { shareProfile = null })
    }

    // Deleting throws the auth secret away with no way back, so ask first.
    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_profile_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.delete_profile_message,
                        profile.name.ifBlank { profile.authority },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(profile.id)
                        pendingDelete = null
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
