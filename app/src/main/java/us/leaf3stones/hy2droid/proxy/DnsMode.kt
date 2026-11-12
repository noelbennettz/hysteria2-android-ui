package us.leaf3stones.hy2droid.proxy

import us.leaf3stones.hy2droid.data.SettingsStore

/**
 * Where names inside the tunnel are resolved.
 *
 * A custom server wins over the mode switch (one setting decides, so the two
 * controls in Settings can never disagree): "Custom DNS server" means the user
 * picked the resolvers, and the local-answer switch only applies when it is off.
 */
enum class DnsMode {
    /**
     * hev answers A queries itself with an address from its mapped pool and
     * forwards the *name* over SOCKS5, so the server resolves it. No round trip
     * per lookup; requires a working resolver on the server.
     */
    Local,

    /** Today's behaviour: real public resolvers pushed into the TUN. */
    Forwarded,

    /** The user's own primary/secondary. */
    Custom,
}

/** Pure decision, unit-tested; keeps [Hysteria2VpnService] free of branching. */
fun dnsMode(customEnabled: Boolean, localAnswer: Boolean): DnsMode = when {
    customEnabled -> DnsMode.Custom
    localAnswer -> DnsMode.Local
    else -> DnsMode.Forwarded
}

/**
 * The resolver list pushed into the TUN for [mode]. Never empty: Android has no
 * "no DNS means use the tunnel" mode, so an empty list makes the queries fall
 * back to the underlying network and leak.
 *
 * [DnsMode.Local] is exactly one server on purpose: hev only intercepts queries
 * addressed to [Tun.MAPPED_DNS], so a second resolver would send a share of the
 * lookups through the tunnel and reintroduce both the round trip and split-brain
 * answers between the two paths.
 */
fun dnsServersFor(mode: DnsMode, primary: String, secondary: String): List<String> = when (mode) {
    DnsMode.Local -> listOf(Tun.MAPPED_DNS)
    DnsMode.Forwarded -> SettingsStore.DEFAULT_DNS_SERVERS
    DnsMode.Custom -> listOf(primary, secondary)
        .map { it.trim() }
        // The TUN's own address and the mapped resolver address are not
        // upstream resolvers; pushing one of them turns every lookup into a
        // loop or a black hole.
        .filter { it.isNotEmpty() && it != Tun.IPV4 && it != Tun.MAPPED_DNS }
        .distinct()
        .ifEmpty { SettingsStore.DEFAULT_DNS_SERVERS }
}
