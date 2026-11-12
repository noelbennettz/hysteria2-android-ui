package us.leaf3stones.hy2droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The bounded, TTL-aware store the in-tunnel resolver keeps. */
class DnsCacheTest {

    private fun entry(storedAt: Long, ttl: Int) = DnsWire.Response(
        packet = ByteArray(8),
        storedAt = storedAt,
        ttlOffsets = intArrayOf(4),
        ttlSeconds = intArrayOf(ttl),
        ttl = ttl,
    )

    private fun question(name: String) = DnsWire.Question(name, type = 1, dnsClass = 1)

    @Test
    fun `an unknown question misses`() {
        assertNull(DnsCache().lookup(question("example.com"), id = 1, now = 0L))
    }

    @Test
    fun `a stored answer is served with the caller's id and an aged TTL`() {
        val cache = DnsCache()
        cache.store(question("example.com"), entry(storedAt = 0L, ttl = 300))

        val served = cache.lookup(question("example.com"), id = 0x4321, now = 100_000L)

        assertNotNull(served)
        assertEquals(0x4321, ((served!![0].toInt() and 0xFF) shl 8) or (served[1].toInt() and 0xFF))
        val ttl = ((served[4].toInt() and 0xFF) shl 24) or ((served[5].toInt() and 0xFF) shl 16) or
            ((served[6].toInt() and 0xFF) shl 8) or (served[7].toInt() and 0xFF)
        assertEquals(200, ttl)
    }

    @Test
    fun `the key is the question, so another name still misses`() {
        val cache = DnsCache()
        cache.store(question("example.com"), entry(storedAt = 0L, ttl = 300))

        assertNull(cache.lookup(question("example.net"), id = 1, now = 0L))
        // A different record type of the same name is a different key too.
        assertNull(cache.lookup(DnsWire.Question("example.com", 28, 1), id = 1, now = 0L))
    }

    @Test
    fun `an expired entry is dropped`() {
        val cache = DnsCache()
        cache.store(question("example.com"), entry(storedAt = 0L, ttl = 60))

        assertNull(cache.lookup(question("example.com"), id = 1, now = 60_000L))
        assertEquals(0, cache.size())
    }

    @Test
    fun `the cache is bounded and evicts the least recently used entry`() {
        val cache = DnsCache(maxEntries = 2)
        cache.store(question("a.test"), entry(storedAt = 0L, ttl = 300))
        cache.store(question("b.test"), entry(storedAt = 0L, ttl = 300))
        // Touch "a" so "b" becomes the least recently used one.
        cache.lookup(question("a.test"), id = 1, now = 0L)
        cache.store(question("c.test"), entry(storedAt = 0L, ttl = 300))

        assertEquals(2, cache.size())
        assertNotNull(cache.lookup(question("a.test"), id = 1, now = 0L))
        assertNotNull(cache.lookup(question("c.test"), id = 1, now = 0L))
        assertNull(cache.lookup(question("b.test"), id = 1, now = 0L))
    }

    @Test
    fun `clear drops everything`() {
        val cache = DnsCache()
        cache.store(question("example.com"), entry(storedAt = 0L, ttl = 300))
        cache.clear()

        assertNull(cache.lookup(question("example.com"), id = 1, now = 0L))
        assertEquals(0, cache.size())
    }
}
