package us.leaf3stones.hy2droid.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Global (non per-profile) app settings, persisted in a Preferences DataStore.
 * All keys default so a fresh install / restored backup behaves sensibly.
 */
class SettingsStore(private val context: Context) {
    private val keyExcluded = stringPreferencesKey("excluded_packages")
    private val keyDynamic = booleanPreferencesKey("dynamic_color")
    private val keyDnsEnabled = booleanPreferencesKey("custom_dns_enabled")
    private val keyDnsServer = stringPreferencesKey("custom_dns_server")
    private val keyDnsServer2 = stringPreferencesKey("custom_dns_server2")
    private val keyBypassLan = booleanPreferencesKey("bypass_lan")
    private val keyBlockRules = stringPreferencesKey("block_rules")
    private val keyShowAddress = booleanPreferencesKey("show_server_address")
    private val keyAutoTestLatency = booleanPreferencesKey("auto_test_latency")
    private val keyDisablePmtud = booleanPreferencesKey("disable_path_mtu_discovery")
    private val keyMtu = intPreferencesKey("tunnel_mtu")
    private val keyShowTraffic = booleanPreferencesKey("show_live_traffic")

    /** Package names excluded from the VPN (split-tunneling bypass). */
    val excluded: Flow<Set<String>> = context.settingsDataStore.data
        .map { decode(it[keyExcluded]) }

    /** Whether Material You dynamic color is applied. Default on. */
    val dynamicColor: Flow<Boolean> = context.settingsDataStore.data
        .map { it[keyDynamic] ?: true }

    /** Whether the profile cards show the server address. Default off (less noise). */
    val showServerAddress: Flow<Boolean> = context.settingsDataStore.data
        .map { it[keyShowAddress] ?: false }

    /**
     * Whether live throughput is shown (status card and notification). Off by
     * default: on Android 14 the notification can be swiped away, and a
     * throughput update would bring it straight back.
     */
    val showTraffic: Flow<Boolean> = context.settingsDataStore.data
        .map { it[keyShowTraffic] ?: false }

    /**
     * Whether node latency is measured when the app opens (and the list is then
     * ordered fastest first). Default off: it costs a QUIC handshake per node.
     */
    val autoTestLatency: Flow<Boolean> = context.settingsDataStore.data
        .map { it[keyAutoTestLatency] ?: false }

    /**
     * Turns off QUIC path-MTU discovery (`quic.disablePathMTUDiscovery`). Off by
     * default; a workaround for networks that silently drop large QUIC packets,
     * where connections stall until the peer gives up.
     */
    val disablePathMtuDiscovery: Flow<Boolean> = context.settingsDataStore.data
        .map { it[keyDisablePmtud] ?: false }

    /** Whether a custom upstream DNS server (pushed to the TUN) is used. Default off. */
    val customDnsEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[keyDnsEnabled] ?: false }

    /** The custom DNS server address. */
    val customDnsServer: Flow<String> = context.settingsDataStore.data
        .map { it[keyDnsServer] ?: DEFAULT_DNS }

    /** The optional secondary DNS server address ("" when unset). */
    val customDnsServer2: Flow<String> = context.settingsDataStore.data
        .map { it[keyDnsServer2] ?: "" }

    /** Whether local-network ranges bypass the tunnel. Default off; Android 13+. */
    val bypassLan: Flow<Boolean> = context.settingsDataStore.data
        .map { it[keyBypassLan] ?: false }

    /**
     * TUN interface MTU. 1450 leaves room for the QUIC/UDP/IP overhead inside a
     * 1500-byte path; a smaller value is the usual fix for networks that stall on
     * full-size packets (see also [disablePathMtuDiscovery]).
     */
    val mtu: Flow<Int> = context.settingsDataStore.data
        .map { clampMtu(it[keyMtu] ?: DEFAULT_MTU) }

    /**
     * Traffic that is refused before it reaches the core. Defaults to QUIC
     * (UDP/443), which Hysteria 2 cannot accelerate; an explicit (even empty)
     * stored list always wins over the default.
     */
    val blockRules: Flow<List<BlockRule>> = context.settingsDataStore.data
        .map { prefs ->
            prefs[keyBlockRules]?.let { decodeRules(decode(it)) } ?: DEFAULT_BLOCK_RULES
        }

    suspend fun setExcluded(packages: Set<String>) {
        context.settingsDataStore.edit { it[keyExcluded] = encode(packages) }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyDynamic] = enabled }
    }

    suspend fun setShowServerAddress(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyShowAddress] = enabled }
    }

    suspend fun setShowTraffic(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyShowTraffic] = enabled }
    }

    suspend fun setAutoTestLatency(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyAutoTestLatency] = enabled }
    }

    suspend fun setDisablePathMtuDiscovery(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyDisablePmtud] = enabled }
    }

    suspend fun setCustomDnsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyDnsEnabled] = enabled }
    }

    suspend fun setCustomDnsServer(server: String) {
        context.settingsDataStore.edit { it[keyDnsServer] = server }
    }

    suspend fun setCustomDnsServer2(server: String) {
        context.settingsDataStore.edit { it[keyDnsServer2] = server }
    }

    suspend fun setBypassLan(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyBypassLan] = enabled }
    }

    suspend fun setMtu(mtu: Int) {
        context.settingsDataStore.edit { it[keyMtu] = clampMtu(mtu) }
    }

    suspend fun setBlockRules(rules: List<BlockRule>) {
        context.settingsDataStore.edit { it[keyBlockRules] = encode(rules.encodeRules().toSet()) }
    }

    private fun decode(json: String?): Set<String> {
        if (json.isNullOrBlank()) return emptySet()
        return try {
            val array = JSONArray(json)
            buildSet { for (i in 0 until array.length()) add(array.getString(i)) }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun encode(packages: Set<String>): String {
        val array = JSONArray()
        packages.forEach { array.put(it) }
        return array.toString()
    }

    companion object {
        /**
         * Resolver pushed into the tunnel when the custom DNS switch is off. Any
         * address works — the query is routed into the VPN and answered from the
         * server's egress — but Android needs one, otherwise it resolves through
         * the underlying network and leaks DNS outside the tunnel.
         */
        const val DEFAULT_DNS = "1.0.0.1"

        /**
         * Default TUN MTU. 1450 keeps a full QUIC datagram inside a 1500-byte
         * path, which avoids the fragmentation stalls seen at 1500.
         */
        const val DEFAULT_MTU = 1450

        /** Smallest MTU still able to carry IPv6; below this nothing works. */
        const val MIN_MTU = 1280

        /** Largest MTU worth offering; the path itself is almost never bigger. */
        const val MAX_MTU = 1500

        /** Keep a user-supplied MTU inside [MIN_MTU]..[MAX_MTU]. */
        fun clampMtu(value: Int): Int = value.coerceIn(MIN_MTU, MAX_MTU)
    }
}
