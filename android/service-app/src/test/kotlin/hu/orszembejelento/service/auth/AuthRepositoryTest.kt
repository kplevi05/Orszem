package hu.orszembejelento.service.auth

import hu.orszembejelento.service.auth.data.AuthApi
import hu.orszembejelento.service.auth.data.AuthOutcome
import hu.orszembejelento.service.auth.data.AuthRepository
import hu.orszembejelento.service.auth.data.CryptoBox
import hu.orszembejelento.service.auth.data.EncryptedTokenStore
import hu.orszembejelento.service.auth.domain.AuthErrorKind
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Repository behaviour against a real HTTP server, so request bodies, status codes and error
 * codes are exercised the way the device will meet them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var repository: AuthRepository
    private lateinit var store: EncryptedTokenStore

    /** Not encryption; the real Keystore-backed box is exercised on device, not on the JVM. */
    private object PassthroughCrypto : CryptoBox {
        override fun encrypt(plaintext: ByteArray) = plaintext
        override fun decrypt(blob: ByteArray) = blob
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val json = Json { ignoreUnknownKeys = true }
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient.Builder().retryOnConnectionFailure(false).build())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AuthApi::class.java)

        store = EncryptedTokenStore(File(folder.root, EncryptedTokenStore.FILE_NAME), PassthroughCrypto)
        // A real dispatcher, not TestScope: the repository scope drives the single-flight
        // coroutine, and nothing in this test advances a virtual scheduler for it.
        repository = AuthRepository(api, store, CoroutineScope(Dispatchers.Default))
    }

    @After
    fun tearDown() = server.close()

    private fun tokens(access: String, refresh: String) = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(
            """{"tokenType":"Bearer","accessToken":"$access","accessTokenExpiresAt":"2026-01-01T12:15:00Z",""" +
                """"refreshToken":"$refresh","sessionExpiresAt":"2026-02-01T12:00:00Z"}""",
        )
        .build()

    private fun me(serviceId: String = "SZ-123456", role: String = "SERVICE_USER") = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body("""{"serviceId":"$serviceId","role":"$role"}""")
        .build()

    private fun error(code: Int, errorCode: String) = MockResponse.Builder()
        .code(code)
        .setHeader("Content-Type", "application/json")
        .body("""{"code":"$errorCode","message":"x","correlationId":"c"}""")
        .build()

    // ------------------------------------------------------------------- login

    @Test
    fun `successful login stores the refresh token and reports the identity`() = runTest {
        server.enqueue(tokens("at_1.secret", "rt_1.secret"))
        server.enqueue(me(role = "SUPER_ADMIN"))

        val outcome = repository.login("SZ-123456", "korte alma szilva dio")

        assertTrue(outcome is AuthOutcome.Success)
        assertEquals("SUPER_ADMIN", (outcome as AuthOutcome.Success).role)
        assertEquals("rt_1.secret", store.load()?.refreshToken)
    }

    @Test
    fun `the stored file never contains the access token`() = runTest {
        server.enqueue(tokens("at_secretaccess.value", "rt_1.secret"))
        server.enqueue(me())

        repository.login("SZ-123456", "korte alma szilva dio")

        val raw = String(File(folder.root, EncryptedTokenStore.FILE_NAME).readBytes(), Charsets.UTF_8)
        assertFalse("the access token must never be persisted", raw.contains("at_secretaccess"))
        assertTrue(raw.contains("rt_1.secret"))
    }

    @Test
    fun `a login needing a password change reports it and stores nothing`() = runTest {
        server.enqueue(error(403, "PASSWORD_CHANGE_REQUIRED"))

        val outcome = repository.login("SZ-123456", "TEMP-CRED-ENTI-ALXX")

        assertTrue(outcome is AuthOutcome.PasswordChangeRequired)
        assertEquals("SZ-123456", (outcome as AuthOutcome.PasswordChangeRequired).serviceId)
        assertNull("no session exists yet, so nothing may be stored", store.load())
    }

    @Test
    fun `invalid credentials are reported without storing anything`() = runTest {
        server.enqueue(error(401, "INVALID_CREDENTIALS"))

        val outcome = repository.login("SZ-123456", "wrong")

        assertEquals(AuthErrorKind.INVALID_CREDENTIALS, (outcome as AuthOutcome.Failure).kind)
        assertNull(store.load())
    }

    @Test
    fun `rate limiting is surfaced distinctly`() = runTest {
        server.enqueue(error(429, "RATE_LIMITED"))

        val outcome = repository.login("SZ-123456", "wrong")

        assertEquals(AuthErrorKind.RATE_LIMITED, (outcome as AuthOutcome.Failure).kind)
    }

    // ----------------------------------------------- forced initial password change

    @Test
    fun `completing the password change stores the new session`() = runTest {
        server.enqueue(tokens("at_new.secret", "rt_new.secret"))
        server.enqueue(me())

        val outcome = repository.completePasswordChange("SZ-123456", "TEMP-CRED", "korte alma szilva dio")

        assertTrue(outcome is AuthOutcome.Success)
        assertEquals("rt_new.secret", store.load()?.refreshToken)
    }

    @Test
    fun `a rejected new password stores nothing and reports the policy failure`() = runTest {
        server.enqueue(error(400, "PASSWORD_POLICY_VIOLATION"))

        val outcome = repository.completePasswordChange("SZ-123456", "TEMP-CRED", "short")

        assertEquals(AuthErrorKind.PASSWORD_POLICY, (outcome as AuthOutcome.Failure).kind)
        assertNull(store.load())
    }

    // ----------------------------------------------------------------- refresh

    @Test
    fun `session restoration rotates and re-stores the refresh token`() = runTest {
        server.enqueue(tokens("at_1.secret", "rt_1.secret"))
        server.enqueue(me())
        repository.login("SZ-123456", "korte alma szilva dio")

        server.enqueue(tokens("at_2.secret", "rt_2.secret"))
        server.enqueue(me())
        val outcome = repository.refresh()

        assertTrue(outcome is AuthOutcome.Success)
        assertEquals("the rotated token must replace the old one", "rt_2.secret", store.load()?.refreshToken)
    }

    @Test
    fun `a rejected refresh clears local auth material`() = runTest {
        server.enqueue(tokens("at_1.secret", "rt_1.secret"))
        server.enqueue(me())
        repository.login("SZ-123456", "korte alma szilva dio")

        // Revoked, expired or already consumed: nothing local can recover it.
        server.enqueue(error(401, "SESSION_INVALID"))
        val outcome = repository.refresh()

        assertEquals(AuthOutcome.SessionEnded, outcome)
        assertNull("unusable credentials must be discarded", store.load())
    }

    @Test
    fun `refresh with nothing stored reports the session as ended`() = runTest {
        assertEquals(AuthOutcome.SessionEnded, repository.refresh())
    }

    @Test
    fun `concurrent refreshes send exactly one request`() = runTest {
        server.enqueue(tokens("at_1.secret", "rt_1.secret"))
        server.enqueue(me())
        repository.login("SZ-123456", "korte alma szilva dio")
        val requestsAfterLogin = server.requestCount

        server.enqueue(tokens("at_2.secret", "rt_2.secret"))
        server.enqueue(me())

        val outcomes = withContext(Dispatchers.Default) {
            (1..10).map { async { repository.refresh() } }.awaitAll()
        }

        assertTrue("every caller must succeed", outcomes.all { it is AuthOutcome.Success })
        // One refresh plus its identity lookup: the rotating token was sent once, not ten times.
        assertEquals(2, server.requestCount - requestsAfterLogin)
        assertEquals(1, repository.refreshExecutions)
    }

    // ------------------------------------------------------------------ logout

    @Test
    fun `logout clears local auth material`() = runTest {
        server.enqueue(tokens("at_1.secret", "rt_1.secret"))
        server.enqueue(me())
        repository.login("SZ-123456", "korte alma szilva dio")

        server.enqueue(MockResponse.Builder().code(204).build())
        val outcome = repository.logout()

        assertEquals(AuthOutcome.SessionEnded, outcome)
        assertNull(store.load())
    }

    @Test
    fun `logout clears local material even when the server is unreachable`() = runTest {
        server.enqueue(tokens("at_1.secret", "rt_1.secret"))
        server.enqueue(me())
        repository.login("SZ-123456", "korte alma szilva dio")

        server.close()

        // A user on a shared device must never stay signed in because the network failed.
        assertEquals(AuthOutcome.SessionEnded, repository.logout())
        assertNull(store.load())
    }

    @Test
    fun `logout-all clears local auth material`() = runTest {
        server.enqueue(tokens("at_1.secret", "rt_1.secret"))
        server.enqueue(me())
        repository.login("SZ-123456", "korte alma szilva dio")

        server.enqueue(MockResponse.Builder().code(204).build())

        assertEquals(AuthOutcome.SessionEnded, repository.logoutAll())
        assertNull(store.load())
    }

    // --------------------------------------------------------- password change

    @Test
    fun `changing the password replaces the stored credentials`() = runTest {
        server.enqueue(tokens("at_1.secret", "rt_1.secret"))
        server.enqueue(me())
        repository.login("SZ-123456", "korte alma szilva dio")

        server.enqueue(tokens("at_3.secret", "rt_3.secret"))
        server.enqueue(me())
        val outcome = repository.changePassword("korte alma szilva dio", "hatvan het nyolcvan kilenc")

        assertTrue(outcome is AuthOutcome.Success)
        assertEquals("rt_3.secret", store.load()?.refreshToken)
    }

    @Test
    fun `an unreachable server is reported as a network failure, not a sign-out`() = runTest {
        server.close()

        val outcome = repository.login("SZ-123456", "korte alma szilva dio")

        assertEquals(AuthErrorKind.NETWORK, (outcome as AuthOutcome.Failure).kind)
    }
}
