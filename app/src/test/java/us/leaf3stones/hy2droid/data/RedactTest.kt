package us.leaf3stones.hy2droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactTest {

    @Test
    fun `masks auth and obfs password but keeps indentation`() {
        val yaml = """
            server: "example.com:443"
            auth: "s3cret"
            obfs:
              type: salamander
              salamander:
                password: "obfspass"
            socks5:
              listen: 127.0.0.1:1080
        """.trimIndent()

        val redacted = Redact.config(yaml)

        assertTrue(redacted.contains("auth: \"***\""))
        assertTrue(redacted.contains("    password: \"***\""))
        assertFalse(redacted.contains("s3cret"))
        assertFalse(redacted.contains("obfspass"))
        // Everything else survives, so the file is still readable as a config.
        assertTrue(redacted.contains("server: \"example.com:443\""))
        assertTrue(redacted.contains("type: salamander"))
        assertTrue(redacted.contains("listen: 127.0.0.1:1080"))
    }

    @Test
    fun `drops a share link line entirely`() {
        val redacted = Redact.config("hysteria2://auth@example.com:443/?obfs-password=x")

        assertFalse(redacted.contains("obfs-password=x"))
        assertTrue(redacted.contains("redacted"))
    }

    @Test
    fun `log line keeps ordinary output and blocks the credential banner`() {
        assertEquals(
            "2026-11-11 INFO connected to example.com:443",
            Redact.logLine("2026-11-11 INFO connected to example.com:443"),
        )
        assertEquals(
            "[redacted: line contains credentials]",
            Redact.logLine("hysteria2://auth@example.com:443/#N1"),
        )
        assertEquals(
            "[redacted: line contains credentials]",
            Redact.logLine("obfs-password: hunter2"),
        )
    }
}
