package us.leaf3stones.hy2droid.proxy

import us.leaf3stones.hy2droid.data.BlockRule
import us.leaf3stones.hy2droid.data.SettingsStore
import us.leaf3stones.hy2droid.data.tcpPortSpec
import us.leaf3stones.hy2droid.data.udpPortSpec

/**
 * The tun2socks config that bridges the Android TUN device to the core's local
 * SOCKS5 listener. Kept compatible with what the bundled `libtun2socks.so`
 * (hev-socks5-tunnel, see `native/tun2socks/`) expects; the SOCKS5 port must
 * match [us.leaf3stones.hy2droid.data.HysteriaConfigBuilder.DEFAULT_SOCKS_PORT].
 * The MTU is handed in from the settings, so the interface and this file can
 * never disagree.
 *
 * Blocked traffic is emitted as `misc.block-tcp` / `misc.block-udp` (ports and
 * ranges, comma separated), which the patched tunnel refuses locally: a blocked
 * TCP port is answered with a RST, a blocked UDP port is dropped. Nothing of a
 * blocked flow ever reaches the Hysteria core.
 */
object Tun2socksConfig {
    /** File name (under `filesDir`) the native library reads. */
    const val FILE_NAME = "tun2socks.yaml"

    /** Render the YAML for [blockRules] (empty = no filtering at all). */
    fun build(
        blockRules: List<BlockRule> = emptyList(),
        mtu: Int = SettingsStore.DEFAULT_MTU,
        /**
         * The engine's per-connection send buffer on the tun hop. That hop is
         * local (sub-millisecond), so this bounds bursts and queues for a slow
         * reader rather than the throughput of a fast link.
         */
        tcpBufferKb: Int = SettingsStore.DEFAULT_TCP_BUFFER_KB,
    ): String {
        val lines = mutableListOf(
            "tunnel:",
            "  name: tun0",
            "  mtu: $mtu",
            "  multi-queue: false",
            "  ipv4: ${Tun.IPV4}",
            "  ipv6: '${Tun.IPV6}'",
            "",
            "socks5:",
            "  port: ${Tun.SOCKS_PORT}",
            "  address: 127.0.0.1",
            "  udp: 'udp'",
            "",
            "misc:",
            "  tcp-buffer-size: ${SettingsStore.tcpBufferBytes(tcpBufferKb)}",
        )
        val tcp = blockRules.tcpPortSpec()
        val udp = blockRules.udpPortSpec()
        if (tcp.isNotEmpty()) lines += "  block-tcp: '$tcp'"
        if (udp.isNotEmpty()) lines += "  block-udp: '$udp'"
        return lines.joinToString("\n", postfix = "\n")
    }
}
