package us.leaf3stones.hy2droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HysteriaConfigBuilderTest {

    @Test
    fun `emits all optional sections when configured`() {
        val profile = Profile(
            id = "1",
            name = "Home",
            server = "example.com",
            port = 8443,
            auth = "secret",
            sni = "sni.example.com",
            obfsPassword = "obfspass",
            insecure = true,
            brutal = true,
            txMbps = 100,
            rxMbps = 50,
            fastOpen = false,
        )

        val yaml = HysteriaConfigBuilder.build(profile)

        assertTrue(yaml.contains("server: \"example.com:8443\""))
        assertTrue(yaml.contains("auth: \"secret\""))
        assertTrue(yaml.contains("type: salamander"))
        assertTrue(yaml.contains("password: \"obfspass\""))
        assertTrue(yaml.contains("sni: \"sni.example.com\""))
        assertTrue(yaml.contains("insecure: true"))
        assertTrue(yaml.contains("up: 100 mbps"))
        assertTrue(yaml.contains("down: 50 mbps"))
        assertTrue(yaml.contains("fastOpen: false"))
        assertTrue(yaml.contains("listen: 127.0.0.1:1080"))
    }

    @Test
    fun `rates without the Brutal switch are not sent to the core`() {
        // A typed-in number must not silently opt a profile into a fixed-rate
        // congestion control that ignores loss.
        val profile = Profile(
            id = "3",
            name = "n",
            server = "h:443",
            auth = "a",
            txMbps = 100,
            rxMbps = 100,
        )

        val yaml = HysteriaConfigBuilder.build(profile)

        assertFalse(yaml.contains("bandwidth:"))
        assertFalse(yaml.contains("mbps"))
    }

    @Test
    fun `the Brutal switch alone does nothing without rates`() {
        val profile = Profile(
            id = "4",
            name = "n",
            server = "h:443",
            auth = "a",
            brutal = true,
        )

        assertFalse(HysteriaConfigBuilder.build(profile).contains("bandwidth:"))
    }

    @Test
    fun `omits optional sections with default profile`() {
        val profile = Profile(id = "2", name = "n", server = "h:443", auth = "a")

        val yaml = HysteriaConfigBuilder.build(profile)

        assertFalse(yaml.contains("bandwidth:"))
        assertFalse(yaml.contains("obfs:"))
        assertFalse(yaml.contains("tls:"))
        assertTrue(yaml.contains("fastOpen: false"))
        assertTrue(yaml.contains("socks5:"))
        assertTrue(yaml.contains("http:"))
    }

    @Test
    fun `is lazy by default and can be made eager explicitly`() {
        val profile = Profile(id = "3", name = "n", server = "h:443", auth = "a")

        assertTrue(HysteriaConfigBuilder.build(profile).contains("lazy: true"))
        assertTrue(
            HysteriaConfigBuilder.build(profile, lazy = true).contains("lazy: true"),
        )
        assertTrue(
            HysteriaConfigBuilder.build(profile, lazy = false).contains("lazy: false"),
        )
    }

    @Test
    fun `path MTU discovery stays on unless it is turned off`() {
        val profile = Profile(id = "4", name = "n", server = "h:443", auth = "a")

        assertFalse(HysteriaConfigBuilder.build(profile).contains("disablePathMTUDiscovery"))
        assertTrue(
            HysteriaConfigBuilder.build(profile, disablePathMtuDiscovery = true)
                .contains("quic:\n  maxIdleTimeout: 30s\n  keepAlivePeriod: 20s\n  disablePathMTUDiscovery: true\n"),
        )
    }

    @Test
    fun `escapes special characters so values cannot break the document`() {
        val quoted = HysteriaConfigBuilder.quote("a\"b\\c:d#e")

        assertEquals("\"a\\\"b\\\\c:d#e\"", quoted)
    }
}
