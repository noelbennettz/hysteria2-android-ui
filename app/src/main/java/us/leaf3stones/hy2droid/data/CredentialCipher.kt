package us.leaf3stones.hy2droid.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals the per-profile secrets (`auth`, obfs password) before they reach disk.
 *
 * The key lives in the Android Keystore, so the ciphertext in the profiles
 * DataStore is worthless to anyone who reads the file: a backup pulled off the
 * device (or a restored cloud backup on another phone) cannot be decrypted,
 * because the key never leaves this device's Keystore.
 *
 * Envelope: `enc:v1:<base64 iv>:<base64 ciphertext+tag>`. Anything without the
 * prefix is treated as legacy plaintext and returned unchanged, which is what
 * lets [ProfileStore] migrate old installs in place.
 *
 * A lost key (factory reset, restored backup, "clear app data") is not fatal:
 * [decrypt] returns `null` and the profile list still loads with an empty
 * secret, so the user only has to paste the password again.
 */
class CredentialCipher(private val keyProvider: () -> SecretKey) {

    private val key: SecretKey by lazy(keyProvider)

    /** True when [value] is one of our sealed envelopes. */
    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    /** Seal [plain]; an empty secret stays empty so blanks never grow a header. */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = Base64.getEncoder().encodeToString(cipher.iv)
        val body = Base64.getEncoder().encodeToString(
            cipher.doFinal(plain.toByteArray(Charsets.UTF_8)),
        )
        return "$PREFIX$iv:$body"
    }

    /**
     * Open a stored value: ciphertext is decrypted, legacy plaintext passes
     * through, and `null` means "sealed with a key we no longer have".
     */
    fun decrypt(stored: String): String? {
        if (stored.isEmpty()) return ""
        if (!isEncrypted(stored)) return stored
        return try {
            val payload = stored.removePrefix(PREFIX)
            val separator = payload.indexOf(':')
            if (separator <= 0) return null
            val iv = Base64.getDecoder().decode(payload.substring(0, separator))
            val body = Base64.getDecoder().decode(payload.substring(separator + 1))
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (_: Exception) {
            // Wrong key, truncated payload or tampered bytes: treat as missing.
            null
        }
    }

    companion object {
        /** Marks a value as ciphertext; legacy plaintext has no prefix. */
        const val PREFIX = "enc:v1:"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "hy2droid.credentials"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128

        /** The production cipher: one AES-256-GCM key kept in the Android Keystore. */
        fun withAndroidKeystore(): CredentialCipher = CredentialCipher(::loadOrCreateKey)

        private fun loadOrCreateKey(): SecretKey {
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
                return it.secretKey
            }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}
