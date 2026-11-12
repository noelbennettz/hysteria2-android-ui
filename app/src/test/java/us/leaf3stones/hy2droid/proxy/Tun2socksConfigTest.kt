package us.leaf3stones.hy2droid.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import us.leaf3stones.hy2droid.data.BlockRule
import us.leaf3stones.hy2droid.data.DEFAULT_BLOCK_RULES
import us.leaf3stones.hy2droid.data.SettingsStore

class Tun2socksConfigTest {

    @Test
    fun `no rules means a bare misc section with the tcp buffer`() {
        val yaml = Tun2socksConfig.build(emptyList())

        assertTrue(yaml.contains("socks5:\n  port: 1080\n  address: 127.0.0.1\n  udp: 'udp'"))
        assertTrue(yaml.contains("ipv4: 10.0.88.88"))
        assertTrue(yaml.contains("misc:\n  tcp-buffer-size: "))
        assertFalse(yaml.contains("block-"))
    }

    @Test
    fun `udp rules are emitted as block-udp`() {
        val yaml = Tun2socksConfig.build(listOf(BlockRule.QUIC))

        assertTrue(yaml.contains("  block-udp: '443'"))
        assertFalse(yaml.contains("block-tcp"))
    }

    @Test
    fun `the default rule set blocks QUIC out of the box`() {
        val yaml = Tun2socksConfig.build(DEFAULT_BLOCK_RULES)

        assertEquals(listOf(BlockRule.QUIC), DEFAULT_BLOCK_RULES)
        assertTrue(yaml.contains("  block-udp: '443'"))
        assertFalse(yaml.contains("block-tcp"))
    }

    @Test
    fun `both protocols are emitted, tcp first, sorted`() {
        val yaml = Tun2socksConfig.build(
            listOf(
                BlockRule(tcp = false, udp = true, portFrom = 443),
                BlockRule(tcp = true, udp = false, portFrom = 465),
                BlockRule(tcp = true, udp = false, portFrom = 25),
                BlockRule(tcp = true, udp = true, portFrom = 8000, portTo = 8100),
            ),
        )

        assertTrue(yaml.contains("  block-tcp: '25,465,8000-8100'\n  block-udp: '443,8000-8100'\n"))
    }

    @Test
    fun `the interface MTU defaults to the settings default`() {
        val yaml = Tun2socksConfig.build(emptyList())

        assertTrue(yaml.contains("mtu: ${SettingsStore.DEFAULT_MTU}"))
    }

    @Test
    fun `a custom MTU is written into the tunnel section`() {
        val yaml = Tun2socksConfig.build(emptyList(), mtu = 1380)

        assertTrue(yaml.contains("mtu: 1380"))
    }

    @Test
    fun `the tcp buffer defaults to the settings default, in bytes`() {
        val yaml = Tun2socksConfig.build(emptyList())

        assertTrue(
            yaml.contains("tcp-buffer-size: ${SettingsStore.DEFAULT_TCP_BUFFER_KB * 1024}"),
        )
    }

    @Test
    fun `the tcp buffer follows the chosen size`() {
        val yaml = Tun2socksConfig.build(emptyList(), tcpBufferKb = 256)

        assertTrue(yaml.contains("tcp-buffer-size: 262144"))
    }

    @Test
    fun `icmp is left alone by default`() {
        val yaml = Tun2socksConfig.build(emptyList())

        assertFalse(yaml.contains("icmp"))
    }

    @Test
    fun `answering ping locally is opt-in and uses the engine's reply mode`() {
        val yaml = Tun2socksConfig.build(emptyList(), fakeIcmp = true)

        // hev parses `tunnel.icmp` as a string and only enables the flag for "reply".
        assertTrue(yaml.contains("  ipv6: 'fc00::1'\n  icmp: reply\n"))
    }

    @Test
    fun `only offered buffer sizes survive, rounded to the nearest`() {
        // A restored backup or a hand-edited preference cannot ask for nonsense.
        assertEquals(64, SettingsStore.clampTcpBufferKb(0))
        assertEquals(64, SettingsStore.clampTcpBufferKb(70))
        assertEquals(128, SettingsStore.clampTcpBufferKb(120))
        assertEquals(256, SettingsStore.clampTcpBufferKb(4096))
        assertEquals(131072, SettingsStore.tcpBufferBytes(128))
    }
}
