package hu.orszembejelento.service.auth

import hu.orszembejelento.service.auth.data.CryptoBox
import hu.orszembejelento.service.auth.data.EncryptedTokenStore
import hu.orszembejelento.service.auth.data.StoredSession
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises the store's format, versioning and corruption handling on a plain JVM.
 *
 * The production [CryptoBox] is backed by the Android Keystore, which does not exist here.
 * That is why the crypto is behind an interface: the storage logic can be tested honestly
 * without weakening the real cryptography, which is untouched by any of this.
 *
 * The fake below is deliberately *not* encryption — it is a reversible transform that also
 * verifies integrity, so that "the blob is unreadable" can be simulated exactly.
 */
class EncryptedTokenStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class ReversibleFakeCrypto(private val key: Byte = 0x5A) : CryptoBox {
        var failDecryption = false

        override fun encrypt(plaintext: ByteArray): ByteArray =
            // A checksum byte stands in for GCM's authentication tag.
            byteArrayOf(checksum(plaintext)) +
                plaintext.map { (it.toInt() xor key.toInt()).toByte() }.toByteArray()

        private fun checksum(bytes: ByteArray): Byte =
            bytes.fold(0) { acc, b -> acc + b }.toByte()

        override fun decrypt(blob: ByteArray): ByteArray? {
            if (failDecryption || blob.size < 2) return null
            val body = blob.copyOfRange(1, blob.size)
                .map { (it.toInt() xor key.toInt()).toByte() }.toByteArray()
            return if (checksum(body) == blob[0]) body else null
        }
    }

    private fun store(crypto: CryptoBox) =
        EncryptedTokenStore(File(folder.root, EncryptedTokenStore.FILE_NAME), crypto)

    @Test
    fun `round-trips a session`() {
        val subject = store(ReversibleFakeCrypto())
        val session = StoredSession("rt_abc.secret-value", "2026-02-01T00:00:00Z")

        subject.save(session)
        val loaded = subject.load()

        assertEquals(session, loaded)
    }

    @Test
    fun `writes no plaintext token to disk`() {
        val subject = store(ReversibleFakeCrypto())
        subject.save(StoredSession("rt_supersecrettoken.value", "2026-02-01T00:00:00Z"))

        val raw = File(folder.root, EncryptedTokenStore.FILE_NAME).readBytes()
        val asText = String(raw, Charsets.ISO_8859_1)

        assertFalse("the refresh token must not be readable on disk", asText.contains("supersecrettoken"))
        assertFalse(asText.contains("refreshToken"))
    }

    @Test
    fun `returns null and clears the file when decryption fails`() {
        val crypto = ReversibleFakeCrypto()
        val subject = store(crypto)
        subject.save(StoredSession("rt_abc.secret", "2026-02-01T00:00:00Z"))

        // Simulates a Keystore key that is gone: device restore, credential reset, or the
        // app's data being cleared. The app must ask for a sign-in, not crash-loop.
        crypto.failDecryption = true

        assertNull(subject.load())
        assertFalse(
            "unusable data must be removed rather than retried forever",
            File(folder.root, EncryptedTokenStore.FILE_NAME).exists(),
        )
    }

    @Test
    fun `returns null for a truncated or tampered file`() {
        val subject = store(ReversibleFakeCrypto())
        subject.save(StoredSession("rt_abc.secret", "2026-02-01T00:00:00Z"))

        val file = File(folder.root, EncryptedTokenStore.FILE_NAME)
        file.writeBytes(file.readBytes().copyOfRange(0, 3))

        assertNull(subject.load())
    }

    @Test
    fun `rejects an unknown format version`() {
        val subject = store(ReversibleFakeCrypto())
        subject.save(StoredSession("rt_abc.secret", "2026-02-01T00:00:00Z"))

        val file = File(folder.root, EncryptedTokenStore.FILE_NAME)
        val bytes = file.readBytes()
        // A future format must be discarded, never misparsed as the current one.
        bytes[0] = 99
        file.writeBytes(bytes)

        assertNull(subject.load())
        assertFalse(file.exists())
    }

    @Test
    fun `returns null when nothing is stored`() {
        assertNull(store(ReversibleFakeCrypto()).load())
    }

    @Test
    fun `clear removes the stored session`() {
        val subject = store(ReversibleFakeCrypto())
        subject.save(StoredSession("rt_abc.secret", "2026-02-01T00:00:00Z"))
        assertTrue(File(folder.root, EncryptedTokenStore.FILE_NAME).exists())

        subject.clear()

        assertNull(subject.load())
        assertFalse(File(folder.root, EncryptedTokenStore.FILE_NAME).exists())
    }

    @Test
    fun `overwrites the previous session on save`() {
        val subject = store(ReversibleFakeCrypto())
        subject.save(StoredSession("rt_first.secret", "2026-02-01T00:00:00Z"))
        subject.save(StoredSession("rt_second.secret", "2026-03-01T00:00:00Z"))

        // Rotation replaces the stored token; an old one left behind could be replayed.
        assertEquals("rt_second.secret", subject.load()?.refreshToken)

        val raw = String(File(folder.root, EncryptedTokenStore.FILE_NAME).readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("rt_first"))
    }
}
