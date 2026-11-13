package us.leaf3stones.hy2droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HysteriaUriTest {

    @Test
    fun `parses a full share link`() {
        val profile = HysteriaUri.parse(
            "hysteria2://mypass@example.com:8443/?sni=sni.example.com" +
                "&obfs=salamander&obfs-password=ob&insecure=1#My%20Node",
        )


        requireNotNull(profile)
        assertEquals("example.com", profile.server)
        assertEquals(8443, profile.port)
        assertEquals("mypass", profile.auth)
        assertEquals("sni.example.com", profile.sni)
        assertEquals("ob", profile.obfsPassword)
        assertTrue(profile.insecure)
        assertEquals("My Node", profile.name)
    }

    @Test
    fun `parses a minimal share link`() {
        val profile = HysteriaUri.parse("hysteria2://pass@host.example:443")

        requireNotNull(profile)
        assertEquals("host.example", profile.server)
        assertEquals(443, profile.port)
        assertEquals("pass", profile.auth)
        assertEquals("", profile.sni)
        assertEquals("", profile.obfsPassword)
        assertEquals("host.example", profile.name)
    }

    @Test
    fun `parses the hy2 alias used by other clients`() {
        val profile = HysteriaUri.parse("hy2://pass@alias.example:443/?sni=s#Alias")

        requireNotNull(profile)
        assertEquals("alias.example", profile.server)
        assertEquals("pass", profile.auth)
        assertEquals("s", profile.sni)
        assertEquals("Alias", profile.name)
    }

    @Test
    fun `rejects non-hysteria schemes and garbage`() {
        assertNull(HysteriaUri.parse("https://example.com"))
        assertNull(HysteriaUri.parse("not a url at all"))
    }

    @Test
    fun `parses bandwidth from third-party links and enables Brutal`() {
        val profile = HysteriaUri.parse(
            "hysteria2://pass@example.com:443/?sni=s&up=20&down=100#Fast",
        )

        requireNotNull(profile)
        assertTrue(profile.brutal)
        assertEquals(20, profile.txMbps)
        assertEquals(100, profile.rxMbps)
        assertTrue(profile.usesBrutal)
    }

    @Test
    fun `parses legacy upmbps and tolerates units and case`() {
        val profile = HysteriaUri.parse(
            "hysteria2://pass@example.com:443/?UP=20Mbps&Down=100%20mbps",
        )

        requireNotNull(profile)
        assertEquals(20, profile.txMbps)
        assertEquals(100, profile.rxMbps)
    }

    @Test
    fun `ignores zero or broken bandwidth values`() {
        val zero = HysteriaUri.parse("hysteria2://pass@example.com:443/?up=0&down=0")
        val broken = HysteriaUri.parse("hysteria2://pass@example.com:443/?up=fast")
        val decimal = HysteriaUri.parse("hysteria2://pass@example.com:443/?up=1.5")

        requireNotNull(zero)
        requireNotNull(broken)
        requireNotNull(decimal)
        assertEquals(0, zero.txMbps)
        assertTrue(!zero.brutal)
        assertEquals(0, broken.rxMbps)
        assertTrue(!broken.brutal)
        // 1.5 Mbps must not silently become 15.
        assertEquals(0, decimal.txMbps)
        assertTrue(!decimal.brutal)
    }

    @Test
    fun `builds a share link that round-trips`() {
        val profile = Profile(
            id = "1",
            name = "Node",
            server = "example.com",
            auth = "pass word",
            sni = "s",
            obfsPassword = "o",
            insecure = true,
        )

        val link = HysteriaUri.build(profile)

        assertTrue(link.startsWith("hysteria2://"))
        assertTrue(link.contains("@example.com:443"))
        assertTrue(link.contains("sni=s"))
        assertTrue(link.contains("insecure=1"))
        assertTrue(link.endsWith("#Node"))
    }
}
