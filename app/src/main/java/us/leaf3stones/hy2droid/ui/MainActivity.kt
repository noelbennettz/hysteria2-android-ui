package us.leaf3stones.hy2droid.ui

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import us.leaf3stones.hy2droid.data.Profile
import us.leaf3stones.hy2droid.ui.theme.Hy2droidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Hy2droidTheme {
                AppRoot()
            }
        }
    }
}

/** Top-level composable that swaps between the list and the editor. */
@Composable
private fun AppRoot(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Profile?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var pendingProfile by remember { mutableStateOf<Profile?>(null) }

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

    if (editorOpen) {
        EditProfileScreen(
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
    } else {
        HomeScreen(
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
        )
    }
}
