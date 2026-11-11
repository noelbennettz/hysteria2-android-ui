package us.leaf3stones.hy2droid.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import us.leaf3stones.hy2droid.R
import us.leaf3stones.hy2droid.data.HysteriaUri
import us.leaf3stones.hy2droid.data.Profile
import us.leaf3stones.hy2droid.ui.theme.Hy2droidTheme

class MainActivity : ComponentActivity() {

    /**
     * A `hysteria2://` link that arrived through the VIEW intent filter and has
     * not been imported yet. Compose consumes it (sets it back to `null`), so a
     * rotation or a re-entry never imports the same link twice.
     */
    private val incomingLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a cold start: a configuration change must not re-import the
        // link the activity was originally launched with.
        if (savedInstanceState == null) incomingLink.value = intent?.dataString
        setContent {
            AppRoot(incomingLink)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.dataString?.let { incomingLink.value = it }
    }
}

/** Top-level composable that swaps between the list, editor, settings and split-tunnel screens. */
@Composable
private fun AppRoot(
    incomingLink: MutableStateFlow<String?>,
    vm: MainViewModel = viewModel(),
) {
    val dynamicColor by vm.dynamicColor.collectAsState()
    val pendingLink by incomingLink.collectAsState()
    val context = LocalContext.current

    // Shared by the QR scanner and the VIEW intent filter: one code path, one
    // message, so a deep link behaves exactly like a scanned code.
    fun importLink(text: String) {
        val profile = HysteriaUri.parse(text)
        if (profile != null) {
            vm.save(profile)
            Toast.makeText(context, R.string.import_success, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, R.string.import_invalid, Toast.LENGTH_SHORT).show()
        }
    }

    // A share link opened from a browser lands here: import it straight away so
    // the user never has to paste anything (or open the editor) by hand.
    LaunchedEffect(pendingLink) {
        val raw = pendingLink ?: return@LaunchedEffect
        incomingLink.value = null
        importLink(raw)
    }

    Hy2droidTheme(dynamicColor = dynamicColor) {
        var editing by remember { mutableStateOf<Profile?>(null) }
        var editorOpen by remember { mutableStateOf(false) }
        var pendingProfile by remember { mutableStateOf<Profile?>(null) }
        var splitTunnelOpen by remember { mutableStateOf(false) }
        var blockRulesOpen by remember { mutableStateOf(false) }
        var settingsOpen by remember { mutableStateOf(false) }
        var scannerOpen by remember { mutableStateOf(false) }

        val vpnPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                pendingProfile?.let { vm.connect(it) }
            }
            pendingProfile = null
        }

        fun requestConnect(profile: Profile) {
            val prepare = VpnService.prepare(context)
            if (prepare != null) {
                pendingProfile = profile
                vpnPermissionLauncher.launch(prepare)
            } else {
                vm.connect(profile)
            }
        }

        when {
            scannerOpen -> QrScannerScreen(
                onResult = { text ->
                    scannerOpen = false
                    importLink(text)
                },
                onCancel = { scannerOpen = false },
            )
            editorOpen -> EditProfileScreen(
                initial = editing,
                onSave = { profile ->
                    vm.save(profile)
                    editorOpen = false
                    editing = null
                },
                onBack = {
                    editorOpen = false
                    editing = null
                },
            )
            splitTunnelOpen -> SplitTunnelScreen(
                viewModel = vm,
                onBack = { splitTunnelOpen = false },
            )
            blockRulesOpen -> BlockRulesScreen(
                viewModel = vm,
                onBack = { blockRulesOpen = false },
            )
            settingsOpen -> SettingsScreen(
                viewModel = vm,
                onBack = { settingsOpen = false },
                onOpenSplitTunnel = { splitTunnelOpen = true },
                onOpenBlockRules = { blockRulesOpen = true },
            )
            else -> HomeScreen(
                viewModel = vm,
                onAdd = {
                    editing = null
                    editorOpen = true
                },
                onEdit = { profile ->
                    editing = profile
                    editorOpen = true
                },
                onConnect = ::requestConnect,
                onDisconnect = vm::disconnect,
                onOpenSettings = { settingsOpen = true },
                onScan = { scannerOpen = true },
            )
        }
    }
}
