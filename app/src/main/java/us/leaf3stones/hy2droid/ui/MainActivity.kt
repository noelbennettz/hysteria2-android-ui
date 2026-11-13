package us.leaf3stones.hy2droid.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import us.leaf3stones.hy2droid.R
import us.leaf3stones.hy2droid.data.HysteriaUri
import us.leaf3stones.hy2droid.data.Profile
import us.leaf3stones.hy2droid.ui.theme.Hy2droidTheme

class MainActivity : ComponentActivity() {

    /**
     * The ongoing tunnel notification (with its Disconnect action) needs this
     * permission on Android 13+; the VPN itself works either way.
     */
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Denied is fine: the tunnel still runs, only the notification is hidden. */ }

    /**
     * A `hysteria2://` link that arrived through the VIEW intent filter and has
     * not been imported yet. Compose consumes it (sets it back to `null`), so a
     * rotation or a re-entry never imports the same link twice.
     */
    private val incomingLink = MutableStateFlow<String?>(null)

    /**
     * Bumped when the activity is brought to the front by its launcher intent (the
     * Quick Settings long-press, the launcher icon). Compose then returns to the
     * home screen instead of resuming whatever page was open — the tile's
     * long-press is documented to open the *app*, so landing on Settings is wrong.
     */
    private val homeRequests = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only on a cold start: a configuration change must not re-import the
        // link the activity was originally launched with.
        if (savedInstanceState == null) incomingLink.value = intent?.dataString
        requestNotificationPermissionIfNeeded()
        setContent {
            AppRoot(incomingLink, homeRequests)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val link = intent.dataString
        if (link != null) {
            incomingLink.value = link
        } else if (intent.action == Intent.ACTION_MAIN) {
            homeRequests.value += 1
        }
    }

    /** Ask for POST_NOTIFICATIONS once on Android 13+; silent elsewhere. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/** Top-level composable that swaps between the list, editor, settings and split-tunnel screens. */
