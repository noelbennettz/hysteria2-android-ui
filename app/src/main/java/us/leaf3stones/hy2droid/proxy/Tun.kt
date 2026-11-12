package us.leaf3stones.hy2droid.proxy

import us.leaf3stones.hy2droid.data.HysteriaConfigBuilder

/**
 * The single source of truth for the TUN parameters shared by the VpnService
 * (which creates the interface) and the tun2socks config (which tells
 * hev-socks5-tunnel where to send packets). These used to be duplicated in both
 * files with a "must match" comment, which fails silently when one drifts.
 */
object Tun {
    const val IPV4 = "10.0.88.88"
    const val PREFIX4 = 16
    const val IPV6 = "fc00::1"
    const val PREFIX6 = 64

    /** The core's local SOCKS5 port that the tunnel engine dials. */
    const val SOCKS_PORT = HysteriaConfigBuilder.DEFAULT_SOCKS_PORT

    /**
     * The resolver pushed into the TUN when DNS is answered locally
     * (`mapdns` in `tun2socks.yaml`, see [Tun2socksConfig]). hev intercepts
     * every datagram addressed to this address and answers it itself, so it is
     * never routable in the real world — it only has to be an address the TUN
     * route owns and that is *not* a real destination.
     *
     * 192.0.2.0/24 is RFC 5737 TEST-NET-1. It is deliberately **outside** the
     * TUN subnet (10.0.88.0/16): `LanRanges` excludes 10.0.0.0/8 from the VPN
     * when "Bypass local network" is on, and an excluded address never reaches
     * hev — DNS would silently die for every user with that switch on.
     * It must also stay outside [MAPPED_DNS_POOL], otherwise a mapped answer
     * could collide with the resolver address. `DnsModeTest` asserts both.
     */
    const val MAPPED_DNS = "192.0.2.53"

    /** Port hev answers [MAPPED_DNS] on; matches the pushed resolver. */
    const val MAPPED_DNS_PORT = 53

    /**
     * The fake-IP pool: RFC 2544 benchmark space, guaranteed not to collide with
     * a real destination. /15 gives 131072 addresses for a 10000 entry cache.
     */
    const val MAPPED_DNS_POOL = "198.18.0.0"
    const val MAPPED_DNS_NETMASK = "255.254.0.0"

    /** Bounded LRU of name → address mappings kept by hev. */
    const val MAPPED_DNS_CACHE_SIZE = 10000
}

