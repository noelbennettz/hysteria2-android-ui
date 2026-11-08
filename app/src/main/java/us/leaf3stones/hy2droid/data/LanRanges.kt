package us.leaf3stones.hy2droid.data

/**
 * Local-network ranges that are kept outside the VPN ("bypass LAN"), so NAS
 * boxes, printers, router admin pages, casting discovery (mDNS/SSDP) and
 * carrier-grade-NAT hotspots keep working while the tunnel is up.
 *
 * Stored as plain `address to prefixLength` pairs (each address is the network
 * address of its prefix) so this stays free of Android types and is unit
 * testable; the VPN service is the only place that builds `IpPrefix` objects.
 */
object LanRanges {
    /** RFC1918, link-local, CGNAT and multicast IPv4 ranges. */
    val IPV4: List<Pair<String, Int>> = listOf(
        "10.0.0.0" to 8,      // RFC1918
        "172.16.0.0" to 12,   // RFC1918
        "192.168.0.0" to 16,  // RFC1918
        "169.254.0.0" to 16,  // link-local (APIPA)
        "100.64.0.0" to 10,   // RFC6598 CGNAT, used by ISP hotspots
        "224.0.0.0" to 4,     // multicast: mDNS, SSDP, casting discovery
    )

    /** Unique-local, link-local and multicast IPv6 ranges. */
    val IPV6: List<Pair<String, Int>> = listOf(
        "fc00::" to 7,  // unique local addresses (ULA)
        "fe80::" to 10, // link-local
        "ff00::" to 8,  // multicast
    )

    /** Every range, IPv4 first. */
    val ALL: List<Pair<String, Int>> = IPV4 + IPV6

    /** First Android release whose VpnService can exclude routes (Android 13). */
    const val MIN_API = 33
}
