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
import us.leaf3stones.hy2droid.data.ConfigFile
import us.leaf3stones.hy2droid.data.DEFAULT_BLOCK_RULES
import us.leaf3stones.hy2droid.data.DiagnosticsExporter
import us.leaf3stones.hy2droid.data.HysteriaConfigBuilder
import us.leaf3stones.hy2droid.data.Profile
import us.leaf3stones.hy2droid.data.ProfileMerge
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

    val showTraffic: StateFlow<Boolean> = settings.showTraffic
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val autoTestLatency: StateFlow<Boolean> = settings.autoTestLatency
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val disablePathMtuDiscovery: StateFlow<Boolean> = settings.disablePathMtuDiscovery
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val customDnsEnabled: StateFlow<Boolean> = settings.customDnsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val customDnsServer: StateFlow<String> = settings.customDnsServer
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_DNS)

    val customDnsServer2: StateFlow<String> = settings.customDnsServer2
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val bypassLan: StateFlow<Boolean> = settings.bypassLan
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val mtu: StateFlow<Int> = settings.mtu
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_MTU)

    val blockRules: StateFlow<List<BlockRule>> = settings.blockRules
        .stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_BLOCK_RULES)

    val tunnelState: StateFlow<TunnelState> = TunnelStatus.state
    val stats: StateFlow<TunnelStats> = TunnelStatus.stats

    /** Profile the running tunnel was built for; `null` while nothing is up. */
    val activeProfileId: StateFlow<String?> = TunnelStatus.activeProfileId

    /** True when the system restarted the tunnel by itself (always-on VPN). */
    val restartedBySystem: StateFlow<Boolean> = TunnelStatus.restartedBySystem

    /** Measured latency per profile id; a `null` value means the probe failed. */
    private val _latency = MutableStateFlow<Map<String, Int?>>(emptyMap())
    val latency: StateFlow<Map<String, Int?>> = _latency

    /** Profile ids whose probe is in flight right now. */
    private val _latencyTesting = MutableStateFlow<Set<String>>(emptySet())
    val latencyTesting: StateFlow<Set<String>> = _latencyTesting

    private var latencyJob: Job? = null
    private var autoTestStarted = false

    init {
        // Seed the in-memory numbers with what the last session measured, so the
        // list is ordered from the first frame instead of after a full re-probe.
        viewModelScope.launch {
            store.latencies.collect { stored ->
                _latency.update { current ->
                    stored.filterKeys { it !in current } + current
                }
            }
        }
    }

    fun save(profile: Profile) {
        viewModelScope.launch { store.upsert(profile) }
    }

    /**
     * Import a profile from a share link or QR code: an entry pointing at the same
     * host and port is updated in place instead of being appended twice.
     */
    fun import(profile: Profile) {
        viewModelScope.launch {
            val existing = runCatching { store.profiles.first() }.getOrDefault(emptyList())
            val merged = ProfileMerge.resolve(existing, profile)
            store.upsert(merged)
            // Importing is an explicit "this is the one" act, so make it the
            // selection too; otherwise the power button and the tile keep using the
            // previous node and the import looks like it did nothing.
            store.select(merged.id)
        }
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

    fun setShowTraffic(enabled: Boolean) {
        viewModelScope.launch { settings.setShowTraffic(enabled) }
    }

    /** The always-on hint was shown; do not show it again for this restart. */
    fun acknowledgeSystemRestart() {
        TunnelStatus.setRestartedBySystem(false)
    }

    fun setAutoTestLatency(enabled: Boolean) {
        // Turning it back on is a fresh request: it should probe again even if this
        // process already did one pass.
        if (!enabled) autoTestStarted = false
        viewModelScope.launch { settings.setAutoTestLatency(enabled) }
    }

    fun setDisablePathMtuDiscovery(enabled: Boolean) {
        viewModelScope.launch { settings.setDisablePathMtuDiscovery(enabled) }
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

    fun setMtu(value: Int) {
        viewModelScope.launch { settings.setMtu(value) }
    }

    fun setBlockRules(rules: List<BlockRule>) {
        viewModelScope.launch { settings.setBlockRules(rules) }
    }

    /** Persist [profile] as the selected profile and bring the tunnel up for it. */
    fun connect(profile: Profile) {
        viewModelScope.launch { connectNow(profile) }
    }

    /**
     * Connect by id instead of by object, for the VPN-permission callback: a
     * configuration change while the system dialog is up recreates the activity,
     * and a `Profile` held in `remember` would be gone by the time it answers.
     */
    fun connectById(id: String) {
        viewModelScope.launch {
            val profile = runCatching { store.profiles.first() }
                .getOrDefault(emptyList())
                .firstOrNull { it.id == id } ?: return@launch
            connectNow(profile)
        }
    }

    private suspend fun connectNow(profile: Profile) {
        store.select(profile.id)
        val yaml = HysteriaConfigBuilder.build(
            profile,
            disablePathMtuDiscovery = settings.disablePathMtuDiscovery.first(),
        )
        // Written atomically: a torn config would be accepted by the service
        // ("the file exists") and fail the core on every start.
        withContext(Dispatchers.IO) { ConfigFile.write(configFile, yaml) }
        val intent = Intent(getApplication<Application>(), Hysteria2VpnService::class.java)
            .setAction(Hysteria2VpnService.ACTION_START_VPN)
            // Authoritative profile id: the selection can move between this write
            // and the service reading it.
            .putExtra(Hysteria2VpnService.EXTRA_PROFILE_ID, profile.id)
        ContextCompat.startForegroundService(getApplication<Application>(), intent)
    }

    /** Ask the running service to tear the tunnel down. */
    fun disconnect() {
        val intent = Intent(getApplication<Application>(), Hysteria2VpnService::class.java)
            .setAction(Hysteria2VpnService.ACTION_STOP_VPN)
        // Same reason as connect(): plain startService() throws on Android 12+ when
        // the app is in the background, and the service is a foreground one anyway.
        ContextCompat.startForegroundService(getApplication<Application>(), intent)
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

    /**
     * Probe every node that has no number yet, one after another (so results
     * appear incrementally). Nodes measured in an earlier session are skipped:
     * their cached value is already shown, and a full re-probe on every launch is
     * one QUIC handshake per node.
     */
    fun testAllLatency() {
        if (latencyJob?.isActive == true) return
        latencyJob = viewModelScope.launch {
            // Read through the store instead of the StateFlow: it may still hold
            // the empty initial value right after process start.
            val all = runCatching { store.profiles.first() }.getOrDefault(emptyList())
            // Read the cache from the store, not from `_latency`: the seeding
            // collector may not have run yet on a cold start, and probing
            // everything again would defeat the cache.
            val known = runCatching { store.latencies.first() }.getOrDefault(emptyMap())
            for (profile in all) {
                if (profile.id in known) continue
                probe(profile)
            }
        }
    }

    private suspend fun probe(profile: Profile) {
        if (profile.id in _latencyTesting.value) return
        _latencyTesting.update { it + profile.id }
        try {
            val millis = LatencyProbe(getApplication<Application>()).measure(
                profile,
                disablePathMtuDiscovery = settings.disablePathMtuDiscovery.first(),
            )
            _latency.update { it + (profile.id to millis) }
            store.setLatency(profile.id, millis)
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
