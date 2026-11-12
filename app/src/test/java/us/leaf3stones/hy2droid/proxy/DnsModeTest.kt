package us.leaf3stones.hy2droid.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import us.leaf3stones.hy2droid.data.LanRanges
import us.leaf3stones.hy2droid.data.SettingsStore

/** Which resolver list is pushed into the TUN, and why it is safe to push. */
class DnsModeTest {

    @Test
    fun `a custom server wins over the cache switch`() {
        assertEquals(DnsMode.Custom, dnsMode(customEnabled = true, localAnswers = true))
        assertEquals(DnsMode.Custom, dnsMode(customEnabled = true, localAnswers = false))
    }

    @Test
    fun `answering in the tunnel is the default when nothing custom is set`() {
        assertEquals(DnsMode.Local, dnsMode(customEnabled = false, localAnswers = true))
    }

    @Test
    fun `forwarded is the escape hatch`() {
        assertEquals(DnsMode.Forwarded, dnsMode(customEnabled = false, localAnswers = false))
    }

    @Test
    fun `local answers push exactly one resolver, the mapped one`() {
        assertEquals(
            listOf(Tun.MAPPED_DNS),
            dnsServersFor(DnsMode.Local, "8.8.8.8", "8.8.4.4"),
        )
        // A second resolver would send a share of the lookups through the tunnel.
        assertEquals(1, dnsServersFor(DnsMode.Local, "", "").size)
    }

    @Test
    fun `forwarded mode is bit-identical to the old behaviour`() {
        assertEquals(
            listOf("9.9.9.9", "1.1.1.1"),
            dnsServersFor(DnsMode.Forwarded, "8.8.8.8", "8.8.4.4"),
        )
        assertEquals(
            SettingsStore.DEFAULT_DNS_SERVERS,
            dnsServersFor(DnsMode.Forwarded, "", ""),
        )
    }

    @Test
    fun `custom mode keeps the user order and drops blanks`() {
        assertEquals(
            listOf("1.0.0.1", "2606:4700:4700::1111"),
            dnsServersFor(DnsMode.Custom, " 1.0.0.1 ", "2606:4700:4700::1111"),
        )
        assertEquals(
            listOf("1.0.0.1"),
            dnsServersFor(DnsMode.Custom, "1.0.0.1", "  "),
        )
    }

    @Test
    fun `custom mode never falls back to an empty list`() {
        // An empty DNS list makes Android resolve through the underlying network,
        // i.e. a leak; the defaults are the safe answer.
        assertEquals(SettingsStore.DEFAULT_DNS_SERVERS, dnsServersFor(DnsMode.Custom, "", ""))
        assertEquals(SettingsStore.DEFAULT_DNS_SERVERS, dnsServersFor(DnsMode.Custom, " ", ""))
    }

    @Test
    fun `the tun address is not an upstream resolver`() {
        assertEquals(
            SettingsStore.DEFAULT_DNS_SERVERS,
            dnsServersFor(DnsMode.Custom, Tun.MAPPED_DNS, ""),
        )
        assertEquals(
            listOf("1.0.0.1"),
            dnsServersFor(DnsMode.Custom, "1.0.0.1", Tun.MAPPED_DNS),
        )
    }

    /**
     * The mapped resolver is answered by the tunnel engine, which only sees
     * packets that actually enter the TUN. "Bypass local network" excludes
     * [LanRanges] from the VPN, so the address must not live in any of them --
     * otherwise every query leaves over Wi-Fi and DNS dies silently. It must also
     * stay outside the fake-IP pool so an answer cannot collide with it.
     */
    @Test
    fun `the mapped resolver is outside the lan ranges and the fake-ip pool`() {
        for ((network, prefix) in LanRanges.IPV4) {
            assertFalse(
                "${Tun.MAPPED_DNS} must not be inside $network/$prefix",
                inPrefix(Tun.MAPPED_DNS, network, prefix),
            )
        }
        val poolPrefix = prefixLength(Tun.MAPPED_DNS_NETMASK)
        assertFalse(
            "${Tun.MAPPED_DNS} must not collide with a mapped answer",
            inPrefix(Tun.MAPPED_DNS, Tun.MAPPED_DNS_POOL, poolPrefix),
        )
        assertTrue(poolPrefix > 0)
    }

    @Test
    fun `the mapped resolver is not an upstream resolver`() {
        assertNotEquals(SettingsStore.DEFAULT_DNS_PRIMARY, Tun.MAPPED_DNS)
        assertNotEquals(Tun.IPV4, Tun.MAPPED_DNS)
    }

    /** Prefix length of a dotted netmask: 255.254.0.0 -> 15. */
    private fun prefixLength(netmask: String): Int {
        var mask = toLong(netmask)
        var bits = 0
        while (bits < 32 && (mask and (1L shl (31 - bits))) != 0L) bits++
        return bits
    }

    private fun inPrefix(address: String, network: String, prefix: Int): Boolean {
        val mask = if (prefix <= 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return (toLong(address) and mask) == (toLong(network) and mask)
    }

    private fun toLong(address: String): Long {
        val parts = address.split('.')
        require(parts.size == 4) { "not an IPv4 address: $address" }
        return parts.fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
    }
}
