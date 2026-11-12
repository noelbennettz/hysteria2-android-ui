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
     * The resolver pushed into the TUN while the in-tunnel cache is on.
     *
     * It is the TUN's *own* address on purpose: the address belongs to the tun
     * interface, so the kernel delivers DNS queries for it to the app's socket
     * instead of writing them into the TUN — the cache never has to be modelled
     * as a fake destination, and hev never sees the queries. See
     * `proxy/LocalDns.kt`.
     */
    const val CACHE_DNS = IPV4

    /** Port the local resolver listens on (and the one clients query). */
    const val CACHE_DNS_PORT = 53
}


