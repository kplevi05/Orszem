package hu.orszembejelento.backend.auth

import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.time.Duration
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

@Import(AbstractAuthIntegrationTest.Containers::class)
class RefreshRotationIT : AbstractAuthIntegrationTest() {

    @Test
    fun `rotation issues new credentials and retires the old ones`() {
        val user = givenUser()
        val original = loginSuccessfully(user.serviceId)

        val response = refresh(original.refreshToken)
        check(response.statusCode() == 200) { "expected 200, got ${response.body()}" }

        val rotated = credentialsFrom(response)
        check(rotated.refreshToken != original.refreshToken) { "the refresh token must rotate" }
        check(rotated.accessToken != original.accessToken) { "the access token must rotate" }

        // The new access token works; the old one is dead the moment it is replaced.
        check(get("/api/v1/service/account/me", rotated.accessToken).statusCode() == 200)
        check(get("/api/v1/service/account/me", original.accessToken).statusCode() == 401) {
            "the superseded access token must stop working"
        }

        check(response.headers().firstValue("Cache-Control").orElse("") == "no-store")
    }

    @Test
    fun `the consumed token is recorded as replaced by its successor`() {
        val user = givenUser()
        val original = loginSuccessfully(user.serviceId)
        refresh(original.refreshToken)

        // Not ordered by created_at: the test clock is fixed, so both rows share a
        // timestamp and any ordering by it would be arbitrary. The states themselves are
        // what matters.
        val rows = jdbc.sql(
            """
            SELECT consumed_at IS NOT NULL AS consumed, replaced_by_token_id IS NOT NULL AS replaced
              FROM refresh_tokens
            """.trimIndent(),
        ).query { rs, _ -> rs.getBoolean("consumed") to rs.getBoolean("replaced") }.list()

        check(rows.size == 2) { "expected the original and its replacement, got $rows" }
        check(rows.count { it == (true to true) } == 1) {
            "exactly one token must be consumed and linked to its successor, got $rows"
        }
        check(rows.count { it == (false to false) } == 1) {
            "exactly one token must remain unconsumed, got $rows"
        }
    }

    @Test
    fun `replaying a consumed refresh token revokes the whole session`() {
        val user = givenUser()
        val original = loginSuccessfully(user.serviceId)

        val rotated = credentialsFrom(refresh(original.refreshToken))
        check(get("/api/v1/service/account/me", rotated.accessToken).statusCode() == 200)

        // The stolen-token scenario: someone presents a refresh token that has already
        // been used. There is no way to tell attacker from victim, so the session ends.
        val replay = refresh(original.refreshToken)
        check(replay.statusCode() == 401) { "a consumed refresh token must not work again" }
        check(errorCode(replay) == "SESSION_INVALID")

        // Crucially, the revocation must have survived the failed request's rollback.
        check(activeSessionCount(user.id) == 0) { "reuse must revoke the session, not merely fail" }
        check(get("/api/v1/service/account/me", rotated.accessToken).statusCode() == 401) {
            "the legitimate client's current access token must also be invalidated"
        }
        check(refresh(rotated.refreshToken).statusCode() == 401) {
            "the legitimate client's current refresh token must also be invalidated"
        }

        check(auditEventTypes().contains("REFRESH_TOKEN_REUSE_DETECTED")) {
            "reuse must be audited, got ${auditEventTypes()}"
        }
    }

    @Test
    fun `refresh cannot extend the absolute session lifetime`() {
        val user = givenUser()
        var credentials = loginSuccessfully(user.serviceId)

        val sessionExpiry = jdbc.sql("SELECT session_expires_at FROM auth_sessions WHERE user_id = :id")
            .param("id", user.id)
            .query(java.sql.Timestamp::class.java).single()!!.toInstant()

        // Refresh repeatedly across most of the session's life.
        repeat(5) {
            mutableClock.advance(Duration.ofDays(5))
            val response = refresh(credentials.refreshToken)
            check(response.statusCode() == 200) { "refresh should still work: ${response.body()}" }
            credentials = credentialsFrom(response)
        }

        val expiryNow = jdbc.sql("SELECT session_expires_at FROM auth_sessions WHERE user_id = :id")
            .param("id", user.id)
            .query(java.sql.Timestamp::class.java).single()!!.toInstant()
        check(expiryNow == sessionExpiry) { "the absolute expiry must never move" }

        // Past the absolute boundary, no amount of refreshing helps.
        mutableClock.advance(Duration.ofDays(6))
        check(refresh(credentials.refreshToken).statusCode() == 401) {
            "the session must end at its absolute expiry"
        }
    }

    @Test
    fun `an access token cannot be used as a refresh token`() {
        val user = givenUser()
        val credentials = loginSuccessfully(user.serviceId)

        val response = refresh(credentials.accessToken)
        check(response.statusCode() == 401) { "token type confusion must be rejected" }
        check(errorCode(response) == "SESSION_INVALID")

        // The rejected attempt must not have harmed the session.
        check(get("/api/v1/service/account/me", credentials.accessToken).statusCode() == 200)
    }

    @Test
    fun `a refresh token cannot be used as a bearer access token`() {
        val user = givenUser()
        val credentials = loginSuccessfully(user.serviceId)

        val response = get("/api/v1/service/account/me", credentials.refreshToken)
        check(response.statusCode() == 401) { "a refresh token must not authenticate a request" }
    }

    @Test
    fun `a refresh token with a valid id but a wrong secret is rejected`() {
        val user = givenUser()
        val credentials = loginSuccessfully(user.serviceId)

        val tokenId = credentials.refreshToken.substringAfter("rt_").substringBefore(".")
        val forged = "rt_$tokenId.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

        check(refresh(forged).statusCode() == 401) { "a forged secret must not be accepted" }
        // A wrong secret is not reuse, so the session must survive.
        check(refresh(credentials.refreshToken).statusCode() == 200) {
            "a failed guess must not revoke the legitimate session"
        }
    }

    @Test
    fun `a refresh token of a revoked session is rejected`() {
        val user = givenUser()
        val credentials = loginSuccessfully(user.serviceId)

        post("/api/v1/service/auth/logout", "", credentials.accessToken)

        check(refresh(credentials.refreshToken).statusCode() == 401)
    }

    @Test
    fun `a refresh token of a deactivated user is rejected`() {
        val user = givenUser()
        val credentials = loginSuccessfully(user.serviceId)

        jdbc.sql("UPDATE users SET status = 'DEACTIVATED' WHERE id = :id").param("id", user.id).update()

        check(refresh(credentials.refreshToken).statusCode() == 401) {
            "deactivation must stop refresh as well as access"
        }
    }
}
