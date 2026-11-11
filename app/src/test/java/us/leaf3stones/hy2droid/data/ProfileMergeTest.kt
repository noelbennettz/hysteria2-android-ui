package us.leaf3stones.hy2droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMergeTest {

    private fun profile(
        id: String,
        server: String = "example.com",
        port: Int = 443,
        auth: String = "secret",
        name: String = "Node",
        sni: String = "",
        obfs: String = "",
    ) = Profile(
        id = id,
        name = name,
        server = server,
        auth = auth,
        port = port,
        sni = sni,
        obfsPassword = obfs,
    )

    @Test
    fun `same node ignores host case but not port`() {
        assertTrue(ProfileMerge.sameNode(profile("a"), profile("b", server = "EXAMPLE.com")))
        assertFalse(ProfileMerge.sameNode(profile("a"), profile("b", port = 8443)))
        assertFalse(ProfileMerge.sameNode(profile("a"), profile("b", server = "other.example.com")))
    }

    @Test
    fun `re-importing a node keeps its id and fills in the secret`() {
        val stored = profile("a", auth = "")
        val incoming = profile("b", auth = "fresh-pass", name = "Renamed")

        val merged = ProfileMerge.merge(stored, incoming)

        assertEquals("a", merged.id)
        assertEquals("fresh-pass", merged.auth)
        assertEquals("Renamed", merged.name)
    }

    @Test
    fun `a link without a secret never erases the stored one`() {
        val stored = profile("a", auth = "stored", sni = "stored.sni", obfs = "obfspass")
        val incoming = profile("b", auth = "", sni = "", obfs = "")

        val merged = ProfileMerge.merge(stored, incoming)

        assertEquals("stored", merged.auth)
        assertEquals("stored.sni", merged.sni)
        assertEquals("obfspass", merged.obfsPassword)
    }

    @Test
    fun `resolve updates the matching node instead of appending a duplicate`() {
        val existing = listOf(profile("a"), profile("b", server = "second.example"))

        val resolved = ProfileMerge.resolve(existing, profile("new", server = "SECOND.example"))
        val updated = existing.map { if (it.id == resolved.id) resolved else it }

        assertEquals("b", resolved.id)
        assertEquals(listOf("a", "b"), updated.map { it.id })
    }

    @Test
    fun `resolve keeps a brand new node as is`() {
        val incoming = profile("new", server = "third.example")

        assertEquals(incoming, ProfileMerge.resolve(listOf(profile("a")), incoming))
    }
}
