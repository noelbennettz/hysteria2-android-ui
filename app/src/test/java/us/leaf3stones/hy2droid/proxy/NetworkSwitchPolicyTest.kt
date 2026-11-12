package us.leaf3stones.hy2droid.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The handover policy: when is a restart of the Hysteria core warranted?
 *
 * The cases that matter are the ones Android actually produces — several
 * callbacks per handover, capability updates that change nothing, and a network
 * that disappears before its replacement is usable.
 */
class NetworkSwitchPolicyTest {

    @Test
    fun `the first usable network is adopted without restarting the core`() {
        val policy = NetworkSwitchPolicy()

        assertEquals(NetworkAction.Adopt, policy.onCurrent("2:wlan0"))
        assertEquals("2:wlan0", policy.adopted)
    }

    @Test
    fun `the same transport reported again is ignored`() {
        val policy = NetworkSwitchPolicy()
        policy.onCurrent("2:wlan0")

        assertEquals(NetworkAction.Ignore, policy.onCurrent("2:wlan0"))
    }

    @Test
    fun `capability updates keep the token and are ignored`() {
        val policy = NetworkSwitchPolicy()
        policy.onCurrent("2:wlan0")

        // Signal strength, bandwidth, validated toggles: same transport, same
        // interface, so the core is still on a live path.
        repeat(5) { assertEquals(NetworkAction.Ignore, policy.onCurrent("2:wlan0")) }
    }

    @Test
    fun `a different transport restarts the core`() {
        val policy = NetworkSwitchPolicy()
        policy.onCurrent("2:wlan0")

        assertEquals(NetworkAction.Restart, policy.onCurrent("1:rmnet_data0"))
        assertEquals("1:rmnet_data0", policy.adopted)
    }

    @Test
    fun `a lost network does not restart and keeps the last token`() {
        val policy = NetworkSwitchPolicy()
        policy.onCurrent("2:wlan0")

        assertEquals(NetworkAction.Ignore, policy.onCurrent(null))
        // The core is still bound to Wi-Fi; the token itself did not change.
        assertEquals("2:wlan0", policy.adopted)
    }

    @Test
    fun `a change inside the rate limit is adopted now and restarted later`() {
        var now = 0L
        val policy = NetworkSwitchPolicy(clock = { now }, restartIntervalMs = 10_000)

        assertEquals(NetworkAction.Adopt, policy.onCurrent("2:wlan0"))
        now = 1_000
        assertEquals(NetworkAction.Restart, policy.onCurrent("1:rmnet_data0"))
        // Immediately after, a second handover may not restart again...
        now = 2_000
        assertEquals(NetworkAction.Adopt, policy.onCurrent("2:wlan0"))
        assertEquals("2:wlan0", policy.adopted)
        assertEquals(9_000L, policy.pendingDelayMs())
        // ...but it is owed, and runs as soon as the interval has passed.
        assertFalse(policy.takeDueRestart())
        // The interval is measured from the last restart, 1 s after the start.
        now = 11_000
        assertTrue(policy.takeDueRestart())
        assertFalse(policy.takeDueRestart())
        assertNull(policy.pendingDelayMs())
    }

    @Test
    fun `a flapping link produces one restart per interval, and settles on the last one`() {
        var now = 0L
        val policy = NetworkSwitchPolicy(clock = { now }, restartIntervalMs = 10_000)
        policy.onCurrent("2:wlan0")
        now = 500
        assertEquals(NetworkAction.Restart, policy.onCurrent("1:rmnet_data0"))

        var restarts = 1
        // The link keeps flapping once a second for a while.
        for (step in 1..6) {
            now = 500L + step * 1_000
            val action = policy.onCurrent(if (step % 2 == 0) "1:rmnet_data0" else "2:wlan0")
            if (action == NetworkAction.Restart) restarts++
            // Whatever the limiter says, the pin follows the network that is up.
            assertEquals(if (step % 2 == 0) "1:rmnet_data0" else "2:wlan0", policy.adopted)
        }
        assertEquals(1, restarts)
        // The owed restart runs once the interval has passed, and only once.
        now = 20_000
        assertTrue(policy.takeDueRestart())
        restarts++
        assertFalse(policy.takeDueRestart())
        assertEquals(2, restarts)
    }

    @Test
    fun `a settled network cancels the restart it owed`() {
        var now = 0L
        val policy = NetworkSwitchPolicy(clock = { now }, restartIntervalMs = 10_000)
        policy.onCurrent("2:wlan0")
        now = 1_000
        assertEquals(NetworkAction.Restart, policy.onCurrent("1:rmnet_data0"))
        now = 2_000
        assertEquals(NetworkAction.Adopt, policy.onCurrent("2:wlan0"))
        assertNotNull(policy.pendingDelayMs())
        // Back on the network the core was started with: nothing is owed.
        now = 3_000
        assertEquals(NetworkAction.Ignore, policy.onCurrent("2:wlan0"))
        assertNull(policy.pendingDelayMs())
        assertFalse(policy.takeDueRestart())
    }

