package us.leaf3stones.hy2droid.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Guards the "bypass LAN" table: every entry must parse, its prefix length must
 * be valid for the address family, and the address must be the network address
 * of its prefix (all host bits zero) — `android.net.IpPrefix` rejects anything
 * else at runtime, which would abort the VPN setup.
 */
class LanRangesTest {

    @Test
    fun everyRangeIsACanonicalNetwork() {
        for ((address, prefixLength) in LanRanges.ALL) {
            val bytes = InetAddress.getByName(address).address
            val bits = bytes.size * 8
            assertTrue(
                "prefix $prefixLength out of range for $address",
                prefixLength in 0..bits,
            )

            val fullBytes = prefixLength / 8
            val spareBits = prefixLength % 8
            for (i in fullBytes until bytes.size) {
                val allowedMask = if (i == fullBytes && spareBits != 0) {
                    (0xFF shl (8 - spareBits)) and 0xFF
                } else {
                    0
                }
                val hostBits = (bytes[i].toInt() and 0xFF) and allowedMask.inv() and 0xFF
                assertTrue(
                    "host bits must be zero in $address/$prefixLength",
                    hostBits == 0,
                )
            }
        }
    }

    @Test
    fun coversTheUsualPrivateRanges() {
        assertTrue(LanRanges.IPV4.contains("192.168.0.0" to 16))
        assertTrue(LanRanges.IPV4.contains("10.0.0.0" to 8))
        assertTrue(LanRanges.IPV4.contains("172.16.0.0" to 12))
        assertTrue(LanRanges.IPV4.contains("224.0.0.0" to 4))
        assertTrue(LanRanges.IPV6.contains("fe80::" to 10))
        assertTrue(LanRanges.IPV6.contains("fc00::" to 7))
        assertTrue(LanRanges.IPV6.contains("ff00::" to 8))
    }
}
