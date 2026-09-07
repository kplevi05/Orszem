package hu.orszembejelento.service.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.orszembejelento.service.auth.data.EncryptedTokenStore
import hu.orszembejelento.service.auth.data.KeystoreCryptoBox
import hu.orszembejelento.service.auth.data.StoredSession
import java.io.File
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the **real** Android Keystore.
 *
 * These cannot be JVM tests. `AndroidKeyStore` is a device-backed provider: on a plain JVM
 * the provider does not exist, so a unit test can only ever run against a stand-in, which
 * proves nothing about the code that actually protects a refresh token on a user's phone.
 * Everything here runs on a device or emulator against the genuine provider.
 *
 * The production crypto is used unmodified — no test-only algorithm, no weakened key spec,
 * no injected fake.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreTokenStoreInstrumentedTest {

    private val alias = "orszem-instrumented-test-key"
    private lateinit var file: File
    private lateinit var crypto: KeystoreCryptoBox
    private lateinit var store: EncryptedTokenStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.filesDir, "instrumented-${System.nanoTime()}.bin")
        crypto = KeystoreCryptoBox(alias)
        store = EncryptedTokenStore(file, crypto)
        deleteKey()
    }

    @After
    fun tearDown() {
        file.delete()
        deleteKey()
    }

    private fun deleteKey() = runCatching {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    // ------------------------------------------------------------- round-trip

    @Test
    fun encryptedRefreshTokenSurvivesARoundTrip() {
        val session = StoredSession(
            refreshToken = "rt_11111111-2222-3333-4444-555555555555.a-secret-value",
            sessionExpiresAt = "2026-02-01T12:00:00Z",
        )

        store.save(session)
        val loaded = store.load()

        assertEquals(session.refreshToken, loaded?.refreshToken)
        assertEquals(session.sessionExpiresAt, loaded?.sessionExpiresAt)
    }

    @Test
    fun theTokenIsNotStoredInPlaintextOnDisk() {
        val refreshToken = "rt_11111111-2222-3333-4444-555555555555.a-secret-value"
        store.save(StoredSession(refreshToken, "2026-02-01T12:00:00Z"))

        val raw = file.readBytes()
        val asText = String(raw, Charsets.ISO_8859_1)

        assertFalse("the refresh token must not appear on disk", asText.contains(refreshToken))
        assertFalse("not even its secret part", asText.contains("a-secret-value"))
        assertFalse(asText.contains("rt_11111111"))
    }

    @Test
    fun everyEncryptionUsesAFreshNonce() {
        // Reusing a nonce with the same GCM key would leak the XOR of two plaintexts and
        // break authentication outright, so this is the property that matters most.
        val plaintext = "the same value every time".toByteArray()

        val blobs = (1..25).map { crypto.encrypt(plaintext) }
        val nonces = blobs.map { blob ->
            val nonceLength = blob[0].toInt()
            blob.copyOfRange(1, 1 + nonceLength).toList()
        }

        assertEquals("every nonce must be unique", nonces.size, nonces.toSet().size)
        assertEquals("ciphertexts must differ too", blobs.size, blobs.map { it.toList() }.toSet().size)

        // All of them still decrypt back to the same plaintext.
        blobs.forEach { assertArrayEquals(plaintext, crypto.decrypt(it)) }
    }

    @Test
    fun theKeyIsNotExportable() {
        crypto.encrypt("force key creation".toByteArray())

        val entry = KeyStore.getInstance("AndroidKeyStore")
            .apply { load(null) }
            .getEntry(alias, null) as KeyStore.SecretKeyEntry

        // A Keystore-backed key returns no encoded form: the bytes are not obtainable by
        // this app, another app, or anyone reading the filesystem.
        assertNull("the key material must not be exportable", entry.secretKey.encoded)
    }

    // ------------------------------------------------- process death and restoration

    @Test
    fun aNewStoreInstanceCanReadWhatAPreviousOneWrote() {
        // Stands in for process death: the object graph is gone, the Keystore key and the
        // encrypted file remain, and the session must come back without a fresh login.
        val session = StoredSession("rt_restored.secret", "2026-02-01T12:00:00Z")
        store.save(session)

        val afterRestart = EncryptedTokenStore(file, KeystoreCryptoBox(alias))
        val loaded = afterRestart.load()

        assertEquals(session.refreshToken, loaded?.refreshToken)
    }

    @Test
    fun clearRemovesTheStoredSession() {
        store.save(StoredSession("rt_to_be_cleared.secret", "2026-02-01T12:00:00Z"))
        assertTrue(file.exists())

        store.clear()

        assertNull("a cleared session must not come back", store.load())
        assertFalse("the file itself should be gone", file.exists())
    }

    // ---------------------------------------------------------- corruption handling

    @Test
    fun aTamperedBlobIsRejectedRatherThanTrusted() {
        store.save(StoredSession("rt_original.secret", "2026-02-01T12:00:00Z"))

        // Flip a bit in the ciphertext. GCM authenticates, so this must fail to decrypt
        // rather than yield altered plaintext.
        val raw = file.readBytes()
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0x01).toByte()
        file.writeBytes(raw)

        assertNull("a tampered blob must not decrypt", store.load())
    }

    @Test
    fun garbageOnDiskFallsBackToNoSessionInsteadOfCrashing() {
        listOf(
            ByteArray(0),
            byteArrayOf(0),
            byteArrayOf(1, 2, 3),
            ByteArray(64) { 0xFF.toByte() },
            "not encrypted at all".toByteArray(),
        ).forEach { garbage ->
            file.writeBytes(garbage)
            // The user is sent back to login; the app must never crash-loop on unusable data.
            assertNull("garbage must read as no session: ${garbage.size} bytes", store.load())
        }
    }

    @Test
    fun aLostKeystoreKeyFallsBackToLoginRatherThanCrashing() {
        store.save(StoredSession("rt_orphaned.secret", "2026-02-01T12:00:00Z"))
        assertTrue(store.load() != null)

        // Simulates the real cases that invalidate a Keystore key: the user changes their
        // lock screen, restores to a new device, or the key is otherwise dropped. The
        // ciphertext remains but can never be decrypted again.
        deleteKey()

        assertNull("an undecryptable session must read as no session", store.load())

        // And the app must recover: saving again re-creates a key and works.
        store.save(StoredSession("rt_after_recovery.secret", "2026-02-01T12:00:00Z"))
        assertEquals("rt_after_recovery.secret", store.load()?.refreshToken)
    }

    @Test
    fun aBlobEncryptedUnderADifferentKeyIsRejected() {
        val other = KeystoreCryptoBox("orszem-instrumented-other-key")
        try {
            val foreign = other.encrypt("someone else's session".toByteArray())
            assertNull("a blob from another key must not decrypt", crypto.decrypt(foreign))
            assertNotEquals(0, foreign.size)
        } finally {
            runCatching {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    .deleteEntry("orszem-instrumented-other-key")
            }
        }
    }
}
