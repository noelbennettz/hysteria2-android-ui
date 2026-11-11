package us.leaf3stones.hy2droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class CredentialCipherTest {

    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    /** One fixed key, so encrypt and decrypt in a test see the same secret. */
    private val cipher = CredentialCipher { key }

    /** A different key: stands in for "restored backup on another device". */
    private val otherCipher = CredentialCipher {
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    @Test
    fun `seals and opens a secret`() {
        val sealed = cipher.encrypt("s3cret")

        assertTrue(sealed.startsWith(CredentialCipher.PREFIX))
        assertFalse(sealed.contains("s3cret"))
        assertEquals("s3cret", cipher.decrypt(sealed))
    }

    @Test
    fun `each encryption uses a fresh iv`() {
        assertTrue(cipher.encrypt("same") != cipher.encrypt("same"))
    }

    @Test
    fun `empty secrets stay empty`() {
        assertEquals("", cipher.encrypt(""))
        assertEquals("", cipher.decrypt(""))
    }

    @Test
    fun `legacy plaintext passes through untouched`() {
        assertFalse(cipher.isEncrypted("legacy-pass"))

        assertEquals("legacy-pass", cipher.decrypt("legacy-pass"))
    }

    @Test
    fun `a payload sealed by another key cannot be opened`() {
        val sealedElsewhere = otherCipher.encrypt("s3cret")

        assertNull(cipher.decrypt(sealedElsewhere))
    }

    @Test
    fun `truncated or garbled payloads degrade to null`() {
        val sealed = cipher.encrypt("s3cret")

        assertNull(cipher.decrypt(sealed.dropLast(6)))
        assertNull(cipher.decrypt(CredentialCipher.PREFIX + "not-base64"))
        assertNull(cipher.decrypt(CredentialCipher.PREFIX))
    }
}
