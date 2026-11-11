package us.leaf3stones.hy2droid.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileOrderingTest {

    private fun profile(id: String) = Profile(
        id = id,
        name = id,
        server = "$id.example.com",
        auth = "x",
    )

    @Test
    fun `fastest node comes first and unmeasured nodes keep their order`() {
        val profiles = listOf(profile("a"), profile("b"), profile("c"), profile("d"))
        val latency = mapOf("a" to 180, "b" to 40, "c" to null)

        val ordered = ProfileOrdering.byLatency(profiles, latency)

        // b (40) < a (180); c failed and d was never tested, both after, in order.
        assertEquals(listOf("b", "a", "c", "d"), ordered.map { it.id })
    }

    @Test
    fun `no results at all leaves the stored order untouched`() {
        val profiles = listOf(profile("a"), profile("b"), profile("c"))

        val ordered = ProfileOrdering.byLatency(profiles, emptyMap())

        assertEquals(profiles, ordered)
    }

    @Test
    fun `ties keep the original order`() {
        val profiles = listOf(profile("a"), profile("b"), profile("c"))
        val latency = mapOf("a" to 90, "b" to 90, "c" to 90)

        assertEquals(listOf("a", "b", "c"), ProfileOrdering.byLatency(profiles, latency).map { it.id })
    }
}
