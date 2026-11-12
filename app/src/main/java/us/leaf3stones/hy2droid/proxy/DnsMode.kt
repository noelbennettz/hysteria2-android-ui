package us.leaf3stones.hy2droid.proxy

import us.leaf3stones.hy2droid.data.SettingsStore

/**
 * Where names inside the tunnel are resolved.
 *
 * A custom server wins over the cache switch (one setting decides, so the two
 * controls in Settings can never disagree): "Custom DNS server" means the user
 * picked the resolvers, and the cache switch only applies when it is off.
 */
enum class DnsMode {
    /**
     * Answered inside the tunnel by the engine: an A query gets an address from
     * its mapped pool immediately, and the *name* travels in the SOCKS5 request
     * so the server resolves it. No DNS round trip at all.
     */
    Local,

    /** Real public resolvers pushed into the TUN, no cache. */
    Forwarded,

    /** The user's own primary/secondary. */
    Custom,
}

/** Pure decision, unit-tested; keeps [Hysteria2VpnService] free of branching. */
fun dnsMode(customEnabled: Boolean, localAnswers: Boolean): DnsMode = when {
    customEnabled -> DnsMode.Custom
    localAnswers -> DnsMode.Local
    else -> DnsMode.Forwarded
}

/**
 * The resolver list pushed into the TUN for [mode]. Never empty: Android has no
 * "no DNS means use the tunnel" mode, so an empty list makes the queries fall
 * back to the underlying network and leak.
 *
 * [DnsMode.Local] is exactly one server on purpose: the engine only intercepts
 * queries addressed to [Tun.MAPPED_DNS], so a second resolver would send a share
 * of the lookups through the tunnel and reintroduce the round trip.
 */
fun dnsServersFor(
    mode: DnsMode,
    primary: String,
    secondary: String,
): List<String> = when (mode) {
    DnsMode.Local -> listOf(Tun.MAPPED_DNS)

    DnsMode.Forwarded -> SettingsStore.DEFAULT_DNS_SERVERS

    DnsMode.Custom -> listOf(primary, secondary)
        .map { it.trim() }
        // The in-tunnel resolver is not an upstream resolver: pushing it here
        // would loop the queries back into the engine instead of the user's.
        .filter { it.isNotEmpty() && it != Tun.MAPPED_DNS }
        .distinct()
        .ifEmpty { SettingsStore.DEFAULT_DNS_SERVERS }
}
