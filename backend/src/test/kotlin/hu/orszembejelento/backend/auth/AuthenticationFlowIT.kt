package hu.orszembejelento.backend.auth

import hu.orszembejelento.backend.identity.domain.ServiceId
import hu.orszembejelento.backend.identity.domain.UserStatus
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.time.Duration
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

@Import(AbstractAuthIntegrationTest.Containers::class)
class AuthenticationFlowIT : AbstractAuthIntegrationTest() {

    // ------------------------------------------------------------------- login

    @Test
    fun `valid credentials return a session`() {
        val user = givenUser()
        val response = login(user.serviceId, STRONG_PASSWORD)

        check(response.statusCode() == 200) { "expected 200, got ${response.statusCode()}" }

        val body = json(response)
        check(body.get("tokenType").asText() == "Bearer")
        check(body.get("accessToken").asText().startsWith("at_"))
        check(body.get("refreshToken").asText().startsWith("rt_"))
        check(body.has("accessTokenExpiresAt") && body.has("sessionExpiresAt"))

        // Credentials must never be cached by an intermediary or the client.
        check(response.headers().firstValue("Cache-Control").orElse("") == "no-store") {
            "token responses must be sent with Cache-Control: no-store"
        }
    }

    @Test
    fun `unknown service id, wrong password and deactivated account are indistinguishable`() {
        val active = givenUser()
        val deactivated = givenUser(status = UserStatus.DEACTIVATED)

        val unknown = login(ServiceId.ofTrusted("SZ-999999"), STRONG_PASSWORD)
        val wrongPassword = login(active.serviceId, "totally different passphrase")
        val disabled = login(deactivated.serviceId, STRONG_PASSWORD)

        listOf(unknown, wrongPassword, disabled).forEach { response ->
            check(response.statusCode() == 401) { "expected 401, got ${response.statusCode()}" }
            check(errorCode(response) == "INVALID_CREDENTIALS") {
                "all three failures must share one code, got ${errorCode(response)}"
            }
        }

        // The bodies must be identical apart from the per-request correlation id, or the
        // difference itself would disclose which case occurred.
        val bodies = listOf(unknown, wrongPassword, disabled).map {
            json(it).apply { (this as tools.jackson.databind.node.ObjectNode).remove("correlationId") }
        }
        check(bodies.toSet().size == 1) { "responses differ and would leak account state: $bodies" }
    }

    @Test
    fun `malformed service id is rejected without disclosing anything`() {
        val response = login(ServiceId.ofTrusted("SZ-000001"), "x")
        check(response.statusCode() == 401)
        check(errorCode(response) == "INVALID_CREDENTIALS")
    }

    @Test
    fun `no session is created when a login fails`() {
        val user = givenUser()
        login(user.serviceId, "the wrong passphrase entirely")
        check(activeSessionCount(user.id) == 0) { "a failed login must not create a session" }
    }

    @Test
    fun `multiple simultaneous sessions are allowed`() {
        val user = givenUser()
        val first = loginSuccessfully(user.serviceId)
        val second = loginSuccessfully(user.serviceId)

        check(first.accessToken != second.accessToken)
        check(activeSessionCount(user.id) == 2)
        // Both remain usable: signing in on a second device must not evict the first.
        check(get("/api/v1/service/account/me", first.accessToken).statusCode() == 200)
        check(get("/api/v1/service/account/me", second.accessToken).statusCode() == 200)
    }

    // ------------------------------------------------- forced initial password change

    @Test
    fun `login with a temporary credential issues no session`() {
        val provisioned = createSuperAdmin.create()

        val response = post(
            "/api/v1/service/auth/login",
            """{"serviceId":"${provisioned.serviceId.value}","password":"${provisioned.temporaryCredential}"}""",
        )

        check(response.statusCode() == 403) { "expected 403, got ${response.statusCode()}" }
        check(errorCode(response) == "PASSWORD_CHANGE_REQUIRED")

        val userId = users.findByServiceId(provisioned.serviceId)!!.id
        check(activeSessionCount(userId) == 0) { "no session may exist before the password change" }
    }

    @Test
    fun `the temporary credential survives a login attempt`() {
        val provisioned = createSuperAdmin.create()

        // Merely attempting to log in must not consume the credential: if the app dies
        // between the two calls, the user must still be able to start over.
        repeat(3) {
            val response = post(
                "/api/v1/service/auth/login",
                """{"serviceId":"${provisioned.serviceId.value}","password":"${provisioned.temporaryCredential}"}""",
            )
            check(errorCode(response) == "PASSWORD_CHANGE_REQUIRED")
        }

        val completed = completePasswordChange(provisioned.serviceId, provisioned.temporaryCredential)
        check(completed.statusCode() == 200) { "the temporary credential must still work" }
    }

