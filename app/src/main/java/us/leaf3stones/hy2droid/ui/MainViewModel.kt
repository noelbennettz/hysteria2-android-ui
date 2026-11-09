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
import us.leaf3stones.hy2droid.data.BlockRule
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
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val selectedId: StateFlow<String?> = store.selectedId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val excludedPackages: StateFlow<Set<String>> = settings.excluded
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val dynamicColor: StateFlow<Boolean> = settings.dynamicColor
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val customDnsEnabled: StateFlow<Boolean> = settings.customDnsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val customDnsServer: StateFlow<String> = settings.customDnsServer
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_DNS)

    val customDnsServer2: StateFlow<String> = settings.customDnsServer2
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val bypassLan: StateFlow<Boolean> = settings.bypassLan
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val blockRules: StateFlow<List<BlockRule>> = settings.blockRules
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settings.setDynamicColor(enabled) }
    }

    fun setCustomDnsEnabled(enabled: Boolean) {
        viewModelScope.launch { settings.setCustomDnsEnabled(enabled) }
    }

    fun setCustomDnsServer(server: String) {
        viewModelScope.launch { settings.setCustomDnsServer(server) }
    }

    fun setCustomDnsServer2(server: String) {
        viewModelScope.launch { settings.setCustomDnsServer2(server) }
    }

    fun setBypassLan(enabled: Boolean) {
        viewModelScope.launch { settings.setBypassLan(enabled) }
    }

    fun setBlockRules(rules: List<BlockRule>) {
        viewModelScope.launch { settings.setBlockRules(rules) }
    }

    /** Persist [profile] as the selected profile and bring the tunnel up for it. */
    fun connect(profile: Profile) {
        viewModelScope.launch {
            store.select(profile.id)
            settings.setDesiredConnected(true)
            val yaml = HysteriaConfigBuilder.build(profile)
            withContext(Dispatchers.IO) { configFile.writeText(yaml) }
            val intent = Intent(getApplication<Application>(), Hysteria2VpnService::class.java)
                .setAction(Hysteria2VpnService.ACTION_START_VPN)
            ContextCompat.startForegroundService(getApplication<Application>(), intent)
        }
    }

    /** Ask the running service to tear the tunnel down. */
    fun disconnect() {
        viewModelScope.launch { settings.setDesiredConnected(false) }
        val intent = Intent(getApplication<Application>(), Hysteria2VpnService::class.java)
            .setAction(Hysteria2VpnService.ACTION_STOP_VPN)
        getApplication<Application>().startService(intent)
    }
}
