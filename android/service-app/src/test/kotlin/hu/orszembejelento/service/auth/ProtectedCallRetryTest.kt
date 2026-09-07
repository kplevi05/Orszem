package hu.orszembejelento.service.auth

import hu.orszembejelento.service.auth.data.AuthApi
import hu.orszembejelento.service.auth.data.AuthOutcome
import hu.orszembejelento.service.auth.data.AuthRepository
import hu.orszembejelento.service.auth.data.CryptoBox
import hu.orszembejelento.service.auth.data.EncryptedTokenStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * A protected call that meets a 401 may refresh once and retry once — and no more.
 *
 * The bound matters: refresh tokens rotate, so an unbounded retry loop would turn one stale
 * access token into a cascade of replays and get the session revoked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProtectedCallRetryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var repository: AuthRepository
    private lateinit var store: EncryptedTokenStore

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

    private fun me() = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body("""{"serviceId":"SZ-123456","role":"SERVICE_USER"}""")
        .build()

    private fun unauthorized() = MockResponse.Builder()
        .code(401)
        .setHeader("Content-Type", "application/json")
        .body("""{"code":"SESSION_INVALID","message":"x","correlationId":"c"}""")
        .build()

    private suspend fun signIn() {
        server.enqueue(tokens("at_1.secret", "rt_1.secret"))
        server.enqueue(me())
        repository.login("SZ-123456", "korte alma szilva dio")
    }

    @Test
    fun `an expired access token is refreshed once and the call retried once`() = runTest {
        signIn()
        val before = server.requestCount

        // The access token has expired but the session is still valid.
        server.enqueue(unauthorized())            // change-password, rejected
        server.enqueue(tokens("at_2.x", "rt_2.x")) // refresh succeeds
        server.enqueue(me())                       // identity after refresh
        server.enqueue(tokens("at_3.x", "rt_3.x")) // retried change-password
        server.enqueue(me())

        val outcome = repository.changePassword("korte alma szilva dio", "hatvan het nyolcvan kilenc")

        assertTrue("the retry should succeed", outcome is AuthOutcome.Success)
        // Rejected call, refresh, identity, retry, identity — one refresh and one retry.
        assertEquals(5, server.requestCount - before)
        assertEquals(1, repository.refreshExecutions)
    }

    @Test
    fun `a dead session is not retried in a loop`() = runTest {
        signIn()
        val before = server.requestCount

        server.enqueue(unauthorized()) // change-password, rejected
        server.enqueue(unauthorized()) // refresh also rejected: the session is gone

        val outcome = repository.changePassword("korte alma szilva dio", "hatvan het nyolcvan kilenc")

        assertEquals(AuthOutcome.SessionEnded, outcome)
        // Exactly two requests: it gives up rather than hammering the server.
        assertEquals(2, server.requestCount - before)
        assertNull("unusable credentials must be discarded", store.load())
    }
}
