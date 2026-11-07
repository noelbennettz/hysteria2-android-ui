package us.leaf3stones.hy2droid.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.leaf3stones.hy2droid.data.HysteriaConfigBuilder
import us.leaf3stones.hy2droid.data.Profile
import us.leaf3stones.hy2droid.data.ProfileStore
import us.leaf3stones.hy2droid.data.SettingsStore
import us.leaf3stones.hy2droid.proxy.Hysteria2VpnService
import us.leaf3stones.hy2droid.proxy.TunnelState
import us.leaf3stones.hy2droid.proxy.TunnelStats
import us.leaf3stones.hy2droid.proxy.TunnelStatus
import java.io.File

/** Owns the profile list and bridges UI intents to the tunnel service. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProfileStore(app)
    private val settings = SettingsStore(app)
    private val configFile = File(app.filesDir, Hysteria2VpnService.HYSTERIA_CONFIG_FILE)

    val profiles: StateFlow<List<Profile>> = store.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedId: StateFlow<String?> = store.selectedId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val excludedPackages: StateFlow<Set<String>> = settings.excluded
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val tunnelState: StateFlow<TunnelState> = TunnelStatus.state
    val stats: StateFlow<TunnelStats> = TunnelStatus.stats

    fun save(profile: Profile) {
        viewModelScope.launch { store.upsert(profile) }
    }

    fun delete(id: String) {
        viewModelScope.launch { store.delete(id) }
    }

    fun select(id: String) {
        viewModelScope.launch { store.select(id) }
    }

    fun setExcluded(packages: Set<String>) {
        viewModelScope.launch { settings.setExcluded(packages) }
    }

    /** Persist [profile] as the selected profile and bring the tunnel up for it. */
    fun connect(profile: Profile) {
        viewModelScope.launch {
            store.select(profile.id)
            val yaml = HysteriaConfigBuilder.build(profile)
            withContext(Dispatchers.IO) { configFile.writeText(yaml) }
            val intent = Intent(getApplication<Application>(), Hysteria2VpnService::class.java)
                .setAction(Hysteria2VpnService.ACTION_START_VPN)
            ContextCompat.startForegroundService(getApplication<Application>(), intent)
        }
    }

    /** Ask the running service to tear the tunnel down. */
    fun disconnect() {
        val intent = Intent(getApplication<Application>(), Hysteria2VpnService::class.java)
            .setAction(Hysteria2VpnService.ACTION_STOP_VPN)
        getApplication<Application>().startService(intent)
    }
}
