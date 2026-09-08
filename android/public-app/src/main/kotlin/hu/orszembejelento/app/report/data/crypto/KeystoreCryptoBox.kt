package hu.orszembejelento.app.report.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM encryption backed by a non-exportable Android Keystore key, at rest for report
 * access credentials (Phase 5 brief §22).
 *
 * The key is generated inside the Keystore and never leaves it. Only the ciphertext is
 * written to disk (as a column in the local history database), in app-private storage
 * additionally excluded from cloud backup and device transfer (§24,
 * `data_extraction_rules.xml` / `backup_rules.xml`).
 *
 * GCM is authenticated encryption, so a tampered blob fails to decrypt rather than
 * yielding altered plaintext. **A fresh random nonce is generated for every encryption**
 * by the Keystore provider - never reused with the same key.
 *
 * This is deliberately a fresh implementation for the Public app, not a shared or imported
 * copy of the Service app's `KeystoreCryptoBox` (Phase 5 brief §23 - Service authentication
 * code is not refactored just to deduplicate this). The design is intentionally identical
 * (same proven AES/GCM/NoPadding pattern, same blob format), only the key alias differs.
 *
 * No biometric or lock-screen binding, and no StrongBox requirement - neither is a Phase 5
 * requirement, and demanding either would lock some users out entirely.
 */
class KeystoreCryptoBox(private val alias: String = DEFAULT_ALIAS) : CryptoBox {

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())

        // The provider generates the nonce; taking it from the cipher guarantees the value
        // actually used is the one stored, rather than one we assumed.
        val nonce = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)

        return byteArrayOf(nonce.size.toByte()) + nonce + ciphertext
    }

    override fun decrypt(blob: ByteArray): ByteArray? = runCatching {
        if (blob.isEmpty()) return null
        val nonceLength = blob[0].toInt()
        if (nonceLength <= 0 || blob.size < 1 + nonceLength + 1) return null

        val nonce = blob.copyOfRange(1, 1 + nonceLength)
        val ciphertext = blob.copyOfRange(1 + nonceLength, blob.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: return null, GCMParameterSpec(TAG_BITS, nonce))
        cipher.doFinal(ciphertext)
    }.getOrNull() // A missing/invalidated key, or a tampered blob, is simply "access lost" - see SubmissionState.ACCESS_LOST.

    /** Returns the existing key, creating one on first use. */
    private fun secretKey(): SecretKey = existingKey() ?: generateKey()

    private fun existingKey(): SecretKey? = runCatching {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }.getOrNull()

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                // Randomised encryption: the provider supplies the nonce and refuses a
                // caller-chosen one, which removes any chance of reusing it.
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_BITS = 256
        private const val TAG_BITS = 128

        // A Public-app-specific alias - distinct from the Service app's own key, which
        // this class deliberately never touches or shares (§23).
        const val DEFAULT_ALIAS = "orszem.public.report.v1"
    }
}
