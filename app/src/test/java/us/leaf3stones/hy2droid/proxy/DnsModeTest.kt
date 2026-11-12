package us.leaf3stones.hy2droid.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import us.leaf3stones.hy2droid.data.LanRanges
import us.leaf3stones.hy2droid.data.SettingsStore

/** Which resolver list is pushed into the TUN, and why it is safe to push. */
class DnsModeTest {

    @Test
    fun `a custom server wins over the cache switch`() {
        assertEquals(DnsMode.Custom, dnsMode(customEnabled = true, cacheEnabled = true))
        assertEquals(DnsMode.Custom, dnsMode(customEnabled = true, cacheEnabled = false))
    }

    @Test
    fun `the cache is the default when nothing custom is set`() {
        assertEquals(DnsMode.Cache, dnsMode(customEnabled = false, cacheEnabled = true))
    }

    @Test
    fun `forwarded is the escape hatch`() {
        assertEquals(DnsMode.Forwarded, dnsMode(customEnabled = false, cacheEnabled = false))
    }

    @Test
    fun `cache mode pushes the tun's own address`() {
        assertEquals(
            listOf(Tun.CACHE_DNS),
            dnsServersFor(DnsMode.Cache, "8.8.8.8", "8.8.4.4", cacheAvailable = true),
        )
        // The kernel delivers queries for an interface address to a local socket,
        // so the resolver has to be that address.
        assertEquals(Tun.IPV4, Tun.CACHE_DNS)
    }

    @Test
    fun `cache mode falls back to real resolvers when port 53 is taken`() {
        assertEquals(
            SettingsStore.DEFAULT_DNS_SERVERS,
            dnsServersFor(DnsMode.Cache, "", "", cacheAvailable = false),
        )
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
            dnsServersFor(DnsMode.Custom, Tun.CACHE_DNS, ""),
        )
        assertEquals(
            listOf("1.0.0.1"),
            dnsServersFor(DnsMode.Custom, "1.0.0.1", Tun.CACHE_DNS),
        )
    }

    /**
     * The local resolver answers on the TUN's address, which sits inside the
     * "bypass local network" ranges (10.0.0.0/8). That is fine — an address on an
     * interface is reached through the kernel's local table before any excluded
     * route — but the resolver must never be a *routed* address, so keep the two
     * facts next to each other.
     */
    @Test
    fun `the cache resolver is a local interface address, not a routed one`() {
        assertTrue(Tun.CACHE_DNS == Tun.IPV4)
        assertNotEquals(SettingsStore.DEFAULT_DNS_PRIMARY, Tun.CACHE_DNS)
        // No LanRanges entry may collide with the upstream resolvers either.
        for ((network, prefix) in LanRanges.IPV4) {
            for (upstream in SettingsStore.DEFAULT_DNS_SERVERS) {
                assertTrue(
                    "$upstream must not be inside $network/$prefix",
                    !inPrefix(upstream, network, prefix),
                )
            }
        }
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
