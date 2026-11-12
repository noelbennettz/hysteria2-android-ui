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
    fun `no rules means no filtering section`() {
        val yaml = Tun2socksConfig.build(emptyList())

        assertTrue(yaml.contains("socks5:\n  port: 1080\n  address: 127.0.0.1\n  udp: 'udp'"))
        assertTrue(yaml.contains("ipv4: 10.0.88.88"))
        assertFalse(yaml.contains("misc:"))
        assertFalse(yaml.contains("block-"))
    }

    @Test
    fun `udp rules are emitted as block-udp`() {
        val yaml = Tun2socksConfig.build(listOf(BlockRule.QUIC))

        assertTrue(yaml.contains("misc:\n  block-udp: '443'"))
        assertFalse(yaml.contains("block-tcp"))
    }

    @Test
    fun `the default rule set blocks QUIC out of the box`() {
        val yaml = Tun2socksConfig.build(DEFAULT_BLOCK_RULES)

        assertEquals(listOf(BlockRule.QUIC), DEFAULT_BLOCK_RULES)
        assertTrue(yaml.contains("misc:\n  block-udp: '443'"))
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

        assertTrue(yaml.contains("misc:\n  block-tcp: '25,465,8000-8100'\n  block-udp: '443,8000-8100'\n"))
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

}