    @Test
    fun `completing the change clears the flag and returns a working session`() {
        val provisioned = createSuperAdmin.create()

        val response = completePasswordChange(provisioned.serviceId, provisioned.temporaryCredential)
        check(response.statusCode() == 200) { "expected 200, got ${response.body()}" }

        val credentials = credentialsFrom(response)
        val user = users.findByServiceId(provisioned.serviceId)!!
        check(!user.mustChangePassword) { "the flag must be cleared" }
        check(user.passwordChangedAt != null)

        val me = get("/api/v1/service/account/me", credentials.accessToken)
        check(me.statusCode() == 200) { "the returned session must be usable" }
        check(json(me).get("role").asText() == "SUPER_ADMIN")
    }

    @Test
    fun `the temporary credential stops working after the change`() {
        val provisioned = createSuperAdmin.create()
        completePasswordChange(provisioned.serviceId, provisioned.temporaryCredential)

        val retry = completePasswordChange(provisioned.serviceId, provisioned.temporaryCredential)
        check(retry.statusCode() == 401) { "a spent temporary credential must not work again" }

        val oldLogin = post(
            "/api/v1/service/auth/login",
            """{"serviceId":"${provisioned.serviceId.value}","password":"${provisioned.temporaryCredential}"}""",
        )
        check(oldLogin.statusCode() == 401)
    }

    @Test
    fun `the temporary credential is accepted without its display hyphens`() {
        val provisioned = createSuperAdmin.create()
        val typedWithoutHyphens = provisioned.temporaryCredential.replace("-", "").lowercase()

        val response = completePasswordChange(provisioned.serviceId, typedWithoutHyphens)
        check(response.statusCode() == 200) { "hyphens and case are formatting, not content" }
    }

    @Test
    fun `the new password must satisfy the policy`() {
        val provisioned = createSuperAdmin.create()

        val tooShort = completePasswordChange(provisioned.serviceId, provisioned.temporaryCredential, "short")
        check(tooShort.statusCode() == 400)
        check(errorCode(tooShort) == "PASSWORD_POLICY_VIOLATION")

        // The account must be untouched by the rejected attempt.
        check(users.findByServiceId(provisioned.serviceId)!!.mustChangePassword)
    }

    // ------------------------------------------------------------------- /me

    @Test
    fun `me returns the service id, role and the caller's own area scope - nothing more`() {
        val user = givenUser()
        val credentials = loginSuccessfully(user.serviceId)

        val response = get("/api/v1/service/account/me", credentials.accessToken)
        check(response.statusCode() == 200)

        val body = json(response)
        check(body.get("serviceId").asText() == user.serviceId.value)
        check(body.get("role").asText() == "SERVICE_USER")
        check(!body.get("globalAreaAccess").asBoolean())
        check(body.get("areas").isArray && body.get("areas").isEmpty)

        @Suppress("UNCHECKED_CAST")
        val fields = (objectMapper.readValue(response.body(), Map::class.java) as Map<String, Any>).keys
        check(fields == setOf("serviceId", "role", "globalAreaAccess", "areas")) {
            "/me must expose nothing beyond the self-account contract, got $fields"
        }
    }

    @Test
    fun `role comes from the database, not the token`() {
        val user = givenUser()
        val credentials = loginSuccessfully(user.serviceId)

        jdbc.sql("UPDATE users SET role = 'MODERATOR' WHERE id = :id").param("id", user.id).update()

        // Same token, new role: proof that nothing is cached in the credential itself.
        val response = get("/api/v1/service/account/me", credentials.accessToken)
        check(json(response).get("role").asText() == "MODERATOR") {
            "the role must be read from current state on every request"
        }
    }

    // -------------------------------------------------------------- session state

    @Test
    fun `a deactivated user loses access immediately`() {
        val user = givenUser()
        val credentials = loginSuccessfully(user.serviceId)
        check(get("/api/v1/service/account/me", credentials.accessToken).statusCode() == 200)

        jdbc.sql("UPDATE users SET status = 'DEACTIVATED' WHERE id = :id").param("id", user.id).update()

        val response = get("/api/v1/service/account/me", credentials.accessToken)
        check(response.statusCode() == 401) { "deactivation must take effect at once, not at token expiry" }
    }

    @Test
    fun `an expired access token is rejected while the session lives on`() {
        val user = givenUser()
        val credentials = loginSuccessfully(user.serviceId)

        mutableClock.advance(Duration.ofMinutes(16))

        val meResponse = get("/api/v1/service/account/me", credentials.accessToken)
        check(meResponse.statusCode() == 401) { "expired access token should be 401, got ${meResponse.statusCode()}" }
        // The session itself is still valid, so refreshing recovers without a new login.
        val refreshed = refresh(credentials.refreshToken)
        check(refreshed.statusCode() == 200) { "refresh should still work, got ${refreshed.statusCode()} ${refreshed.body()}" }
    }

