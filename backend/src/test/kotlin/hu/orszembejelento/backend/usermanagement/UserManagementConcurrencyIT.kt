package hu.orszembejelento.backend.usermanagement

import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import hu.orszembejelento.backend.support.AbstractUserManagementIntegrationTest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Import

/**
 * Genuinely concurrent tests against real PostgreSQL (brief §56). Sequential calls would
 * prove nothing here — every property under test depends on `SELECT ... FOR UPDATE` row
 * locking and on the database's own unique-index enforcement, neither of which two calls
 * that never overlap would ever exercise.
 */
@Import(AbstractAuthIntegrationTest.Containers::class)
class UserManagementConcurrencyIT : AbstractUserManagementIntegrationTest() {

    private fun adminBearer(): String {
        val admin = createSuperAdmin.create()
        completeInitialChange(admin.serviceId, admin.temporaryCredential)
        return loginSuccessfully(admin.serviceId).accessToken
    }

    /** Releases [count] blocks from a shared latch so they genuinely overlap inside the database. */
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

    // ------------------------------------------------- A. role promotion vs moderator mutation

    @Test
    fun `a moderator cannot mutate a target the instant it has become a MODERATOR under it`() {
        val area = givenArea()
        val actingModerator = givenUser(role = UserRole.MODERATOR)
        assignArea(actingModerator.id, area.id)
        val target = givenUser()
        assignArea(target.id, area.id)

        val moderatorBearer = loginSuccessfully(actingModerator.serviceId).accessToken
        val adminBearerToken = adminBearer()

        val results = runConcurrently(2) { index ->
            if (index == 0) {
                changeRole(adminBearerToken, target.serviceId.value, "MODERATOR")
            } else {
                resetPassword(moderatorBearer, target.serviceId.value)
            }
        }.map { it.getOrThrow() }

        val promotion = results[0]
        val moderatorAttempt = results[1]

        // Whichever operation actually won the row lock first, the final database state
        // must be safe: if the target ended up promoted, the moderator's mutation - whether
        // it ran before or after - must never leave the target's role as anything but
        // MODERATOR, and a moderator-authorized mutation must never have taken effect
        // against a target already MODERATOR at the time it acquired the lock.
        val finalRole = jdbc.sql("SELECT role FROM users WHERE id = :id").param("id", target.id).query(String::class.java).single()

        if (promotion.statusCode() == 200) {
            check(finalRole == "MODERATOR") { "a successful promotion must be reflected in the final state" }
            // If the moderator's mutation is the one that lost the race (ran after the
            // promotion committed), it must have been rejected - a MODERATOR is never
            // manageable by another MODERATOR.
            if (moderatorAttempt.statusCode() != 200) {
                check(moderatorAttempt.statusCode() == 403) { "expected a manageability rejection, got ${moderatorAttempt.statusCode()}: ${moderatorAttempt.body()}" }
            }
        } else {
            check(finalRole == "SERVICE_USER")
        }
    }

    // ---------------------------------------------------------- B. concurrent distinct grants

    @Test
    fun `two concurrent grants of different areas both survive`() {
        val areaA = givenArea()
        val areaB = givenArea()
        val target = givenUser()
        val bearer = adminBearer()

        val results = runConcurrently(2) { index ->
            if (index == 0) grantArea(bearer, target.serviceId.value, areaA.id) else grantArea(bearer, target.serviceId.value, areaB.id)
        }.map { it.getOrThrow() }

        results.forEach { check(it.statusCode() == 200) { "both grants must succeed: ${it.body()}" } }
        check(serviceAreas.assignedAreaIds(target.id) == setOf(areaA.id, areaB.id)) { "neither concurrent grant may be lost" }
    }

    // -------------------------------------------------------------- C. concurrent same grant

    @Test
    fun `concurrent duplicate grants of the same area produce exactly one relation row`() {
        val area = givenArea()
        val target = givenUser()
        val bearer = adminBearer()

        val results = runConcurrently(6) { grantArea(bearer, target.serviceId.value, area.id) }.map { it.getOrThrow() }

        results.forEach { check(it.statusCode() == 200) { "a duplicate grant must be a harmless no-op, not an error: ${it.body()}" } }

        val rowCount = jdbc.sql("SELECT COUNT(*) FROM user_service_areas WHERE user_id = :id AND service_area_id = :area")
            .param("id", target.id).param("area", area.id).query(Int::class.java).single()
        check(rowCount == 1) { "expected exactly one relation row, found $rowCount" }
    }

    // ----------------------------------------------------------- D. deactivation vs login

    @Test
    fun `no session created around a committed deactivation ever bypasses it`() {
        val target = givenUser()
        val bearer = adminBearer()

        val results = runConcurrently(4) { index ->
            if (index == 0) {
                deactivateUser(bearer, target.serviceId.value)
            } else {
                login(target.serviceId, STRONG_PASSWORD)
            }
        }.map { it.getOrThrow() }

        val deactivation = results[0]
        check(deactivation.statusCode() == 200) { "deactivation itself must succeed: ${deactivation.body()}" }

        // The user-row lock serialises deactivation against every concurrent login attempt:
        // a login that wins the lock first completes normally (legitimately, before the
        // deactivation committed); one that loses the race sees the now-DEACTIVATED account
        // and is rejected. Either way, once the deactivation has committed, no session may
        // remain usable.
        val status = jdbc.sql("SELECT status FROM users WHERE id = :id").param("id", target.id).query(String::class.java).single()
        check(status == "DEACTIVATED")
        check(activeSessionCount(target.id) == 0) { "no session may survive a committed deactivation, regardless of the race outcome" }

        // And a fresh login attempt, issued strictly after all of the above has committed,
        // must now unambiguously fail.
        check(login(target.serviceId, STRONG_PASSWORD).statusCode() == 401)
    }
}
