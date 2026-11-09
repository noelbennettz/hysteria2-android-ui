package us.leaf3stones.hy2droid.proxy

import us.leaf3stones.hy2droid.data.BlockRule
import us.leaf3stones.hy2droid.data.tcpPortSpec
import us.leaf3stones.hy2droid.data.udpPortSpec

/**
 * The tun2socks config that bridges the Android TUN device to the core's local
 * SOCKS5 listener. Kept compatible with what the bundled `libtun2socks.so`
 * (hev-socks5-tunnel, see `native/tun2socks/`) expects; the SOCKS5 port must
 * match [us.leaf3stones.hy2droid.data.HysteriaConfigBuilder.DEFAULT_SOCKS_PORT].
 *
 * Blocked traffic is emitted as `misc.block-tcp` / `misc.block-udp` (ports and
 * ranges, comma separated), which the patched tunnel refuses locally: a blocked
 * TCP port is answered with a RST, a blocked UDP port is dropped. Nothing of a
 * blocked flow ever reaches the Hysteria core.
 */
object Tun2socksConfig {
    /** File name (under `filesDir`) the native library reads. */
    const val FILE_NAME = "tun2socks.yaml"

    /** Local SOCKS5 port of the Hysteria core. */
    const val SOCKS_PORT = 1080

    /** Render the YAML for [blockRules] (empty = no filtering at all). */
    fun build(blockRules: List<BlockRule> = emptyList()): String {
        val lines = mutableListOf(
            "tunnel:",
            "  name: tun0",
            "  mtu: 1500",
            "  multi-queue: false",
            "  ipv4: 10.0.88.88",
            "  ipv6: 'fc00::1'",
            "",
            "socks5:",
            "  port: $SOCKS_PORT",
            "  address: 127.0.0.1",
            "  udp: 'udp'",
        )
        val tcp = blockRules.tcpPortSpec()
        val udp = blockRules.udpPortSpec()
        if (tcp.isNotEmpty() || udp.isNotEmpty()) {
            lines += ""
            lines += "misc:"
            if (tcp.isNotEmpty()) lines += "  block-tcp: '$tcp'"
            if (udp.isNotEmpty()) lines += "  block-udp: '$udp'"
        }
        return lines.joinToString("\n", postfix = "\n")
    }
}

