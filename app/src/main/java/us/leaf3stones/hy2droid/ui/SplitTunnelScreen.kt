package us.leaf3stones.hy2droid.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import us.leaf3stones.hy2droid.R

private data class AppInfo(val label: String, val packageName: String)

/** Per-app split tunneling: apps toggled on are routed OUTSIDE the VPN. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitTunnelScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val excluded by viewModel.excludedPackages.collectAsState()
    val apps by produceState<List<AppInfo>>(initialValue = emptyList()) {
        value = withContext(Dispatchers.IO) { loadInstalledApps(context) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.split_tunnel_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (apps.isEmpty()) {
            Box(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.split_tunnel_hint), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.split_tunnel_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        // PackageManager only reveals apps with a launcher entry to
                        // us (see <queries> in the manifest); saying so is better
                        // than an empty list that looks broken.
                        Text(
                            text = stringResource(R.string.split_tunnel_visibility_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(apps, key = { it.packageName }) { app ->
                    val bypass = app.packageName in excluded
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = bypass,
                            onCheckedChange = { checked ->
                                val newSet = if (checked) {
                                    excluded + app.packageName
                                } else {
                                    excluded - app.packageName
                                }
                                viewModel.setExcluded(newSet)
                            },
                            // TalkBack otherwise announces only "on/off".
                            modifier = Modifier.semantics { contentDescription = app.label },
                        )
                    }
                }
            }
        }
    }
}

private fun loadInstalledApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .mapNotNull { ri ->
            val info = ri.activityInfo?.applicationInfo ?: return@mapNotNull null
            val pkg = info.packageName
            if (pkg == context.packageName) return@mapNotNull null
            val label = info.loadLabel(pm).toString().ifBlank { pkg }
            AppInfo(label, pkg)
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}
