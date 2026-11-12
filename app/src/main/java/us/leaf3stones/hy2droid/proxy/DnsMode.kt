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
     * Queries go to the app's own caching resolver on the TUN address; a hit is
     * answered locally, a miss is resolved through the core.
     */
    Cache,

    /** Real public resolvers pushed into the TUN, no cache. */
    Forwarded,

    /** The user's own primary/secondary. */
    Custom,
}

/** Pure decision, unit-tested; keeps [Hysteria2VpnService] free of branching. */
fun dnsMode(customEnabled: Boolean, cacheEnabled: Boolean): DnsMode = when {
    customEnabled -> DnsMode.Custom
    cacheEnabled -> DnsMode.Cache
    else -> DnsMode.Forwarded
}

/**
 * The resolver list pushed into the TUN for [mode]. Never empty: Android has no
 * "no DNS means use the tunnel" mode, so an empty list makes the queries fall
 * back to the underlying network and leak.
 *
 * [cacheAvailable] is false when the resolver could not take port 53 (another
 * VPN app, an OEM restriction); then the cache mode degrades to the forwarded
 * defaults instead of leaving the tunnel without a working resolver.
 */
fun dnsServersFor(
    mode: DnsMode,
    primary: String,
    secondary: String,
    cacheAvailable: Boolean = true,
): List<String> = when (mode) {
    DnsMode.Cache ->
        if (cacheAvailable) listOf(Tun.CACHE_DNS) else SettingsStore.DEFAULT_DNS_SERVERS

    DnsMode.Forwarded -> SettingsStore.DEFAULT_DNS_SERVERS

    DnsMode.Custom -> listOf(primary, secondary)
        .map { it.trim() }
        // The TUN's own address is not an upstream resolver: pushing it would
        // send every lookup to the local cache instead of the user's server.
        .filter { it.isNotEmpty() && it != Tun.CACHE_DNS }
        .distinct()
        .ifEmpty { SettingsStore.DEFAULT_DNS_SERVERS }
}