@Composable
private fun AppRoot(
    incomingLink: MutableStateFlow<String?>,
    homeRequestsFlow: MutableStateFlow<Int>,
    vm: MainViewModel = viewModel(),
) {
    val dynamicColor by vm.dynamicColor.collectAsState()
    val pendingLink by incomingLink.collectAsState()
    val context = LocalContext.current

    // Always-on VPN cannot be detected up front: `always_on_vpn_app` is a
    // restricted secure setting and reading it throws SecurityException from
    // Android 12 on. Instead the service notices a system-initiated restart right
    // after a user stop and raises this flag.
    val restartedBySystem by vm.restartedBySystem.collectAsState()
    var alwaysOnWarning by remember { mutableStateOf(false) }
    LaunchedEffect(restartedBySystem) {
        if (restartedBySystem) {
            vm.acknowledgeSystemRestart()
            alwaysOnWarning = true
        }
    }

    // Shared by the QR scanner and the VIEW intent filter: one code path, one
    // message, so a deep link behaves exactly like a scanned code.
    fun importLink(text: String) {
        val profile = HysteriaUri.parse(text)
        if (profile != null) {
            vm.import(profile)
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
        // Saveable so a rotation does not silently drop the user back on the list
        // (or, in the editor, throw the draft away).
        var editingId by rememberSaveable { mutableStateOf<String?>(null) }
        var editorOpen by rememberSaveable { mutableStateOf(false) }
        var pendingProfileId by rememberSaveable { mutableStateOf<String?>(null) }
        var splitTunnelOpen by rememberSaveable { mutableStateOf(false) }
        var blockRulesOpen by rememberSaveable { mutableStateOf(false) }
        var settingsOpen by rememberSaveable { mutableStateOf(false) }
        var aboutOpen by rememberSaveable { mutableStateOf(false) }
        var scannerOpen by rememberSaveable { mutableStateOf(false) }

        // The editor works on the stored profile: the ViewModel survives a
        // rotation, so this resolves on the first frame after one.
        val profiles by vm.profiles.collectAsState()
        val editing = editingId?.let { id -> profiles.firstOrNull { it.id == id } }

        // Restored from saved state after process death the id may resolve to
        // nothing (still loading, or the profile was deleted meanwhile). Close the
        // editor instead of letting it open a blank "new profile" form.
        LaunchedEffect(editorOpen, editingId, profiles) {
            if (editorOpen && editingId != null && editing == null && profiles.isNotEmpty()) {
                editorOpen = false
                editingId = null
            }
        }

        // Launcher / Quick Settings long-press: come back to the list, not to
        // whatever sub-screen happened to be open when the app went to background.
        val homeRequests by homeRequestsFlow.collectAsState()
        LaunchedEffect(homeRequests) {
            if (homeRequests == 0) return@LaunchedEffect
            editorOpen = false
            editingId = null
            splitTunnelOpen = false
            blockRulesOpen = false
            settingsOpen = false
            aboutOpen = false
            scannerOpen = false
        }

        // System back closes one level (innermost first) instead of finishing the
        // activity. Without this every sub-screen was a dead end: pressing back on
        // Settings left the app entirely.
        val inSubScreen = scannerOpen || editorOpen || splitTunnelOpen || blockRulesOpen ||
            aboutOpen || settingsOpen
        BackHandler(enabled = inSubScreen) {
            when {
                scannerOpen -> scannerOpen = false
                editorOpen -> {
                    editorOpen = false
                    editingId = null
                }
                splitTunnelOpen -> splitTunnelOpen = false
                blockRulesOpen -> blockRulesOpen = false
                aboutOpen -> aboutOpen = false
                settingsOpen -> settingsOpen = false
            }
        }

        val vpnPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            // Remembered as an id: the grant can arrive after a rotation, when a
            // captured Profile object would no longer exist.
            if (result.resultCode == Activity.RESULT_OK) {
                pendingProfileId?.let { vm.connectById(it) }
            }
            pendingProfileId = null
        }

        fun requestConnect(profile: Profile) {
            val prepare = VpnService.prepare(context)
            if (prepare != null) {
                pendingProfileId = profile.id
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
            // While a restored id is still unresolved, render nothing: opening the
            // editor with `initial == null` would snapshot a fresh id and turn an
            // edit into a duplicate.
            editorOpen -> if (editing != null || editingId == null) {
                EditProfileScreen(
                    initial = editing,
                    onSave = { profile ->
                        vm.save(profile)
                        editorOpen = false
                        editingId = null
                    },
                    onBack = {
                        editorOpen = false
                        editingId = null
                    },
                )
            }
            splitTunnelOpen -> SplitTunnelScreen(
                viewModel = vm,
                onBack = { splitTunnelOpen = false },
            )
            blockRulesOpen -> BlockRulesScreen(
                viewModel = vm,
                onBack = { blockRulesOpen = false },
            )
            aboutOpen -> AboutScreen(onBack = { aboutOpen = false })
            settingsOpen -> SettingsScreen(
                viewModel = vm,
                onBack = { settingsOpen = false },
                onOpenSplitTunnel = { splitTunnelOpen = true },
                onOpenBlockRules = { blockRulesOpen = true },
                onOpenAbout = { aboutOpen = true },
            )
            else -> HomeScreen(
                viewModel = vm,
                onAdd = {
                    editingId = null
                    editorOpen = true
                },
                onEdit = { profile ->
                    editingId = profile.id
                    editorOpen = true
                },
                onConnect = ::requestConnect,
                onDisconnect = vm::disconnect,
                onOpenSettings = { settingsOpen = true },
                onScan = { scannerOpen = true },
            )
        }

        if (alwaysOnWarning) {
            AlertDialog(
                onDismissRequest = { alwaysOnWarning = false },
                title = { Text(stringResource(R.string.always_on_title)) },
                text = { Text(stringResource(R.string.always_on_message)) },
                confirmButton = {
                    TextButton(onClick = { alwaysOnWarning = false }) {
                        Text(stringResource(R.string.action_close))
                    }
                },
            )
        }
    }
}
