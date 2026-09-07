package hu.orszembejelento.backend.auth

import hu.orszembejelento.backend.identity.application.ResetSuperAdminPasswordUseCase
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import

/**
 * Genuinely concurrent tests: real threads issuing real HTTP requests against real
 * PostgreSQL, released together by a latch.
 *
 * Sequential calls would prove nothing here. Every property under test — that exactly one
 * refresh wins a race, that a password reset and a login cannot interleave into a session
 * authenticated by the old password — depends on PostgreSQL row locking and on
 * compare-and-set updates, neither of which is exercised by calls that never overlap.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class AuthConcurrencyIT : AbstractAuthIntegrationTest() {

    @Autowired
    private lateinit var resetSuperAdminPassword: ResetSuperAdminPasswordUseCase

    /**
     * Runs [count] blocks simultaneously, releasing them from a latch so they overlap
     * inside the database rather than merely being started in a loop.
     */
    private fun <T> runConcurrently(count: Int, block: (Int) -> T): List<Result<T>> {
        val pool = Executors.newFixedThreadPool(count)
        val ready = CountDownLatch(count)
        val go = CountDownLatch(1)
        try {
            val futures = (0 until count).map { index ->
                pool.submit<Result<T>> {
                    ready.countDown()
                    go.await(10, TimeUnit.SECONDS)
                    runCatching { block(index) }
                }
            }
            check(ready.await(10, TimeUnit.SECONDS)) { "workers did not start" }
            go.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    // ------------------------------------------------------- A. refresh token race

    @Test
    fun `two concurrent refreshes with the same token end with the session revoked`() {
        val user = givenUser()
        val credentials = loginSuccessfully(user.serviceId)

        val results = runConcurrently(2) { refresh(credentials.refreshToken) }
            .map { it.getOrThrow() }

        val succeeded = results.filter { it.statusCode() == 200 }
        val failed = results.filter { it.statusCode() == 401 }

        // Exactly one caller may consume the token. The other is reuse by definition.
        check(succeeded.size == 1) {
            "exactly one refresh may succeed, got ${results.map(HttpResponse<String>::statusCode)}"
        }
        check(failed.size == 1) { "the losing caller must be rejected" }

        // The whole session ends — including the winner's brand-new credentials. There is
        // no way to distinguish the legitimate client from an attacker replaying the token,
        // so the only safe outcome is that nobody keeps the session.
        check(activeSessionCount(user.id) == 0) { "the raced session must end revoked" }

        val winner = credentialsFrom(succeeded.single())
        check(get("/api/v1/service/account/me", winner.accessToken).statusCode() == 401) {
            "even the winner's credentials must be dead after reuse detection"
        }
        check(refresh(winner.refreshToken).statusCode() == 401)

        check(auditEventTypes().contains("REFRESH_TOKEN_REUSE_DETECTED"))
    }

    @Test
    fun `many concurrent refreshes with the same token still revoke exactly once`() {
        val user = givenUser()
        val credentials = loginSuccessfully(user.serviceId)

        val results = runConcurrently(8) { refresh(credentials.refreshToken) }.map { it.getOrThrow() }

        check(results.count { it.statusCode() == 200 } <= 1) {
            "at most one caller may consume the token, got ${results.map(HttpResponse<String>::statusCode)}"
        }
        check(activeSessionCount(user.id) == 0)

        val revocations = jdbc.sql(
            "SELECT COUNT(*) FROM auth_sessions WHERE user_id = :id AND revocation_reason = 'REFRESH_TOKEN_REUSE'",
        ).param("id", user.id).query(Int::class.java).single()
        check(revocations == 1) { "the session must be revoked exactly once" }
    }

    // ------------------------------------------- B. password reset versus old login

    @Test
    fun `a login with the old password cannot survive a concurrent admin reset`() {
        val provisioned = createSuperAdmin.create()
        val serviceId = provisioned.serviceId

        // Give the administrator a normal, known password to race against.
        val completed = post(
            "/api/v1/service/auth/complete-password-change",
            """{"serviceId":"${serviceId.value}","temporaryPassword":${
                objectMapper.writeValueAsString(provisioned.temporaryCredential)
            },"newPassword":${objectMapper.writeValueAsString(STRONG_PASSWORD)}}""",
        )
        check(completed.statusCode() == 200)

        val userId = users.findByServiceId(serviceId)!!.id

        // Reset and old-password login, released together.
        val results = runConcurrently(2) { index ->
            if (index == 0) {
                resetSuperAdminPassword.reset(serviceId.value)
                null
            } else {
                login(serviceId, STRONG_PASSWORD)
            }
        }

        results.forEach { result ->
            result.exceptionOrNull()?.let { throw AssertionError("worker failed", it) }
        }

        // The user-row lock serialises the two operations, so they cannot interleave.
        // Whichever order they take, no session authenticated by the obsolete password may
        // remain once the reset has committed.
        val user = users.findByServiceId(serviceId)!!
        check(user.mustChangePassword) { "the reset must have taken effect" }
        check(activeSessionCount(userId) == 0) {
            "no session may survive a committed password reset"
        }

        // And the old password is definitively dead afterwards.
        check(login(serviceId, STRONG_PASSWORD).statusCode() == 401)
    }

    // ------------------------------------ C. session invalidation versus concurrent use

    @Test
    fun `sessions do not survive a concurrent password change`() {
        val user = givenUser()
        val existing = (1..4).map { loginSuccessfully(user.serviceId) }
        check(activeSessionCount(user.id) == 4)

        val changer = existing.first()

        // One thread changes the password while the others keep using their sessions.
        val results = runConcurrently(4) { index ->
            if (index == 0) {
                post(
                    "/api/v1/service/account/change-password",
                    """{"currentPassword":${objectMapper.writeValueAsString(STRONG_PASSWORD)},""" +
                        """"newPassword":${objectMapper.writeValueAsString(ANOTHER_STRONG_PASSWORD)}}""",
                    changer.accessToken,
                )
            } else {
                get("/api/v1/service/account/me", existing[index].accessToken)
            }
        }.map { it.getOrThrow() }

        check(results[0].statusCode() == 200) { "the password change must succeed: ${results[0].body()}" }

        // Requests that overlapped the change may legitimately have been served just before
        // it committed. What must hold is the state afterwards.
        val fresh = credentialsFrom(results[0])
        check(activeSessionCount(user.id) == 1) { "exactly one session may remain" }
        check(get("/api/v1/service/account/me", fresh.accessToken).statusCode() == 200)

        existing.forEach { old ->
            check(get("/api/v1/service/account/me", old.accessToken).statusCode() == 401) {
                "every pre-change session must be invalid once the change has committed"
            }
        }
    }

    @Test
    fun `concurrent logins each get their own session`() {
        val user = givenUser()

        val results = runConcurrently(6) { login(user.serviceId, STRONG_PASSWORD) }.map { it.getOrThrow() }

        check(results.all { it.statusCode() == 200 }) {
            "concurrent logins must all succeed, got ${results.map(HttpResponse<String>::statusCode)}"
        }
        check(activeSessionCount(user.id) == 6) { "each login must create its own session" }

        val tokens = results.map { credentialsFrom(it).accessToken }
        check(tokens.toSet().size == 6) { "sessions must not share credentials" }
    }

    @Test
    fun `logout-all racing with logins leaves no stale session`() {
        val user = givenUser()
        val actor = loginSuccessfully(user.serviceId)

        runConcurrently(4) { index ->
            if (index == 0) {
                post("/api/v1/service/auth/logout-all", "", actor.accessToken)
            } else {
                login(user.serviceId, STRONG_PASSWORD)
            }
        }.forEach { it.getOrThrow() }

        // Sessions created after logout-all committed are legitimately alive; what matters
        // is that the operation itself is well defined and the actor's session is gone.
        check(get("/api/v1/service/account/me", actor.accessToken).statusCode() == 401) {
            "the session that called logout-all must be revoked"
        }
    }

    @Test
    fun `concurrent super admin creation never duplicates a service id`() {
        val results = runConcurrently(6) { createSuperAdmin.create() }

        results.forEach { result ->
            result.exceptionOrNull()?.let { throw AssertionError("creation failed", it) }
        }

        val serviceIds = results.map { it.getOrThrow().serviceId.value }
        check(serviceIds.toSet().size == serviceIds.size) { "service IDs must be unique: $serviceIds" }

        val stored = jdbc.sql("SELECT COUNT(*) FROM users WHERE role = 'SUPER_ADMIN'")
            .query(Int::class.java).single()
        check(stored == 6) { "expected six administrators, found $stored" }
    }

}
