package us.leaf3stones.hy2droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoreVersionTest {

    @Test
    fun `reads the version out of the CLI banner`() {
        val banner = "Version:\tv2.5.1\nBuildDate:\t2024-08-17T04:15:10Z\n"

        assertEquals("v2.5.1", CoreVersion.parse(banner))
    }

    @Test
    fun `reads a version without a build date`() {
        assertEquals("v2.6.0", CoreVersion.parse("Version: v2.6.0\n"))
    }

    @Test
    fun `returns null when the banner has no version line`() {
        assertNull(CoreVersion.parse("Aperture Internet Laboratory"))
        assertNull(CoreVersion.parse(""))
    }
}
