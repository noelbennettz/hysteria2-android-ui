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
    private val keyLocalDns = booleanPreferencesKey("answer_dns_locally")
    private val keyTcpBuffer = intPreferencesKey("tcp_buffer_kb")
    private val keyFakeIcmp = booleanPreferencesKey("answer_ping_locally")



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
        .map { it[keyDnsServer] ?: DEFAULT_DNS_PRIMARY }

    /** The optional secondary DNS server address ("" when unset). */
    val customDnsServer2: Flow<String> = context.settingsDataStore.data
        .map { it[keyDnsServer2] ?: "" }

    /**
     * Whether names are answered inside the tunnel (hev's `mapdns`) instead of
     * being forwarded to a public resolver. Default on: the answer is instant and
     * the *name* travels in the SOCKS5 request, so the server resolves it with no
     * extra round trip — which is what a high-latency tunnel needs most.
     * Ignored while a custom DNS server is set — see `proxy/dnsMode()`.
     */
    val localDnsEnabled: Flow<Boolean> = context.settingsDataStore.data
        .map { it[keyLocalDns] ?: true }

    /**
     * Per-connection TCP send buffer of the tunnel engine, in KiB.
     *
     * The tun hop between the app and the engine is local, so this is a burst
     * absorber, not a wide-area window: it bounds what can queue while the
     * reader is slow, and it does not decide the ceiling of a fast link (that
     * is the QUIC path, its socket buffers and the CPU). Each session pays the
     * buffer plus a fixed 84 KiB task stack, so the default is a compromise.
     */
    val tcpBufferKb: Flow<Int> = context.settingsDataStore.data
        .map { it[keyTcpBuffer] ?: DEFAULT_TCP_BUFFER_KB }

    /**
     * Whether ICMP echo is answered inside the tunnel (`tunnel.icmp: reply`).
     *
     * Hysteria forwards TCP and UDP only, so a real ping cannot cross it. With
     * this on, the engine answers the echo locally, which keeps apps and games
     * that gate on ping happy — at the price of `ping` no longer telling you
     * anything about the path. Off by default.
     */
    val fakeIcmp: Flow<Boolean> = context.settingsDataStore.data
        .map { it[keyFakeIcmp] ?: false }



    /** Whether local-network ranges bypass the tunnel. Default off; Android 13+. */
    val bypassLan: Flow<Boolean> = context.settingsDataStore.data
        .map { it[keyBypassLan] ?: false }

    /**
     * TUN interface MTU. Defaults to 1400, which carries a full-size QUIC datagram
     * on a 1500-byte path without the packet count of the 1280 floor; lower it on
     * networks that drop full-size packets (see also [disablePathMtuDiscovery]).
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

    suspend fun setLocalDnsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyLocalDns] = enabled }
    }


    suspend fun setBypassLan(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyBypassLan] = enabled }
    }

    suspend fun setMtu(mtu: Int) {
        context.settingsDataStore.edit { it[keyMtu] = clampMtu(mtu) }
    }

    suspend fun setTcpBufferKb(kb: Int) {
        context.settingsDataStore.edit { it[keyTcpBuffer] = clampTcpBufferKb(kb) }
    }

    suspend fun setFakeIcmp(enabled: Boolean) {
        context.settingsDataStore.edit { it[keyFakeIcmp] = enabled }
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
         * Resolvers pushed into the tunnel when the custom DNS switch is off: Quad9
         * first, Cloudflare as the backup. Any address works — the query is routed
         * into the VPN and answered from the server's egress — but Android needs at
         * least one, otherwise it resolves through the underlying network and leaks
         * DNS outside the tunnel.
         */
        const val DEFAULT_DNS_PRIMARY = "9.9.9.9"

        /** Backup resolver, used when the primary does not answer. */
        const val DEFAULT_DNS_SECONDARY = "1.1.1.1"

        /** The two addresses above, in the order they are pushed to the tun. */
        val DEFAULT_DNS_SERVERS: List<String> = listOf(DEFAULT_DNS_PRIMARY, DEFAULT_DNS_SECONDARY)

        /**
         * Default TUN MTU: 1400. Fits a full QUIC datagram inside a 1500-byte path
         * — so it carries fewer packets than 1280 does — while still leaving room
         * for the tunnel headers on paths that clamp a little lower. Drop it to
         * 1280 (Settings → Network) only on networks that stall on full-size
         * packets.
         */
        const val DEFAULT_MTU = 1400

        /** Smallest MTU still able to carry IPv6; below this nothing works. */
        const val MIN_MTU = 1280

        /** Largest MTU worth offering; the path itself is almost never bigger. */
        const val MAX_MTU = 1500

        /** Keep a user-supplied MTU inside [MIN_MTU]..[MAX_MTU]. */
        fun clampMtu(value: Int): Int = value.coerceIn(MIN_MTU, MAX_MTU)

        /**
         * TCP buffer sizes offered in Settings, in KiB. The upper bound is the
         * engine's own ceiling: lwIP is built with `TCP_SND_BUF = 32 * TCP_MSS`
         * (262,112 bytes at MSS 8191); a larger value is clamped away there, so
         * offering more would be a lie in the UI.
         */
        val TCP_BUFFER_CHOICES_KB = listOf(64, 128, 256)

        /** 64 KiB is the engine's default; 128 KiB is twice the window for free. */
        const val DEFAULT_TCP_BUFFER_KB = 128

        /** The nearest offered size, so a restored/hand-edited value is usable. */
        fun clampTcpBufferKb(value: Int): Int =
            TCP_BUFFER_CHOICES_KB.minByOrNull { kotlin.math.abs(it - value) }
                ?: DEFAULT_TCP_BUFFER_KB

        /** Bytes handed to the engine's `misc.tcp-buffer-size`. */
        fun tcpBufferBytes(kb: Int): Int = clampTcpBufferKb(kb) * 1024

    }
}
