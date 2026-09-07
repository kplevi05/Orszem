package hu.orszembejelento.service.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import hu.orszembejelento.service.auth.data.AuthApi
import hu.orszembejelento.service.auth.data.AuthOutcome
import hu.orszembejelento.service.auth.data.AuthRepository
import hu.orszembejelento.service.auth.data.EncryptedTokenStore
import hu.orszembejelento.service.auth.data.KeystoreCryptoBox
import java.io.File
import java.security.KeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The Phase 2 authentication flow, on a device, against the **real** Android Keystore.
 *
 * The JVM tests cover the same repository logic, but with a stand-in for the crypto — on a
 * plain JVM the `AndroidKeyStore` provider does not exist. This runs the identical flow with
 * real device-backed AES-GCM, so the interaction between the repository, the encrypted store
 * and the platform key is exercised rather than assumed.
 *
 * The server is a local mock rather than the real backend: what is under test here is the
 * client, and a self-contained test needs no network, no database and no fixture account.
 * The backend's own behaviour is covered by its PostgreSQL integration suite.
 */
@RunWith(AndroidJUnit4::class)
class AuthFlowInstrumentedTest {

    private val alias = "orszem-flow-instrumented-key"
    private lateinit var server: MockWebServer
    private lateinit var file: File
    private lateinit var store: EncryptedTokenStore
    private lateinit var repository: AuthRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        server = MockWebServer()
        server.start()
        file = File(context.filesDir, "flow-${System.nanoTime()}.bin")
        deleteKey()
        store = EncryptedTokenStore(file, KeystoreCryptoBox(alias))
        repository = newRepository()
    }

    @After
    fun tearDown() {
        server.close()
        file.delete()
        deleteKey()
    }

    private fun deleteKey() = runCatching {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    /** A repository sharing the on-disk store but nothing in memory: stands in for a restart. */
    private fun newRepository(): AuthRepository {
        val json = Json { ignoreUnknownKeys = true }
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().retryOnConnectionFailure(false).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AuthApi::class.java)
        return AuthRepository(api, EncryptedTokenStore(file, KeystoreCryptoBox(alias)), CoroutineScope(Dispatchers.Default))
    }

    private fun tokens(access: String, refresh: String) = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(
            """{"tokenType":"Bearer","accessToken":"$access","accessTokenExpiresAt":"2026-01-01T12:15:00Z",""" +
                """"refreshToken":"$refresh","sessionExpiresAt":"2026-02-01T12:00:00Z"}""",
        )
        .build()

    private fun me() = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body("""{"serviceId":"SZ-123456","role":"SUPER_ADMIN"}""")
        .build()

    private fun unauthorized() = MockResponse.Builder()
        .code(401)
        .setHeader("Content-Type", "application/json")
        .body("""{"code":"SESSION_INVALID","message":"x","correlationId":"c"}""")
        .build()

    @Test
    fun signingInEncryptsTheRefreshTokenWithTheDeviceKeystore() = runBlocking {
        server.enqueue(tokens("at_1.secret", "rt_1.verysecret"))
        server.enqueue(me())

        val outcome = repository.login("SZ-123456", "korte alma szilva dio")

        assertTrue("login should succeed", outcome is AuthOutcome.Success)
        assertEquals("rt_1.verysecret", store.load()?.refreshToken)

        // On disk it is ciphertext produced by the real Keystore key.
        val onDisk = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse("the refresh token must not be readable on disk", onDisk.contains("verysecret"))
        assertFalse("nor the access token", onDisk.contains("at_1"))
    }

    @Test
    fun theSessionIsRestoredAfterProcessDeath() = runBlocking {
        server.enqueue(tokens("at_1.secret", "rt_1.secret"))
        server.enqueue(me())
        repository.login("SZ-123456", "korte alma szilva dio")

        // A brand-new repository, as after the process is killed: nothing in memory, only
        // the encrypted file and the Keystore key survive.
        val restarted = newRepository()

        server.enqueue(tokens("at_2.secret", "rt_2.secret")) // refresh on startup
        server.enqueue(me())

        val restored = restarted.refresh()

        assertTrue("the session should come back without a new login", restored is AuthOutcome.Success)
        assertEquals("SZ-123456", (restored as AuthOutcome.Success).serviceId)
        // The rotated refresh token replaced the stored one, still encrypted.
        assertEquals("rt_2.secret", store.load()?.refreshToken)
    }

    @Test
    fun theRotatedRefreshTokenIsReEncryptedNotAppended() = runBlocking {
        server.enqueue(tokens("at_1.secret", "rt_1.secret"))
        server.enqueue(me())
        repository.login("SZ-123456", "korte alma szilva dio")
        val afterLogin = file.readBytes()

        server.enqueue(tokens("at_2.secret", "rt_2.secret"))
        server.enqueue(me())
        repository.refresh()
        val afterRefresh = file.readBytes()

        assertNotEquals(
            "the stored blob must change when the token rotates",
            afterLogin.toList(),
            afterRefresh.toList(),
        )
        assertEquals("rt_2.secret", store.load()?.refreshToken)
        assertFalse(
            "the superseded token must not linger on disk",
            String(afterRefresh, Charsets.ISO_8859_1).contains("rt_1"),
        )
    }

    @Test
    fun logoutClearsTheEncryptedSession() = runBlocking {
        server.enqueue(tokens("at_1.secret", "rt_1.secret"))
        server.enqueue(me())
        repository.login("SZ-123456", "korte alma szilva dio")
        assertTrue(file.exists())

        server.enqueue(MockResponse.Builder().code(204).build())
        repository.logout()

        assertNull("logout must clear the stored session", store.load())
        assertFalse("and remove the file", file.exists())
    }

    @Test
    fun logoutClearsLocallyEvenWhenTheServerCannotBeReached() = runBlocking {
        server.enqueue(tokens("at_1.secret", "rt_1.secret"))
        server.enqueue(me())
        repository.login("SZ-123456", "korte alma szilva dio")

        // The device is offline. A user on a shared device must still end up signed out.
        server.close()
        repository.logout()

        assertNull("local credentials must be cleared regardless", store.load())
    }

    @Test
    fun aRevokedSessionSendsTheUserBackToLogin() = runBlocking {
        server.enqueue(tokens("at_1.secret", "rt_1.secret"))
        server.enqueue(me())
        repository.login("SZ-123456", "korte alma szilva dio")

        // The server has revoked the session, for instance after refresh-token reuse.
        server.enqueue(unauthorized())
        val outcome = newRepository().refresh()

        assertEquals(AuthOutcome.SessionEnded, outcome)
        assertNull("unusable credentials must be discarded, not retried forever", store.load())
    }

    @Test
    fun aCorruptStoreSendsTheUserBackToLoginWithoutCrashing() = runBlocking {
        server.enqueue(tokens("at_1.secret", "rt_1.secret"))
        server.enqueue(me())
        repository.login("SZ-123456", "korte alma szilva dio")

        // Whatever the cause - a partial write, a restored backup, a Keystore key
        // invalidated by a lock-screen change - the app must recover, not crash-loop.
        file.writeBytes("this is not a valid encrypted blob".toByteArray())

        val restarted = newRepository()
        val outcome = restarted.refresh()

        assertEquals(AuthOutcome.SessionEnded, outcome)

        // And a fresh login still works afterwards.
        server.enqueue(tokens("at_9.secret", "rt_9.secret"))
        server.enqueue(me())
        val relogin = restarted.login("SZ-123456", "korte alma szilva dio")
        assertTrue("the user must be able to sign in again", relogin is AuthOutcome.Success)
        assertEquals("rt_9.secret", store.load()?.refreshToken)
    }
}