    @Test
    fun `a network that comes back on the same transport restarts the core`() {
        val policy = NetworkSwitchPolicy()
        policy.onCurrent("2:wlan0")
        policy.onCurrent(null)

        // Airplane mode off, or a Wi-Fi re-association: the QUIC session did not
        // survive the gap even though the token is identical, so waiting for the
        // idle timeout would leave a 30 s hole.
        assertEquals(NetworkAction.Restart, policy.onCurrent("2:wlan0"))
        assertEquals(NetworkAction.Ignore, policy.onCurrent("2:wlan0"))
    }

    @Test
    fun `going away and coming back on another transport restarts once per change`() {
        var now = 0L
        val policy = NetworkSwitchPolicy(clock = { now }, restartIntervalMs = 10_000)
        policy.onCurrent("2:wlan0")

        now = 1_000
        assertEquals(NetworkAction.Ignore, policy.onCurrent(null))
        now = 2_000
        assertEquals(NetworkAction.Restart, policy.onCurrent("1:rmnet_data0"))
        now = 3_000
        assertEquals(NetworkAction.Ignore, policy.onCurrent("1:rmnet_data0"))

        // A second gap is a second dead path, so it restarts again on the way back
        // once the rate limit has passed.
        now = 20_000
        assertEquals(NetworkAction.Ignore, policy.onCurrent(null))
        now = 21_000
        assertEquals(NetworkAction.Restart, policy.onCurrent("2:wlan0"))
        now = 22_000
        assertEquals(NetworkAction.Ignore, policy.onCurrent("2:wlan0"))
    }

    @Test
    fun `a handover burst settles on the last network before acting`() {
        val policy = NetworkSwitchPolicy()
        policy.onCurrent("2:wlan0")

        // The consumer debounces the burst; the policy only sees the settled
        // value, so a second sample of the same value must be inert. Without
        // this, two restarts land back to back on one handover.
        assertEquals(NetworkAction.Restart, policy.onCurrent("1:rmnet_data0"))
        assertEquals(NetworkAction.Ignore, policy.onCurrent("1:rmnet_data0"))
    }

    @Test
    fun `reset starts from scratch`() {
        val policy = NetworkSwitchPolicy()
        policy.onCurrent("2:wlan0")
        policy.reset()

        assertNull(policy.adopted)
        assertEquals(NetworkAction.Adopt, policy.onCurrent("1:rmnet_data0"))
    }

    @Test
    fun `the transport mask is a bitmask of the capability types`() {
        // NetworkCapabilities.TRANSPORT_CELLULAR == 0, TRANSPORT_WIFI == 1.
        assertEquals(1, NetworkSwitchPolicy.transportMask(listOf(0)))
        assertEquals(2, NetworkSwitchPolicy.transportMask(listOf(1)))
        assertEquals(3, NetworkSwitchPolicy.transportMask(listOf(0, 1)))
        // Nonsense values must not crash (or wrap the shift).
        assertEquals(2, NetworkSwitchPolicy.transportMask(listOf(1, 99, -1)))
    }

    @Test
    fun `wifi and cellular never share a token`() {
        val wifi = NetworkSwitchPolicy.transportToken(2, "wlan0")
        val cell = NetworkSwitchPolicy.transportToken(1, "rmnet_data0")

        assertNotEquals(wifi, cell)
        assertEquals("2:wlan0", wifi)
        assertEquals("1:rmnet_data0", cell)
    }

    @Test
    fun `the interface name separates two networks of one transport`() {
        // A Wi-Fi hotspot link and the station link are both TRANSPORT_WIFI.
        assertNotEquals(
            NetworkSwitchPolicy.transportToken(2, "wlan0"),
            NetworkSwitchPolicy.transportToken(2, "p2p0"),
        )
    }

    @Test
    fun `a network without an interface name still gets a usable token`() {
        assertEquals("2:unknown", NetworkSwitchPolicy.transportToken(2, null))
    }

    @Test
    fun `the snapshot ignores callback order and duplicates`() {
        val a = NetworkSwitchPolicy.networkToken(listOf("1:rmnet_data0", "2:wlan0"))
        val b = NetworkSwitchPolicy.networkToken(listOf("2:wlan0", "1:rmnet_data0", "2:wlan0"))

        assertEquals(a, b)
        assertEquals("1:rmnet_data0+2:wlan0", a)
    }

    @Test
    fun `no usable network is an empty snapshot`() {
        assertNull(NetworkSwitchPolicy.networkToken(emptyList()))
        assertNull(NetworkSwitchPolicy.networkToken(listOf("", "  ")))
    }
}
