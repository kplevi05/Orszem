package hu.orszem.auth

import hu.orszem.auth.api.ServiceLoginResponse
import hu.orszem.auth.api.ServiceUserProfileResponse
import hu.orszem.shared.error.ProblemResponse
import hu.orszem.support.AbstractDemoIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

class ServiceAuthIT @Autowired constructor(
    private val rest: TestRestTemplate,
    private val jdbc: JdbcTemplate,
) : AbstractDemoIntegrationTest() {

    private val demoUser = "demo.service"
    private val demoPassword = "OrszemDemo!2026"

    private fun login(username: String, password: String) =
        rest.postForEntity(
            "/api/v1/service/auth/login",
            mapOf("username" to username, "password" to password),
            String::class.java,
        )

    @Test
    fun `AT-002 demo credentials return a valid Bearer JWT`() {
        val response = rest.postForEntity(
            "/api/v1/service/auth/login",
            mapOf("username" to demoUser, "password" to demoPassword),
            ServiceLoginResponse::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!.accessToken).isNotBlank()
        assertThat(response.body!!.tokenType).isEqualTo("Bearer")
        // Default TTL 8h.
        assertThat(response.body!!.expiresAt).isAfter(Instant.now().plusSeconds(7 * 3600))
    }

    @Test
    fun `AT-003 wrong password returns 401 INVALID_CREDENTIALS and audits the failure`() {
        val response = rest.postForEntity(
            "/api/v1/service/auth/login",
            mapOf("username" to demoUser, "password" to "wrong"),
            ProblemResponse::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.body!!.code).isEqualTo("INVALID_CREDENTIALS")

        val failures = jdbc.queryForObject(
            "SELECT count(*) FROM audit_events WHERE action = 'SERVICE_LOGIN_FAILURE'",
        ) { rs, _ -> rs.getInt(1) }
        assertThat(failures).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `me returns the profile and the Demo v1 capability set`() {
        val token = rest.postForEntity(
            "/api/v1/service/auth/login",
            mapOf("username" to demoUser, "password" to demoPassword),
            ServiceLoginResponse::class.java,
        ).body!!.accessToken

        val headers = HttpHeaders().apply { setBearerAuth(token) }
        val me = rest.exchange(
            "/api/v1/service/me", HttpMethod.GET, HttpEntity<Void>(headers), ServiceUserProfileResponse::class.java,
        )
        assertThat(me.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(me.body!!.username).isEqualTo(demoUser)
        assertThat(me.body!!.role).isEqualTo("SERVICE_USER")
        assertThat(me.body!!.capabilities).containsExactlyInAnyOrder(
            "REPORT_READ_ACTIVE", "REPORT_ACCEPT", "REPORT_ARCHIVE", "ARCHIVE_READ", "ANALYTICS_READ",
        )
    }

    @Test
    fun `AT-041 me without a token returns 401`() {
        val response = rest.getForEntity("/api/v1/service/me", ProblemResponse::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.body!!.code).isEqualTo("UNAUTHORIZED")
    }

    @Test
    fun `AT-029 a malformed token returns 401`() {
        val headers = HttpHeaders().apply { setBearerAuth("not.a.real.token") }
        val response = rest.exchange(
            "/api/v1/service/me", HttpMethod.GET, HttpEntity<Void>(headers), ProblemResponse::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `AT-042 the demo password is stored only as a hash`() {
        val hash = jdbc.queryForObject(
            "SELECT password_hash FROM users WHERE username = ?", String::class.java, demoUser,
        )
        assertThat(hash).isNotEqualTo(demoPassword)
        assertThat(hash).startsWith("\$argon2id\$")
    }

    @Test
    fun `unknown user cannot log in`() {
        assertThat(login("nobody", "whatever").statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}
