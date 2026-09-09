package hu.orszembejelento.backend.identity

import hu.orszembejelento.backend.identity.domain.ServiceId
import hu.orszembejelento.backend.identity.domain.ServiceIdGenerator
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.support.AbstractAuthIntegrationTest
import hu.orszembejelento.backend.usermanagement.application.CreateUserUseCase
import hu.orszembejelento.backend.usermanagement.domain.ManagementActor
import java.security.SecureRandom
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.boot.test.context.TestConfiguration

/**
 * A deterministic, real-PostgreSQL proof that a service-ID collision - an ordinary,
 * expected event given a random 6-digit ID space, not an edge case - never leaves the
 * enclosing transaction aborted.
 *
 * [JdbcUserRepository.insertIfServiceIdFree][hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository.insertIfServiceIdFree]
 * is the single repository method both [hu.orszembejelento.backend.identity.application.CreateSuperAdminUseCase]
 * (the maintenance CLI's own creation path) and Phase 6's
 * [CreateUserUseCase] call from inside their own bounded retry loop. Rather than
 * duplicating a hand-rolled collision simulation once per caller, this class overrides the
 * single [ServiceIdGenerator] bean the whole Spring context shares with a scripted one, so
 * the exact same forced-collision scenario is proved once against each of the two real
 * production call sites - not against a reimplementation of either.
 */
@Import(AbstractAuthIntegrationTest.Containers::class, ServiceIdCollisionIT.ScriptedServiceIdConfig::class)
class ServiceIdCollisionIT : AbstractAuthIntegrationTest() {

    @Autowired
    private lateinit var scriptedRandom: ScriptedSecureRandom

    @Autowired
    private lateinit var createUser: CreateUserUseCase

    @BeforeEach
    fun resetScriptedRandom() {
        // The scripted-random bean is a Spring singleton reused across every @Test in this
        // class; without this, a later test would inherit an earlier test's exhausted (or
        // still-scripted) queue instead of genuine randomness for its own unrelated setup.
        scriptedRandom.reset()
    }

    /** A real, database-backed SUPER_ADMIN actor - `audit_events.actor_user_id` has a real FK to `users`. */
    private fun realSuperAdminActor(): ManagementActor {
        val admin = createSuperAdmin.create()
        val adminUser = users.findByServiceId(admin.serviceId)!!
        return ManagementActor(adminUser.id, UserRole.SUPER_ADMIN, globalAreaAccess = false, ownActiveAreaIds = emptySet())
    }

    // -------------------------------------------------- maintenance CLI creation path

    @Test
    fun `SUPER_ADMIN maintenance creation survives a forced first-candidate collision`() {
        val existing = givenUser()
        val existingCandidate = candidateNumberOf(existing.serviceId)
        val freshCandidate = freshNumberOtherThan(existingCandidate)

        scriptedRandom.willReturn(existingCandidate, freshCandidate)

        val userCountBefore = countUsers()

        // The real, unmodified, @Transactional production method - not a reimplementation.
        val provisioned = createSuperAdmin.create()

        check(provisioned.serviceId != existing.serviceId) { "must have moved on to the second candidate" }
        check(provisioned.serviceId.value == "SZ-%06d".format(freshCandidate))

        val created = users.findByServiceId(provisioned.serviceId)
        check(created != null) { "the resulting account must exist" }
        check(created.role == UserRole.SUPER_ADMIN)

        // Exactly one NEW row - the pre-existing fixture is untouched, and the colliding
        // first candidate never became a second row under any id.
        check(countUsers() == userCountBefore + 1) { "expected exactly one new user" }
        check(users.findByServiceId(existing.serviceId)!!.id == existing.id) { "the pre-existing account must be untouched" }

        // The transaction was never left aborted: the audit write is the very next
        // statement in the same @Transactional method, after the failed insert attempt,
        // and it plainly succeeded (or the whole call above would have thrown).
        val auditRows = jdbc.sql(
            "SELECT metadata::text FROM audit_events WHERE event_type = 'SUPER_ADMIN_CREATED' AND actor_type = 'SYSTEM'",
        ).query(String::class.java).list().filterNotNull()
        check(auditRows.any { it.contains(provisioned.serviceId.value) }) {
            "audit provenance must name the SECOND, successful candidate: $auditRows"
        }
        check(auditRows.none { it.contains(existing.serviceId.value) }) {
            "audit provenance must never name the colliding, discarded first candidate"
        }
    }

    // ---------------------------------------------------- Phase 6 user-management path

    @Test
    fun `Phase 6 user creation survives a forced first-candidate collision`() {
        // The acting SUPER_ADMIN is provisioned first, through the real generator (still
        // unscripted at this point) - only the createUser.create() call below is hijacked.
        val actor = realSuperAdminActor()

        val existing = givenUser()
        val existingCandidate = candidateNumberOf(existing.serviceId)
        val freshCandidate = freshNumberOtherThan(existingCandidate)
        scriptedRandom.willReturn(existingCandidate, freshCandidate)

        val userCountBefore = countUsers()
        val provisioned = createUser.create(actor, UserRole.SERVICE_USER, requestedAreaIds = emptyList(), requestedGlobalAreaAccess = false)

        check(provisioned.serviceId != existing.serviceId)
        check(provisioned.serviceId.value == "SZ-%06d".format(freshCandidate))
        check(countUsers() == userCountBefore + 1) { "exactly one new user from this call" }

        val created = users.findByServiceId(provisioned.serviceId)!!
        check(created.role == UserRole.SERVICE_USER)
        check(created.mustChangePassword)

        val auditRows = jdbc.sql(
            "SELECT metadata::text FROM audit_events WHERE event_type = 'USER_CREATED'",
        ).query(String::class.java).list().filterNotNull()
        check(auditRows.any { it.contains(provisioned.serviceId.value) })
        check(auditRows.none { it.contains(existing.serviceId.value) })
    }

