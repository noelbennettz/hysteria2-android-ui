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
    fun `a custom server wins over the local-answer switch`() {
        assertEquals(DnsMode.Custom, dnsMode(customEnabled = true, localAnswer = true))
        assertEquals(DnsMode.Custom, dnsMode(customEnabled = true, localAnswer = false))
    }

    @Test
    fun `local answers are the default when nothing custom is set`() {
        assertEquals(DnsMode.Local, dnsMode(customEnabled = false, localAnswer = true))
    }

    @Test
    fun `forwarded is the escape hatch`() {
        assertEquals(DnsMode.Forwarded, dnsMode(customEnabled = false, localAnswer = false))
    }

    @Test
    fun `local mode pushes exactly one resolver`() {
        // A second resolver would send a share of the lookups through the tunnel:
        // one round trip each, answered by a different party than the mapped ones.
        assertEquals(listOf(Tun.MAPPED_DNS), dnsServersFor(DnsMode.Local, "8.8.8.8", "8.8.4.4"))
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
    fun `the tun address and the mapped resolver are not upstream resolvers`() {
        assertEquals(SettingsStore.DEFAULT_DNS_SERVERS, dnsServersFor(DnsMode.Custom, Tun.IPV4, ""))
        assertEquals(
            SettingsStore.DEFAULT_DNS_SERVERS,
            dnsServersFor(DnsMode.Custom, Tun.MAPPED_DNS, ""),
        )
        assertEquals(
            listOf("1.0.0.1"),
            dnsServersFor(DnsMode.Custom, "1.0.0.1", Tun.IPV4),
        )
    }

    @Test
    fun `the mapped resolver is not the tun address`() {
        assertNotEquals(Tun.IPV4, Tun.MAPPED_DNS)
    }

    /**
     * "Bypass local network" excludes [LanRanges] from the VPN. An excluded
     * address never reaches hev, so a mapped resolver inside one of them would
     * turn DNS into a black hole for everyone with that switch on.
     */
    @Test
    fun `the mapped resolver is outside every lan range and outside the fake-ip pool`() {
        // IPv4 ranges only: MAPPED_DNS is IPv4, and the IPv6 entries are not
        // reachable from an IPv4 destination anyway.
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
        // The pool itself has to be reachable through the tunnel, or the
        // answers hev hands out would not come back to it.
        for ((network, prefix) in LanRanges.IPV4) {
            assertFalse(
                "$network/$prefix must not contain the fake-ip pool",
                inPrefix(Tun.MAPPED_DNS_POOL, network, prefix) ||
                    inPrefix(network, Tun.MAPPED_DNS_POOL, poolPrefix),
            )
        }
        assertTrue(poolPrefix > 0)
    }


    private fun inPrefix(address: String, network: String, prefix: Int): Boolean {
        val mask = if (prefix <= 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return (toLong(address) and mask) == (toLong(network) and mask)
    }

    private fun prefixLength(netmask: String): Int =
        toLong(netmask).countOneBits()

    private fun toLong(address: String): Long {
        val parts = address.split('.')
        require(parts.size == 4) { "not an IPv4 address: $address" }
        return parts.fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
    }
}
