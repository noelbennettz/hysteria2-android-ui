package us.leaf3stones.hy2droid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import us.leaf3stones.hy2droid.R
import us.leaf3stones.hy2droid.data.Profile

/** The main screen: connection status card, profile list, add/edit/share/delete entry points. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onAdd: () -> Unit,
    onEdit: (Profile) -> Unit,
    onConnect: (Profile) -> Unit,
) {
    val profiles by viewModel.profiles.collectAsState()
    val selectedId by viewModel.selectedId.collectAsState()
    val tunnelState by viewModel.tunnelState.collectAsState()
    val stats by viewModel.stats.collectAsState()
    var shareProfile by remember { mutableStateOf<Profile?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.action_add_profile),
                )
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
                onDisconnect = viewModel::disconnect,
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
                    items(profiles, key = { it.id }) { profile ->
                        ProfileRow(
                            profile = profile,
                            selected = profile.id == selectedId,
                            onClick = { onConnect(profile) },
                            onEdit = { onEdit(profile) },
                            onShare = { shareProfile = profile },
                            onDelete = { viewModel.delete(profile.id) },
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    shareProfile?.let { profile ->
        ShareDialog(profile = profile, onDismiss = { shareProfile = null })
    }
}