    // ------------------------------------------------- repeated collisions, both outcomes

    @Test
    fun `creation eventually succeeds after several consecutive forced collisions`() {
        val existing = givenUser()
        val existingCandidate = candidateNumberOf(existing.serviceId)
        // Three collisions with the SAME already-taken id, then a fresh one - well inside
        // the production retry budget (10 attempts).
        val freshCandidate = freshNumberOtherThan(existingCandidate)
        scriptedRandom.willReturn(existingCandidate, existingCandidate, existingCandidate, freshCandidate)

        val userCountBefore = countUsers()
        val provisioned = createSuperAdmin.create()

        check(provisioned.serviceId.value == "SZ-%06d".format(freshCandidate))
        check(countUsers() == userCountBefore + 1)
    }

    @Test
    fun `exhausting the bounded retry budget fails cleanly with no partial state`() {
        val existing = givenUser()
        val existingCandidate = candidateNumberOf(existing.serviceId)
        // 10 consecutive collisions - matches (and deliberately does not exceed) the
        // production SERVICE_ID_ATTEMPTS budget, so every attempt is exhausted by collision.
        scriptedRandom.willReturn(*IntArray(10) { existingCandidate })

        val userCountBefore = countUsers()
        val auditCountBefore = jdbc.sql("SELECT COUNT(*) FROM audit_events").query(Int::class.java).single()

        runCatching { createSuperAdmin.create() }.onFailure { error ->
            check(error is IllegalStateException) { "expected the production bounded-retry failure, got $error" }
        }.onSuccess {
            throw AssertionError("expected the retry budget to be exhausted, but creation succeeded")
        }

        // The failed attempt's own transaction rolls back entirely - no half-created user,
        // no orphaned audit row, and the pre-existing fixture is untouched.
        check(countUsers() == userCountBefore) { "no partial state may survive an exhausted retry budget" }
        check(jdbc.sql("SELECT COUNT(*) FROM audit_events").query(Int::class.java).single() == auditCountBefore)
        check(users.findByServiceId(existing.serviceId)!!.id == existing.id)
    }

    // ---------------------------------------------------------------------------- helpers

    private fun countUsers(): Int = jdbc.sql("SELECT COUNT(*) FROM users").query(Int::class.java).single()

    private fun candidateNumberOf(serviceId: ServiceId): Int = serviceId.value.removePrefix("SZ-").toInt()

    /** A six-digit candidate guaranteed not to equal [excluding] and not to already exist. */
    private fun freshNumberOtherThan(excluding: Int): Int {
        var candidate = (excluding + 1) % 1_000_000
        while (candidate == excluding || users.findByServiceId(ServiceId.ofTrusted("SZ-%06d".format(candidate))) != null) {
            candidate = (candidate + 1) % 1_000_000
        }
        return candidate
    }

    @TestConfiguration
    class ScriptedServiceIdConfig {
        @Bean
        @Primary
        fun scriptedRandom(): ScriptedSecureRandom = ScriptedSecureRandom()

        // A distinct bean *name* from the production `serviceIdGenerator` bean in
        // IdentityConfig - Spring Boot refuses same-name bean overriding by default, and
        // @Primary alone (no name clash) is sufficient to make every by-type injection
        // site in this test's context prefer this scripted instance instead.
        @Bean
        @Primary
        fun scriptedServiceIdGenerator(scriptedRandom: ScriptedSecureRandom): ServiceIdGenerator = ServiceIdGenerator(scriptedRandom)
    }

    /**
     * A [SecureRandom] that returns an exact, pre-scripted sequence of candidate numbers
     * instead of real randomness - the only way to force a specific, reproducible
     * service-ID collision against a real unique index rather than waiting for one by luck.
     *
     * Before [willReturn] is ever called, it delegates to a real [SecureRandom] - a test
     * that needs an unrelated, ordinary account provisioned first (e.g. a real SUPER_ADMIN
     * actor to act as, via [hu.orszembejelento.backend.identity.application.CreateSuperAdminUseCase])
     * must not be forced to script that unrelated call too.
     */
    class ScriptedSecureRandom : SecureRandom() {
        private val queue = ArrayDeque<Int>()
        private val fallback = SecureRandom()
        private var scripted = false

        fun willReturn(vararg values: Int) {
            queue.clear()
            queue.addAll(values.toList())
            scripted = true
        }

        /** Back to real-randomness passthrough - the bean is a Spring singleton reused across every `@Test` in this class. */
        fun reset() {
            queue.clear()
            scripted = false
        }

        override fun nextInt(bound: Int): Int {
            if (!scripted) return fallback.nextInt(bound)
            check(queue.isNotEmpty()) { "ScriptedSecureRandom queue exhausted - the test did not script enough candidates" }
            return queue.removeFirst()
        }
    }
}
