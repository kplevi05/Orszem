package hu.orszembejelento.service.auth.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM encryption backed by a non-exportable Android Keystore key.
 *
 * The key is generated inside the Keystore and never leaves it: this class can ask for
 * encryption and decryption, but the raw key bytes are not obtainable by this app, by
 * another app, or by anyone reading the device's storage. Only the ciphertext is written to
 * disk, in app-private storage that is additionally excluded from cloud backup and device
 * transfer.
 *
 * GCM is authenticated encryption, so a tampered blob fails to decrypt rather than yielding
 * altered plaintext. A **fresh random nonce is generated for every encryption** by the
 * Keystore provider — never reused with the same key, which for GCM would be catastrophic:
 * two messages under one key and nonce leak their XOR and break the authentication.
 *
 * `EncryptedSharedPreferences` is deliberately not used: it is deprecated, and it does not
 * do anything here that this does not.
 *
 * No user authentication is required to use the key. Biometric or lock-screen binding is not
 * a Phase 2 requirement, and StrongBox is not required either — neither is available on all
 * target devices, and demanding them would lock some users out entirely.
 *
 * Deliberately no custom cryptography: this is the platform's AES-GCM with platform key
 * management, and nothing more.
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
    }.getOrNull() // A missing or invalidated key, or a tampered blob, is simply "no session".

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
        const val DEFAULT_ALIAS = "orszem.service.auth.v1"
    }
}
