package hu.orszembejelento.backend.auth

import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

@Import(AbstractAuthIntegrationTest.Containers::class)
class AuditAndRateLimitIT : AbstractAuthIntegrationTest() {

    // ------------------------------------------------------------------- audit

    @Test
    fun `security-sensitive operations are audited`() {
        val user = givenUser()
        val credentials = loginSuccessfully(user.serviceId)
        post("/api/v1/service/auth/logout", "", credentials.accessToken)

        val second = loginSuccessfully(user.serviceId)
        post(
            "/api/v1/service/account/change-password",
            """{"currentPassword":${objectMapper.writeValueAsString(STRONG_PASSWORD)},""" +
                """"newPassword":${objectMapper.writeValueAsString(ANOTHER_STRONG_PASSWORD)}}""",
            second.accessToken,
        )

        val events = auditEventTypes()
        listOf("SESSION_CREATED", "SESSION_REVOKED", "PASSWORD_CHANGED").forEach { expected ->
            check(events.contains(expected)) { "expected $expected in the audit trail, got $events" }
        }
    }

    @Test
    fun `logout-all is audited with the number of sessions it ended`() {
        val user = givenUser()
        repeat(3) { loginSuccessfully(user.serviceId) }
        val actor = loginSuccessfully(user.serviceId)

        post("/api/v1/service/auth/logout-all", "", actor.accessToken)

        val metadata = jdbc.sql(
            "SELECT metadata::text FROM audit_events WHERE event_type = 'LOGOUT_ALL'",
        ).query(String::class.java).single().orEmpty()

        check(metadata.contains("revokedSessions")) { "expected a session count, got $metadata" }
        check(metadata.contains("\"4\"")) { "expected four revoked sessions, got $metadata" }
    }

    @Test
    fun `failed logins are not audited`() {
        val user = givenUser()
        repeat(5) { login(user.serviceId, "the wrong password entirely") }

        // Failed guesses belong to security logging. Auditing them would let anyone flood
        // the immutable trail simply by guessing.
        check(auditEventTypes().isEmpty()) {
            "failed logins must not create audit rows, got ${auditEventTypes()}"
        }
    }

    @Test
    fun `successful refresh rotation is not audited`() {
        val user = givenUser()
        val credentials = loginSuccessfully(user.serviceId)
        val before = auditEventTypes().size

        refresh(credentials.refreshToken)

        // Rotation happens every fifteen minutes per active session; auditing it would bury
        // the events that matter.
        check(auditEventTypes().size == before) {
            "routine rotation must not be audited, got ${auditEventTypes()}"
        }
    }

    @Test
    fun `audit metadata never contains credentials or tokens`() {
        val provisioned = createSuperAdmin.create()
        val completed = post(
            "/api/v1/service/auth/complete-password-change",
            """{"serviceId":"${provisioned.serviceId.value}","temporaryPassword":${
                objectMapper.writeValueAsString(provisioned.temporaryCredential)
            },"newPassword":${objectMapper.writeValueAsString(STRONG_PASSWORD)}}""",
        )
        val credentials = credentialsFrom(completed)

        val allMetadata = jdbc.sql("SELECT COALESCE(string_agg(metadata::text, ' '), '') FROM audit_events")
            .query(String::class.java).single().orEmpty()

        listOf(
            provisioned.temporaryCredential,
            STRONG_PASSWORD,
            credentials.accessToken,
            credentials.refreshToken,
            credentials.accessToken.substringAfter("."),
        ).forEach { secret ->
            check(!allMetadata.contains(secret)) { "a secret leaked into audit metadata" }
        }
        check(!allMetadata.contains("argon2")) { "a password hash leaked into audit metadata" }
    }

    @Test
    fun `an operation id groups the rows written by one action`() {
        val user = givenUser()
        repeat(2) { loginSuccessfully(user.serviceId) }
        val actor = loginSuccessfully(user.serviceId)

        post(
            "/api/v1/service/account/change-password",
            """{"currentPassword":${objectMapper.writeValueAsString(STRONG_PASSWORD)},""" +
                """"newPassword":${objectMapper.writeValueAsString(ANOTHER_STRONG_PASSWORD)}}""",
            actor.accessToken,
        )

        // The password change and the session it created share one operation id, so the
        // whole action can be reconstructed from the trail.
        val grouped = jdbc.sql(
            """
            SELECT COUNT(DISTINCT event_type) FROM audit_events
             WHERE operation_id = (
                 SELECT operation_id FROM audit_events WHERE event_type = 'PASSWORD_CHANGED' LIMIT 1
             )
            """.trimIndent(),
        ).query(Int::class.java).single()

        check(grouped >= 2) { "the password change and its new session should share an operation id" }
    }

