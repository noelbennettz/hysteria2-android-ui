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
     * The resolver pushed into the TUN while DNS is answered inside the tunnel.
     *
     * `libtun2socks.so` (hev) intercepts UDP addressed to it and answers A
     * queries itself, then forwards the *name* over SOCKS5 so the server does
     * the lookup — see `native/tun2socks/README.md`. 192.0.2.0/24 is RFC 5737
     * TEST-NET-1, and it is deliberately **outside** 10.0.0.0/8: "bypass local
     * network" excludes 10/8, and an excluded address never reaches the TUN.
     * It must also stay outside [MAPPED_DNS_POOL] so an answer cannot collide
     * with the resolver itself.
     */
    const val MAPPED_DNS = "192.0.2.53"

    /** Port the engine answers [MAPPED_DNS] on. */
    const val MAPPED_DNS_PORT = 53

    /** The fake-IP pool: RFC 2544 space, never a real destination. */
    const val MAPPED_DNS_POOL = "198.18.0.0"
    const val MAPPED_DNS_NETMASK = "255.254.0.0"

    /** Name → address mappings kept by the engine (bounded LRU). */
    const val MAPPED_DNS_CACHE_SIZE = 10000
}