    @Test
    fun `an expired session cannot be refreshed`() {
        val user = givenUser()
        val credentials = loginSuccessfully(user.serviceId)

        mutableClock.advance(Duration.ofDays(31))

        check(refresh(credentials.refreshToken).statusCode() == 401) {
            "the absolute session lifetime must not be extendable"
        }
    }

    @Test
    fun `an account owing a password change cannot use a protected endpoint`() {
        val user = givenUser()
        val credentials = loginSuccessfully(user.serviceId)

        // Simulates an administrative reset while a session is live.
        jdbc.sql("UPDATE users SET must_change_password = TRUE WHERE id = :id").param("id", user.id).update()

        val response = get("/api/v1/service/account/me", credentials.accessToken)
        check(response.statusCode() == 401) { "an account owing a password change is not operational" }
    }

    // ----------------------------------------------------------------- logout

    @Test
    fun `logout revokes the current session immediately`() {
        val user = givenUser()
        val credentials = loginSuccessfully(user.serviceId)

        val response = post("/api/v1/service/auth/logout", "", credentials.accessToken)
        check(response.statusCode() == 204) { "expected 204, got ${response.statusCode()}" }

        check(get("/api/v1/service/account/me", credentials.accessToken).statusCode() == 401)
        check(refresh(credentials.refreshToken).statusCode() == 401) {
            "the refresh token of a revoked session must not work either"
        }
    }

    @Test
    fun `logout-all revokes every session of the user`() {
        val user = givenUser()
        val first = loginSuccessfully(user.serviceId)
        val second = loginSuccessfully(user.serviceId)
        val third = loginSuccessfully(user.serviceId)

        val response = post("/api/v1/service/auth/logout-all", "", second.accessToken)
        check(response.statusCode() == 204)

        listOf(first, second, third).forEach { credentials ->
            check(get("/api/v1/service/account/me", credentials.accessToken).statusCode() == 401)
            check(refresh(credentials.refreshToken).statusCode() == 401)
        }
        check(activeSessionCount(user.id) == 0)
    }

    // -------------------------------------------------------- own password change

    @Test
    fun `changing the password replaces every session with exactly one`() {
        val user = givenUser()
        val first = loginSuccessfully(user.serviceId)
        val second = loginSuccessfully(user.serviceId)

        val response = post(
            "/api/v1/service/account/change-password",
            """{"currentPassword":${objectMapper.writeValueAsString(STRONG_PASSWORD)},""" +
                """"newPassword":${objectMapper.writeValueAsString(ANOTHER_STRONG_PASSWORD)}}""",
            first.accessToken,
        )
        check(response.statusCode() == 200) { "expected 200, got ${response.body()}" }

        val fresh = credentialsFrom(response)

        // Exactly one session survives: the one just handed to this client.
        check(activeSessionCount(user.id) == 1) { "expected exactly one live session" }
        check(get("/api/v1/service/account/me", fresh.accessToken).statusCode() == 200)

        // Both previous sessions, on every device, are gone.
        check(get("/api/v1/service/account/me", first.accessToken).statusCode() == 401)
        check(get("/api/v1/service/account/me", second.accessToken).statusCode() == 401)

        // The old password no longer authenticates; the new one does.
        check(login(user.serviceId, STRONG_PASSWORD).statusCode() == 401)
        check(login(user.serviceId, ANOTHER_STRONG_PASSWORD).statusCode() == 200)
    }

    @Test
    fun `changing the password requires the correct current password`() {
        val user = givenUser()
        val credentials = loginSuccessfully(user.serviceId)

        val response = post(
            "/api/v1/service/account/change-password",
            """{"currentPassword":"not the right one at all","newPassword":${
                objectMapper.writeValueAsString(ANOTHER_STRONG_PASSWORD)
            }}""",
            credentials.accessToken,
        )
        check(response.statusCode() == 401)
        check(errorCode(response) == "INVALID_CREDENTIALS")
        // The rejected attempt must not have revoked anything.
        check(activeSessionCount(user.id) == 1)
    }

    @Test
    fun `the new password cannot equal the current one`() {
        val user = givenUser()
        val credentials = loginSuccessfully(user.serviceId)

        val response = post(
            "/api/v1/service/account/change-password",
            """{"currentPassword":${objectMapper.writeValueAsString(STRONG_PASSWORD)},""" +
                """"newPassword":${objectMapper.writeValueAsString(STRONG_PASSWORD)}}""",
            credentials.accessToken,
        )
        check(response.statusCode() == 400)
        check(errorCode(response) == "PASSWORD_POLICY_VIOLATION")
    }

    private fun completePasswordChange(
        serviceId: ServiceId,
        temporary: String,
        newPassword: String = ANOTHER_STRONG_PASSWORD,
    ) = post(
        "/api/v1/service/auth/complete-password-change",
        """{"serviceId":"${serviceId.value}","temporaryPassword":${
            objectMapper.writeValueAsString(temporary)
        },"newPassword":${objectMapper.writeValueAsString(newPassword)}}""",
    )
}
