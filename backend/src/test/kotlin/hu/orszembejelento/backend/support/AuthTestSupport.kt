package hu.orszembejelento.backend.support

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import hu.orszembejelento.backend.auth.infrastructure.LoginRateLimiter
import hu.orszembejelento.backend.identity.application.CreateSuperAdminUseCase
import hu.orszembejelento.backend.identity.domain.PasswordHasher
import hu.orszembejelento.backend.identity.domain.PasswordNormalizer
import hu.orszembejelento.backend.identity.domain.ServiceId
import hu.orszembejelento.backend.identity.domain.User
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.identity.domain.UserStatus
import hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Base for authentication integration tests.
 *
 * Runs against a real PostgreSQL through Testcontainers — never H2. Every behaviour under
 * test here depends on genuine PostgreSQL semantics: `SELECT ... FOR UPDATE` row locking,
 * `UPDATE ... RETURNING`, partial indexes, `BYTEA`, `JSONB` and regex CHECK constraints.
 * H2 emulates some of those loosely and silently differs on the rest, so a passing H2 suite
 * would prove nothing about production.
 *
 * HTTP is driven with the JDK client rather than a framework test client, so requests
 * traverse the real filter chain exactly as a device would.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractAuthIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    class Containers {
        @Bean
        @ServiceConnection
        fun postgres(): PostgreSQLContainer = PostgreSQLContainer("postgres:16-alpine").withReuse(false)

        /**
         * A clock the tests control, so token and session expiry can be exercised by moving
         * time rather than by sleeping for fifteen minutes.
         */
        @Bean
        @Primary
        fun testClock(): MutableClock = MutableClock(Instant.parse("2026-01-01T12:00:00Z"))
    }

    @Autowired protected lateinit var jdbc: JdbcClient
    @Autowired protected lateinit var users: JdbcUserRepository
    @Autowired protected lateinit var hasher: PasswordHasher
    @Autowired protected lateinit var objectMapper: ObjectMapper
    @Autowired protected lateinit var createSuperAdmin: CreateSuperAdminUseCase
    @Autowired protected lateinit var clock: Clock
    @Autowired protected lateinit var rateLimiter: LoginRateLimiter

    @Value("\${local.server.port}")
    protected var port: Int = 0

    protected val http: HttpClient = HttpClient.newHttpClient()

    protected val mutableClock: MutableClock get() = clock as MutableClock

    @BeforeEach
    fun resetDatabaseAndClock() {
        // Order matters: audit references users, refresh tokens reference sessions.
        // service_areas is included here (not just by Phase 2's own tables) so Phase 6's
        // user-management tests - the only ones that create service areas - always start
        // from a clean scope; CASCADE also clears user_service_areas, which references both.
        jdbc.sql("TRUNCATE audit_events, refresh_tokens, auth_sessions, users, service_areas CASCADE").update()
        // Every test calls from 127.0.0.1, so they share one IP bucket; without this a
        // throttling test would poison every test that runs after it.
        rateLimiter.resetAll()
        mutableClock.set(Instant.parse("2026-01-01T12:00:00Z"))
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * Creates a user with a known password.
     *
     * Test credentials are generated per test rather than committed as realistic-looking
     * constants, so nothing here could ever be a working credential in a real deployment.
     */
    protected fun givenUser(
        password: String = STRONG_PASSWORD,
        role: UserRole = UserRole.SERVICE_USER,
        status: UserStatus = UserStatus.ACTIVE,
        mustChangePassword: Boolean = false,
    ): User {
        val now = clock.instant()
        val user = User(
            id = UUID.randomUUID(),
            serviceId = uniqueServiceId(),
            role = role,
            status = status,
            passwordHash = hasher.hash(PasswordNormalizer.normalize(password)),
            mustChangePassword = mustChangePassword,
            passwordChangedAt = if (mustChangePassword) null else now,
            createdAt = now,
            updatedAt = now,
        )
        check(users.insertIfServiceIdFree(user)) { "failed to insert fixture user" }
        return user
    }

    private fun uniqueServiceId(): ServiceId {
        repeat(50) {
            val candidate = ServiceId.ofTrusted("SZ-%06d".format((0..999_999).random()))
            if (users.findByServiceId(candidate) == null) return candidate
        }
        error("could not allocate a fixture service ID")
    }

    // ---------------------------------------------------------------- HTTP helpers

    protected fun post(
        path: String,
        body: String,
        bearer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        headers.forEach { (name, value) -> builder.header(name, value) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    /**
     * A login that presents itself as coming from [clientIp].
     *
     * The test client connects over loopback, which the resolver treats as a trusted proxy,
     * so this reproduces exactly what Caddy does in production: the peer is loopback and the
     * originating client is named in `X-Forwarded-For`.
     */
    protected fun loginFromIp(
        serviceId: ServiceId,
        password: String,
        clientIp: String,
    ): HttpResponse<String> = post(
        "/api/v1/service/auth/login",
        """{"serviceId":"${serviceId.value}","password":${objectMapper.writeValueAsString(password)}}""",
        headers = mapOf("X-Forwarded-For" to clientIp),
    )

    protected fun get(path: String, bearer: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET()
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    protected fun json(response: HttpResponse<String>): JsonNode = objectMapper.readTree(response.body())

    protected fun errorCode(response: HttpResponse<String>): String = json(response).get("code").asText()

    /**
     * A JSON array node as a real Kotlin `List<JsonNode>`.
     *
     * `JsonNode` declares its own member `map` (array-transform semantics, not the Kotlin
     * stdlib's `Iterable.map`), which - being a member - wins over the stdlib extension in
     * overload resolution and silently changes what a plain `.map { ... }` call on a
     * `JsonNode` even means. Converting to a real `List` first sidesteps that entirely.
     */
    protected fun JsonNode.asList(): List<JsonNode> = iterator().asSequence().toList()

    // ------------------------------------------------------------- flow helpers

    protected fun login(serviceId: ServiceId, password: String): HttpResponse<String> = post(
        "/api/v1/service/auth/login",
        """{"serviceId":"${serviceId.value}","password":${objectMapper.writeValueAsString(password)}}""",
    )

    protected fun loginSuccessfully(serviceId: ServiceId, password: String = STRONG_PASSWORD): Credentials {
        val response = login(serviceId, password)
        check(response.statusCode() == 200) { "login failed: ${response.statusCode()} ${response.body()}" }
        return credentialsFrom(response)
    }

    protected fun refresh(refreshToken: String): HttpResponse<String> = post(
        "/api/v1/service/auth/refresh",
        """{"refreshToken":${objectMapper.writeValueAsString(refreshToken)}}""",
    )

    protected fun credentialsFrom(response: HttpResponse<String>): Credentials {
        val node = json(response)
        return Credentials(
            accessToken = node.get("accessToken").asText(),
            refreshToken = node.get("refreshToken").asText(),
        )
    }

    data class Credentials(val accessToken: String, val refreshToken: String)

    // ------------------------------------------------------------ assertion helpers

    protected fun auditEventTypes(): List<String> =
        jdbc.sql("SELECT event_type FROM audit_events ORDER BY created_at, event_type")
            .query(String::class.java).list().filterNotNull()

    protected fun activeSessionCount(userId: UUID): Int =
        jdbc.sql("SELECT COUNT(*) FROM auth_sessions WHERE user_id = :id AND revoked_at IS NULL")
            .param("id", userId).query(Int::class.java).single()

    companion object {
        /** Long enough for the policy, and not in the blocklist. Test-only. */
        const val STRONG_PASSWORD = "korte alma szilva dio"
        const val ANOTHER_STRONG_PASSWORD = "hatvan het nyolcvan kilenc"
    }
}

/** A [Clock] tests can move forward. */
class MutableClock(private var current: Instant) : Clock() {
    override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): Clock = this
    override fun instant(): Instant = current

    fun set(instant: Instant) { current = instant }
    fun advance(duration: java.time.Duration) { current = current.plus(duration) }
}
