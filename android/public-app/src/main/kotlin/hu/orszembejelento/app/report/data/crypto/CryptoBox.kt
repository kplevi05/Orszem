package hu.orszembejelento.app.report.data.crypto

/**
 * Authenticated encryption, abstracted away from Android - so the encrypted-blob format
 * and failure handling can be unit-tested on a plain JVM (a fake, in-memory [CryptoBox]),
 * while the real cryptography ([KeystoreCryptoBox]) is exercised only by an instrumented
 * test against the real Android Keystore. See `AbstractPostgresIntegrationTest`'s backend
 * equivalent reasoning: this interface exists for testability, never to weaken the real
 * implementation.
 */
interface CryptoBox {
    /** Returns a self-contained blob: nonce plus ciphertext plus authentication tag. */
    fun encrypt(plaintext: ByteArray): ByteArray

    /** Returns null if the blob is corrupt, truncated, or not decryptable with this key. */
    fun decrypt(blob: ByteArray): ByteArray?
}
