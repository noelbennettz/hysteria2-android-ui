package us.leaf3stones.hy2droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockRuleTest {

    @Test
    fun `encode and parse round trip`() {
        val rules = listOf(
            BlockRule(tcp = false, udp = true, portFrom = 443),
            BlockRule(tcp = true, udp = false, portFrom = 25),
            BlockRule(tcp = true, udp = true, portFrom = 8000, portTo = 8100),
        )

        val encoded = rules.map { it.encode() }
        assertEquals(listOf("udp/443", "tcp/25", "both/8000-8100"), encoded)
        assertEquals(rules, encoded.mapNotNull(BlockRule::parse))
    }

    @Test
    fun `parse rejects malformed specs`() {
        val bad = listOf(
            "", "udp", "udp/", "/443", "sctp/443", "udp/abc", "udp/0",
            "udp/65536", "udp/9000-8000", "udp/443-", "udp/-443", "udp/443-100000",
        )
        bad.forEach { assertNull("expected null for \"$it\"", BlockRule.parse(it)) }
    }

    @Test
    fun `fromInput accepts a single port and a range`() {
        assertEquals(
            BlockRule(tcp = false, udp = true, portFrom = 443),
            BlockRule.fromInput(tcp = false, udp = true, portText = "443"),
        )
        assertEquals(
            BlockRule(tcp = true, udp = true, portFrom = 8000, portTo = 8100),
            BlockRule.fromInput(tcp = true, udp = true, portText = " 8000 - 8100 "),
        )
    }

    @Test
    fun `fromInput rejects invalid input`() {
        val bad = listOf("", " ", "0", "65536", "443-", "-443", "9000-8000", "44 3", "abc")
        bad.forEach {
            assertNull("expected null for \"$it\"", BlockRule.fromInput(tcp = true, udp = true, portText = it))
        }
        assertNull(BlockRule.fromInput(tcp = false, udp = false, portText = "443"))
    }

    @Test
    fun `port specs are split per protocol and sorted`() {
        val rules = listOf(
            BlockRule(tcp = false, udp = true, portFrom = 443),
            BlockRule(tcp = true, udp = false, portFrom = 25),
            BlockRule(tcp = true, udp = true, portFrom = 8000, portTo = 8100),
        )

        assertEquals("25,8000-8100", rules.tcpPortSpec())
        assertEquals("443,8000-8100", rules.udpPortSpec())
        assertEquals("", emptyList<BlockRule>().tcpPortSpec())
        assertEquals("", emptyList<BlockRule>().udpPortSpec())
    }

    @Test
    fun `matches honours the protocol and the range`() {
        val rule = BlockRule(tcp = true, udp = false, portFrom = 8000, portTo = 8100)

        assertTrue(rule.matches(8000, udp = false))
        assertTrue(rule.matches(8100, udp = false))
        assertFalse(rule.matches(8101, udp = false))
        assertFalse(rule.matches(8000, udp = true))
        assertTrue(BlockRule.QUIC.matches(443, udp = true))
        assertFalse(BlockRule.QUIC.matches(443, udp = false))
    }

    @Test
    fun `decodeRules drops malformed entries and duplicates`() {
        val decoded = decodeRules(listOf("udp/443", "udp/443", "bogus", "tcp/25-30"))

        assertEquals(
            listOf(
                BlockRule(tcp = false, udp = true, portFrom = 443),
                BlockRule(tcp = true, udp = false, portFrom = 25, portTo = 30),
            ),
            decoded,
        )
        assertEquals(decoded.map { it.encode() }, decoded.encodeRules())
    }
}
