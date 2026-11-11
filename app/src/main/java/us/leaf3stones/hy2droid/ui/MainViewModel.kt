package us.leaf3stones.hy2droid.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import us.leaf3stones.hy2droid.data.BlockRule
import us.leaf3stones.hy2droid.data.DEFAULT_BLOCK_RULES
import us.leaf3stones.hy2droid.data.DiagnosticsExporter
import us.leaf3stones.hy2droid.data.HysteriaConfigBuilder
import us.leaf3stones.hy2droid.data.Profile
import us.leaf3stones.hy2droid.data.ProfileStore
import us.leaf3stones.hy2droid.data.SettingsStore
import us.leaf3stones.hy2droid.proxy.Hysteria2VpnService
import us.leaf3stones.hy2droid.proxy.LatencyProbe
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

    val showServerAddress: StateFlow<Boolean> = settings.showServerAddress
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val autoTestLatency: StateFlow<Boolean> = settings.autoTestLatency
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val customDnsEnabled: StateFlow<Boolean> = settings.customDnsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val customDnsServer: StateFlow<String> = settings.customDnsServer
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_DNS)

    val customDnsServer2: StateFlow<String> = settings.customDnsServer2
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val bypassLan: StateFlow<Boolean> = settings.bypassLan
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val blockRules: StateFlow<List<BlockRule>> = settings.blockRules
        .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_BLOCK_RULES)

    val tunnelState: StateFlow<TunnelState> = TunnelStatus.state
    val stats: StateFlow<TunnelStats> = TunnelStatus.stats

    /** Measured latency per profile id; a `null` value means the probe failed. */
    private val _latency = MutableStateFlow<Map<String, Int?>>(emptyMap())
    val latency: StateFlow<Map<String, Int?>> = _latency

    /** Profile ids whose probe is in flight right now. */
    private val _latencyTesting = MutableStateFlow<Set<String>>(emptySet())
    val latencyTesting: StateFlow<Set<String>> = _latencyTesting

    private var latencyJob: Job? = null
    private var autoTestStarted = false

    init {
        // One-off: profiles saved by older versions carry plaintext secrets.
        viewModelScope.launch { store.encryptLegacyCredentials() }
    }

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

    fun setShowServerAddress(enabled: Boolean) {
        viewModelScope.launch { settings.setShowServerAddress(enabled) }
    }

    fun setAutoTestLatency(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoTestLatency(enabled) }
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

    /**
     * Start the automatic latency pass once per app process, when the user has
     * enabled it in Settings. Idempotent: repeated calls (recomposition, coming
     * back from another screen) do nothing.
     */
    fun maybeAutoTestLatency(enabled: Boolean) {
        if (!enabled || autoTestStarted) return
        autoTestStarted = true
        testAllLatency()
    }

    /** Probe a single node and store the result next to the profile list. */
    fun testLatency(profile: Profile) {
        viewModelScope.launch { probe(profile) }
    }

    /** Probe every node, one after another (so results appear incrementally). */
    fun testAllLatency() {
        if (latencyJob?.isActive == true) return
        latencyJob = viewModelScope.launch {
            // Read through the store instead of the StateFlow: it may still hold
            // the empty initial value right after process start.
            val all = runCatching { store.profiles.first() }.getOrDefault(emptyList())
            for (profile in all) probe(profile)
        }
    }

    private suspend fun probe(profile: Profile) {
        if (profile.id in _latencyTesting.value) return
        _latencyTesting.update { it + profile.id }
        try {
            val millis = LatencyProbe(getApplication<Application>()).measure(profile)
            _latency.update { it + (profile.id to millis) }
        } finally {
            _latencyTesting.update { it - profile.id }
        }
    }

    /**
     * Write a diagnostics zip to [uri] (a document the user picked) and report
     * success on the main thread, so the caller can show a toast.
     */
    fun exportDiagnostics(uri: Uri, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = DiagnosticsExporter(getApplication<Application>()).export(uri)
            onResult(ok)
        }
    }
}
