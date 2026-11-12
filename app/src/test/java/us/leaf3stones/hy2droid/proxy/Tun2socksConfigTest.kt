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

    @Test
    fun `forwarded DNS emits no mapdns block`() {
        val yaml = Tun2socksConfig.build(emptyList())

        assertFalse(yaml.contains("mapdns"))
    }

    @Test
    fun `mapped DNS is emitted as a top-level mapdns block`() {
        val yaml = Tun2socksConfig.build(emptyList(), mapDns = true)

        // Sibling of tunnel/socks5/misc, at column zero: hev only walks the root
        // mapping, so an indented block would be silently ignored.
        assertTrue(yaml.lines().contains("mapdns:"))
        assertFalse(yaml.lines().any { it.startsWith(" ") && it.trim() == "mapdns:" })
        assertTrue(
            yaml.contains(
                "mapdns:\n" +
                    "  address: ${Tun.MAPPED_DNS}\n" +
                    "  port: ${Tun.MAPPED_DNS_PORT}\n" +
                    "  network: ${Tun.MAPPED_DNS_POOL}\n" +
                    "  netmask: ${Tun.MAPPED_DNS_NETMASK}\n" +
                    "  cache-size: ${Tun.MAPPED_DNS_CACHE_SIZE}\n",
            ),
        )
        // The block must come after the tunnel/socks section and, when there are
        // block rules, before `misc:`.
        assertTrue(yaml.indexOf("mapdns:") > yaml.indexOf("socks5:"))
    }

    @Test
    fun `blocking port 53 does not break local DNS`() {
        val yaml = Tun2socksConfig.build(
            listOf(BlockRule(tcp = false, udp = true, portFrom = 1, portTo = 1024)),
            mapDns = true,
        )

        // 53 is carved out of the range instead of dropping the whole rule.
        assertTrue(yaml.contains("block-udp: '1-52,54-1024'"))
    }

    @Test
    fun `a rule that only blocks port 53 vanishes in mapped mode`() {
        val yaml = Tun2socksConfig.build(
            listOf(BlockRule(tcp = false, udp = true, portFrom = 53)),
            mapDns = true,
        )

        assertFalse(yaml.contains("block-udp"))
        assertFalse(yaml.contains("misc:"))
    }

    @Test
    fun `port 53 is blocked normally when DNS is forwarded`() {
        val yaml = Tun2socksConfig.build(
            listOf(BlockRule(tcp = false, udp = true, portFrom = 53)),
            mapDns = false,
        )

        assertTrue(yaml.contains("block-udp: '53'"))
    }
}

