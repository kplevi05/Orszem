package hu.orszembejelento.app.report

import androidx.test.ext.junit.runners.AndroidJUnit4
import hu.orszembejelento.app.report.data.crypto.KeystoreCryptoBox
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the real Android Keystore round-trip (§58) - a plain JVM unit test cannot reach
 * `AndroidKeyStore` at all, only a real device or emulator can. Uses its own alias so this
 * suite never collides with the app's real `KeystoreCryptoBox.DEFAULT_ALIAS` key.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreCryptoBoxInstrumentedTest {

    private val alias = "orszem.public.report.test.v1"
    private lateinit var cryptoBox: KeystoreCryptoBox

    @Before
    fun setUp() {
        deleteTestKey()
        cryptoBox = KeystoreCryptoBox(alias)
    }

    @After
    fun tearDown() {
        deleteTestKey()
    }

    private fun deleteTestKey() {
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        }
    }

    @Test
    fun encryptThenDecryptRoundTripsExactly() {
        val plaintext = "pr_" + "A".repeat(43)
        val blob = cryptoBox.encrypt(plaintext.toByteArray(Charsets.UTF_8))
        val decrypted = cryptoBox.decrypt(blob)
        assertNotNull(decrypted)
        assertTrue(plaintext.toByteArray(Charsets.UTF_8).contentEquals(decrypted))
    }

    @Test
    fun theCiphertextItselfNeverContainsThePlaintext() {
        val plaintext = "pr_" + "B".repeat(43)
        val blob = cryptoBox.encrypt(plaintext.toByteArray(Charsets.UTF_8))
        val blobAsLatin1 = String(blob, Charsets.ISO_8859_1)
        assertTrue("the encrypted blob must not contain the plaintext credential", !blobAsLatin1.contains(plaintext))
    }

    @Test
    fun everyEncryptionUsesAFreshNonce() {
        val plaintext = "pr_" + "C".repeat(43)
        val blobs = (1..20).map { cryptoBox.encrypt(plaintext.toByteArray(Charsets.UTF_8)) }
        // Byte 0 is the nonce length, bytes 1..nonceLength are the nonce itself (see
        // KeystoreCryptoBox's blob format).
        val nonces = blobs.map { it.copyOfRange(1, 1 + it[0].toInt()).toList() }
        assertEquals(nonces.size, nonces.toSet().size)
    }

    @Test
    fun aTamperedBlobFailsClosedRatherThanDecryptingToAlteredPlaintext() {
        val plaintext = "pr_" + "D".repeat(43)
        val blob = cryptoBox.encrypt(plaintext.toByteArray(Charsets.UTF_8))
        val tampered = blob.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1] + 1).toByte()
        assertNull(cryptoBox.decrypt(tampered))
    }

    @Test
    fun aCorruptOrTruncatedBlobReturnsNullRatherThanCrashing() {
        assertNull(cryptoBox.decrypt(ByteArray(0)))
        assertNull(cryptoBox.decrypt(byteArrayOf(50))) // claims a 50-byte nonce with no data
        assertNull(cryptoBox.decrypt(byteArrayOf(1, 2))) // nonce present, no ciphertext/tag at all
    }

    @Test
    fun theKeyIsNotExtractable() {
        cryptoBox.encrypt("warm up key creation".toByteArray())
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val entry = keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry
        // A non-extractable Keystore key reports itself as "protected" (no raw key material
        // obtainable) - this is the platform's own guarantee, not something this class can
        // subvert even if it wanted to.
        assertTrue(entry.secretKey.format == null || entry.secretKey.encoded == null)
    }

    @Test
    fun aDifferentInstanceWithADifferentAliasCannotDecryptThisOnesBlob() {
        val plaintext = "pr_" + "E".repeat(43)
        val blob = cryptoBox.encrypt(plaintext.toByteArray(Charsets.UTF_8))
        val otherAlias = "orszem.public.report.test.v1.other"
        val other = KeystoreCryptoBox(otherAlias)
        try {
            assertNull(other.decrypt(blob))
        } finally {
            runCatching {
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                keyStore.deleteEntry(otherAlias)
            }
        }
    }
}
