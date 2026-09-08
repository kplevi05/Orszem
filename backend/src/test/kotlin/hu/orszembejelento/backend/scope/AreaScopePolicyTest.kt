package hu.orszembejelento.backend.scope

import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.scope.domain.AreaActor
import hu.orszembejelento.backend.scope.domain.AreaScopePolicy
import hu.orszembejelento.backend.scope.domain.AreaSnapshot
import java.util.UUID
import org.junit.jupiter.api.Test

/**
 * The area authorisation rules, as pure logic.
 *
 * Two rules carry most of the weight and are easy to get wrong in opposite directions:
 * global area access must not quietly become permission to triage UNCLASSIFIED, and an
 * inactive area must be closed even to SUPER_ADMIN.
 */
class AreaScopePolicyTest {

    private val policy = AreaScopePolicy()

    private val alpha = AreaSnapshot(UUID.randomUUID(), active = true)
    private val beta = AreaSnapshot(UUID.randomUUID(), active = true)
    private val retired = AreaSnapshot(UUID.randomUUID(), active = false)

    private fun actor(
        role: UserRole,
        global: Boolean = false,
        assigned: Set<UUID> = emptySet(),
    ) = AreaActor(UUID.randomUUID(), role, global, assigned)

    // ------------------------------------------------------------ SUPER_ADMIN

    @Test
    fun `super admin reaches every active area and can triage unclassified`() {
        val admin = actor(UserRole.SUPER_ADMIN)

        check(policy.hasGlobalAreaAccess(admin))
        check(policy.canAccessArea(admin, alpha))
        check(policy.canAccessArea(admin, beta))
        check(policy.canViewUnclassified(admin))
    }

    @Test
    fun `the super admin role wins over an inconsistent global flag`() {
        // A SUPER_ADMIN row with global_area_access = false is bad data, not a demotion.
        val admin = actor(UserRole.SUPER_ADMIN, global = false)

        check(policy.hasGlobalAreaAccess(admin)) { "the role is authoritative" }
        check(policy.canAccessArea(admin, alpha))
        check(policy.canViewUnclassified(admin))
    }

    // -------------------------------------------------------------- MODERATOR

    @Test
    fun `a territorial moderator is limited to assigned areas and cannot triage unclassified`() {
        val moderator = actor(UserRole.MODERATOR, assigned = setOf(alpha.id))

        check(policy.canAccessArea(moderator, alpha))
        check(!policy.canAccessArea(moderator, beta)) { "an unassigned area must stay closed" }
        check(!policy.canViewUnclassified(moderator)) {
            "triage is a national responsibility, not a territorial one"
        }
    }

    @Test
    fun `a global moderator reaches every active area and can triage unclassified`() {
        val moderator = actor(UserRole.MODERATOR, global = true)

        check(policy.canAccessArea(moderator, alpha))
        check(policy.canAccessArea(moderator, beta))
        check(policy.canViewUnclassified(moderator))
    }

    // ----------------------------------------------------------- SERVICE_USER

    @Test
    fun `a service user is limited to assigned areas`() {
        val user = actor(UserRole.SERVICE_USER, assigned = setOf(beta.id))

        check(policy.canAccessArea(user, beta))
        check(!policy.canAccessArea(user, alpha))
        check(!policy.canViewUnclassified(user))
    }

    @Test
    fun `global area access does not give a service user unclassified triage`() {
        // The rule most likely to be "simplified" away later. Breadth of ordinary work is
        // not the same responsibility as deciding where unroutable traffic belongs.
        val user = actor(UserRole.SERVICE_USER, global = true)

        check(policy.hasGlobalAreaAccess(user)) { "they may work in every area" }
        check(policy.canAccessArea(user, alpha))
        check(policy.canAccessArea(user, beta))
        check(!policy.canViewUnclassified(user)) {
            "a global service user still must not triage UNCLASSIFIED"
        }
    }

    @Test
    fun `an unassigned service user reaches nothing`() {
        val user = actor(UserRole.SERVICE_USER)

        check(!policy.canAccessArea(user, alpha))
        check(!policy.canAccessArea(user, beta))
        check(policy.accessibleAreas(user, listOf(alpha, beta)).isEmpty())
    }

    // ------------------------------------------------------------ inactive areas

    @Test
    fun `an inactive area is closed to everyone including super admin`() {
        listOf(
            actor(UserRole.SUPER_ADMIN),
            actor(UserRole.MODERATOR, global = true),
            actor(UserRole.SERVICE_USER, global = true),
            actor(UserRole.SERVICE_USER, assigned = setOf(retired.id)),
        ).forEach { candidate ->
            check(!policy.canAccessArea(candidate, retired)) {
                "a deactivated area must be closed to ${candidate.role}"
            }
        }
    }

    @Test
    fun `accessible areas filters rather than assuming`() {
        val territorial = actor(UserRole.MODERATOR, assigned = setOf(alpha.id, retired.id))

        val accessible = policy.accessibleAreas(territorial, listOf(alpha, beta, retired))

        check(accessible.map { it.id } == listOf(alpha.id)) {
            "only the assigned and active area should be reachable, got $accessible"
        }
    }

    @Test
    fun `assignment to an area does not leak into another`() {
        val user = actor(UserRole.SERVICE_USER, assigned = setOf(alpha.id))
        check(policy.canAccessArea(user, alpha))
        check(!policy.canAccessArea(user, AreaSnapshot(UUID.randomUUID(), active = true))) {
            "an unrelated area must never be reachable"
        }
    }
}