    // -------------------------------------------------------------- rate limiting

    @Test
    fun `repeated failures against one service id are throttled`() {
        val user = givenUser()

        // The configured per-service-ID budget is 10 within the window.
        val statuses = (1..14).map { login(user.serviceId, "wrong password number $it").statusCode() }

        check(statuses.contains(429)) { "guessing must eventually be throttled, got $statuses" }

        val throttled = login(user.serviceId, "wrong again")
        check(throttled.statusCode() == 429)
        check(errorCode(throttled) == "RATE_LIMITED")
        check(throttled.headers().firstValue("Retry-After").isPresent) {
            "a throttled response must say when to retry"
        }
    }

    @Test
    fun `throttling does not reveal whether the account exists`() {
        val user = givenUser()
        val unknown = hu.orszembejelento.backend.identity.domain.ServiceId.ofTrusted("SZ-987654")

        repeat(14) { login(user.serviceId, "wrong $it") }
        repeat(14) { login(unknown, "wrong $it") }

        val knownResponse = login(user.serviceId, "wrong again")
        val unknownResponse = login(unknown, "wrong again")

        // An unknown account must be throttled just like a real one, otherwise the
        // difference in behaviour would itself enumerate valid service IDs.
        check(knownResponse.statusCode() == unknownResponse.statusCode()) {
            "known and unknown accounts must throttle alike: " +
                "${knownResponse.statusCode()} vs ${unknownResponse.statusCode()}"
        }
        check(errorCode(knownResponse) == errorCode(unknownResponse))
    }

    @Test
    fun `a correct password still works below the threshold`() {
        val user = givenUser()

        repeat(3) { login(user.serviceId, "wrong $it") }

        // A user who mistypes a few times must not be locked out.
        check(login(user.serviceId, STRONG_PASSWORD).statusCode() == 200)

        // And the successful login clears the service-ID budget.
        repeat(3) { login(user.serviceId, "wrong again $it") }
        check(login(user.serviceId, STRONG_PASSWORD).statusCode() == 200)
    }

    // ------------------------------------------------- per-client-IP bucketing

    @Test
    fun `distinct client IPs behind the proxy get independent budgets`() {
        // The behaviour that would be lost if the backend used the TCP peer address: behind
        // Caddy every user shares 127.0.0.1, so one guesser would throttle the whole world.
        val attacker = "203.0.113.7"
        val innocent = "198.51.100.4"

        // Exhaust the attacker's IP budget by spraying many distinct accounts, so the
        // per-service-ID limiter is not what trips.
        repeat(45) { index ->
            val target = hu.orszembejelento.backend.identity.domain.ServiceId.ofTrusted("SZ-%06d".format(index))
            loginFromIp(target, "wrong password $index", attacker)
        }

        val attackerBlocked = loginFromIp(
            hu.orszembejelento.backend.identity.domain.ServiceId.ofTrusted("SZ-500000"),
            "another guess",
            attacker,
        )
        check(attackerBlocked.statusCode() == 429) {
            "the offending IP must be throttled, got ${attackerBlocked.statusCode()}"
        }

        // The innocent user, on a different address, must be entirely unaffected.
        val victim = givenUser()
        val innocentResponse = loginFromIp(victim.serviceId, STRONG_PASSWORD, innocent)
        check(innocentResponse.statusCode() == 200) {
            "a different client IP must have its own budget, got ${innocentResponse.statusCode()} " +
                innocentResponse.body()
        }
    }

    @Test
    fun `a forged forwarded chain is attributed to the real client, not the leftmost entry`() {
        val victim = givenUser()

        // The attacker prepends a victim address hoping to burn the victim's budget. The
        // rightmost entry is the one a trusted proxy wrote, so the attempts land on the
        // attacker's own bucket.
        repeat(45) { index ->
            val target = hu.orszembejelento.backend.identity.domain.ServiceId.ofTrusted("SZ-%06d".format(index))
            post(
                "/api/v1/service/auth/login",
                """{"serviceId":"${target.value}","password":"wrong $index"}""",
                headers = mapOf("X-Forwarded-For" to "198.51.100.4, 203.0.113.99"),
            )
        }

        // The address the attacker tried to frame is still able to sign in.
        val framed = loginFromIp(victim.serviceId, STRONG_PASSWORD, "198.51.100.4")
        check(framed.statusCode() == 200) {
            "a forged leftmost entry must not throttle the address it names, got ${framed.statusCode()}"
        }
    }
}
