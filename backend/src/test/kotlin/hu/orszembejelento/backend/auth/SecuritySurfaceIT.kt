package hu.orszembejelento.backend.auth

import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.util.UUID
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Endpoint-level probing of the authenticated surface: the things an attacker would
 * actually try.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class SecuritySurfaceIT : AbstractAuthIntegrationTest() {

    private val protectedEndpoints = listOf(
        "/api/v1/service/account/me",
    )

    @Test
    fun `protected endpoints reject a request with no Authorization header`() {
        protectedEndpoints.forEach { path ->
            val response = get(path)
            check(response.statusCode() == 401) { "$path must require authentication" }
            check(errorCode(response) == "SESSION_INVALID")
        }
        check(post("/api/v1/service/auth/logout", "").statusCode() == 401)
        check(post("/api/v1/service/auth/logout-all", "").statusCode() == 401)
    }

    @Test
    fun `malformed and forged bearer tokens are all rejected identically`() {
        val user = givenUser()
        val valid = loginSuccessfully(user.serviceId)

        val sessionId = valid.accessToken.substringAfter("at_").substringBefore(".")

        listOf(
            "",
            "   ",
            "garbage",
            "at_",
            "at_.",
            "at_not-a-uuid.secret",
            // A well-formed but unknown session id.
            "at_${UUID.randomUUID()}.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            // A real session id with a forged secret.
            "at_$sessionId.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            // The right shape, wrong type.
            valid.refreshToken,
            // SQL-injection-shaped input is only ever a bound parameter.
            "at_'; DROP TABLE auth_sessions; --.x",
        ).forEach { candidate ->
            val response = get("/api/v1/service/account/me", candidate)
            check(response.statusCode() == 401) {
                "token '${candidate.take(30)}' must be rejected, got ${response.statusCode()}"
            }
            // Nothing about why it failed may leak.
            check(!response.body().contains("uuid", ignoreCase = true))
            check(!response.body().contains("session_id", ignoreCase = true))
            check(!response.body().contains("Exception"))
        }

        // The table is still there and the valid session still works.
        check(get("/api/v1/service/account/me", valid.accessToken).statusCode() == 200)
    }

    @Test
    fun `one user's session cannot act as another user`() {
        val victim = givenUser()
        val attacker = givenUser()

        val attackerCredentials = loginSuccessfully(attacker.serviceId)

        // The actor always comes from the session, never from anything the caller supplies,
        // so there is no parameter to point at someone else.
        val response = get("/api/v1/service/account/me", attackerCredentials.accessToken)
        check(json(response).get("serviceId").asText() == attacker.serviceId.value) {
            "the identity must come from the session"
        }
        check(json(response).get("serviceId").asText() != victim.serviceId.value)
    }

    @Test
    fun `a password change cannot be aimed at another account`() {
        val victim = givenUser()
        val attacker = givenUser()
        val attackerCredentials = loginSuccessfully(attacker.serviceId)

        // Extra fields naming a victim must be ignored: the target is the session's user.
        val response = post(
            "/api/v1/service/account/change-password",
            """{"serviceId":"${victim.serviceId.value}","userId":"${victim.id}",""" +
                """"currentPassword":${objectMapper.writeValueAsString(STRONG_PASSWORD)},""" +
                """"newPassword":${objectMapper.writeValueAsString(ANOTHER_STRONG_PASSWORD)}}""",
            attackerCredentials.accessToken,
        )
        check(response.statusCode() == 200) { "the attacker changes their own password" }

        // The victim is untouched: old password still works, sessions unaffected.
        check(login(victim.serviceId, STRONG_PASSWORD).statusCode() == 200) {
            "the victim's credentials must be unchanged"
        }
        check(login(attacker.serviceId, ANOTHER_STRONG_PASSWORD).statusCode() == 200)
    }

    @Test
    fun `malformed JSON is rejected without disclosing internals`() {
        listOf(
            "not json at all",
            "{",
            """{"serviceId":}""",
            """{"serviceId": ["SZ-123456"], "password": {"a":1}}""",
        ).forEach { body ->
            val response = post("/api/v1/service/auth/login", body)
            check(response.statusCode() == 400) { "expected 400 for '$body', got ${response.statusCode()}" }
            check(errorCode(response) == "VALIDATION_ERROR")
            check(!response.body().contains("Exception"))
            check(!response.body().contains("com.fasterxml") && !response.body().contains("tools.jackson"))
        }
    }

    @Test
    fun `oversized input is rejected before it reaches the password hasher`() {
        val user = givenUser()

        // Unbounded input into a memory-hard hash would be a cheap denial of service.
        val huge = "a".repeat(200_000)
        val response = post(
            "/api/v1/service/auth/login",
            """{"serviceId":"${user.serviceId.value}","password":${objectMapper.writeValueAsString(huge)}}""",
        )
        check(response.statusCode() == 400) { "oversized input must be rejected, got ${response.statusCode()}" }
        check(errorCode(response) == "VALIDATION_ERROR")

        // And the account is unaffected.
        check(login(user.serviceId, STRONG_PASSWORD).statusCode() == 200)
    }

    @Test
    fun `every response carries a correlation id that the client cannot dictate`() {
        val response = get(
            "/api/v1/service/account/me",
            "at_${UUID.randomUUID()}.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        )

        val header = response.headers().firstValue("X-Correlation-Id").orElse("")
        check(header.isNotBlank()) { "responses must carry a correlation id header" }
        check(json(response).get("correlationId").asText() == header) {
            "the body and header correlation ids must agree"
        }
        check(runCatching { UUID.fromString(header) }.isSuccess) { "must be a server-generated UUID" }
    }

    @Test
    fun `error responses are never cached`() {
        val response = login(givenUser().serviceId, "the wrong password entirely")
        check(response.headers().firstValue("Cache-Control").orElse("").contains("no-store")) {
            "auth error responses must not be cached"
        }
    }

    @Test
    fun `tokens are never accepted from a query parameter`() {
        val user = givenUser()
        val credentials = loginSuccessfully(user.serviceId)

        // Credentials in a URL end up in proxy logs and browser history, so the API must
        // not honour them there even if a client tried.
        val response = get("/api/v1/service/account/me?access_token=${credentials.accessToken}")
        check(response.statusCode() == 401) { "a token in the query string must not authenticate" }
    }

    @Test
    fun `the public endpoints really are the only unauthenticated ones`() {
        // Anything not deliberately opened must be closed by default.
        listOf(
            "/api/v1/service/account/me",
            "/api/v1/service/account/change-password",
            "/api/v1/service/auth/logout",
            "/api/v1/service/auth/logout-all",
        ).forEach { path ->
            val response = if (path.contains("account/me")) get(path) else post(path, "{}")
            check(response.statusCode() == 401) { "$path must be protected, got ${response.statusCode()}" }
        }

        // And the three genuinely public ones remain reachable without a token.
        check(get("/api/v1/meta").statusCode() == 200)
        check(post("/api/v1/service/auth/login", """{"serviceId":"SZ-000000","password":"x"}""").statusCode() == 401) {
            "login must be reachable without a token, even when it then fails"
        }
    }
}
