package us.leaf3stones.hy2droid.ui

import android.widget.Toast
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
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import us.leaf3stones.hy2droid.R
import us.leaf3stones.hy2droid.data.HysteriaUri
import us.leaf3stones.hy2droid.data.Profile
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
    onOpenSplitTunnel: () -> Unit,
) {
    val profiles by viewModel.profiles.collectAsState()
    val selectedId by viewModel.selectedId.collectAsState()
    val tunnelState by viewModel.tunnelState.collectAsState()
    val stats by viewModel.stats.collectAsState()
    var shareProfile by remember { mutableStateOf<Profile?>(null) }

    val context = LocalContext.current
    // The first profile is treated as selected when nothing explicit is stored.
    val selectedProfile = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()
    val active = tunnelState is TunnelState.Connected ||
        tunnelState is TunnelState.Connecting ||
        tunnelState is TunnelState.Disconnecting

    fun handleScanResult(contents: String?) {
        if (contents.isNullOrBlank()) return
        val profile = HysteriaUri.parse(contents)
        if (profile != null) {
            viewModel.save(profile)
            Toast.makeText(context, R.string.import_success, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, R.string.import_invalid, Toast.LENGTH_SHORT).show()
        }
    }

    fun startScan() {
        GmsBarcodeScanning.getClient(context)
            .startScan()
            .addOnSuccessListener { barcode -> handleScanResult(barcode.rawValue) }
            .addOnFailureListener { /* cancelled or failed; ignore */ }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSplitTunnel) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = stringResource(R.string.split_tunnel_title),
                        )
                    }
                    IconButton(onClick = { startScan() }) {
                        Icon(
                            imageVector = Icons.Filled.QrCodeScanner,
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
                if (selectedProfile != null) {
                    FloatingActionButton(
                        onClick = {
                            if (active) onDisconnect() else onConnect(selectedProfile)
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
            ConnectionCard(state = tunnelState, stats = stats)
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
                    items(profiles, key = { it.id }) { profile ->
                        ProfileRow(
                            profile = profile,
                            selected = profile.id == selectedProfile?.id,
                            onClick = { viewModel.select(profile.id) },
                            onEdit = { onEdit(profile) },
                            onShare = { shareProfile = profile },
                            onDelete = { viewModel.delete(profile.id) },
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
}
