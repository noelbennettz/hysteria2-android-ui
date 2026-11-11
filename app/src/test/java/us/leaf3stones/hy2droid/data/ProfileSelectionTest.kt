package us.leaf3stones.hy2droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileSelectionTest {

    private fun profile(id: String) = Profile(
        id = id,
        name = id,
        server = "$id.example.com",
        auth = "x",
    )

    @Test
    fun `deleting the last entry selects the one above it`() {
        val remaining = listOf(profile("a"), profile("b"))

        assertEquals("b", successorAfterRemoval(remaining, removedIndex = 2))
    }

    @Test
    fun `deleting from the middle selects the one above it`() {
        val remaining = listOf(profile("a"), profile("c"))

        assertEquals("a", successorAfterRemoval(remaining, removedIndex = 1))
    }

    @Test
    fun `deleting the top entry falls back to the new first entry`() {
        val remaining = listOf(profile("b"), profile("c"))

        assertEquals("b", successorAfterRemoval(remaining, removedIndex = 0))
    }

    @Test
    fun `deleting the only entry leaves nothing selected`() {
        assertNull(successorAfterRemoval(emptyList(), removedIndex = 0))
    }
}
