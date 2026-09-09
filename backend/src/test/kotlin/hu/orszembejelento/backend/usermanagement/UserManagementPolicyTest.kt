package hu.orszembejelento.backend.usermanagement

import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.identity.domain.UserStatus
import hu.orszembejelento.backend.reference.domain.ServiceAreaStatus
import hu.orszembejelento.backend.scope.domain.AreaScopePolicy
import hu.orszembejelento.backend.usermanagement.domain.AssignedArea
import hu.orszembejelento.backend.usermanagement.domain.ManagedUser
import hu.orszembejelento.backend.usermanagement.domain.ManagementActor
import hu.orszembejelento.backend.usermanagement.domain.UserManagementPolicy
import java.util.UUID
import org.junit.jupiter.api.Test

/**
 * Pure-logic coverage of the whole Phase 6 authorisation surface (brief §48). No database,
 * no Spring context - every fact the policy needs is passed in directly, exactly as
 * [hu.orszembejelento.backend.scope.AreaScopePolicyTest] already does for area access.
 */
class UserManagementPolicyTest {

    private val policy = UserManagementPolicy(AreaScopePolicy())

    private val alpha = UUID.randomUUID()
    private val beta = UUID.randomUUID()
    private val retiredAreaId = UUID.randomUUID()

    private fun actor(role: UserRole, global: Boolean = false, ownAreas: Set<UUID> = emptySet()) =
        ManagementActor(UUID.randomUUID(), role, global, ownAreas)

    private fun area(id: UUID, active: Boolean = true, name: String = "area") =
        AssignedArea(id, name, if (active) ServiceAreaStatus.ACTIVE else ServiceAreaStatus.INACTIVE)

    private fun target(
        role: UserRole = UserRole.SERVICE_USER,
        global: Boolean = false,
        areas: List<AssignedArea> = emptyList(),
        status: UserStatus = UserStatus.ACTIVE,
    ) = ManagedUser(UUID.randomUUID(), fakeServiceId(), role, status, mustChangePassword = false, globalAreaAccess = global, assignedAreas = areas)

    private fun fakeServiceId() = hu.orszembejelento.backend.identity.domain.ServiceId.ofTrusted("SZ-%06d".format((0..999_999).random()))

    // ---------------------------------------------------------------------------- SERVICE_USER

    @Test
    fun `a SERVICE_USER actor cannot list or manage anyone`() {
        val serviceUserActor = actor(UserRole.SERVICE_USER)
        check(!policy.canListUsers(serviceUserActor))
        check(!policy.canViewTarget(serviceUserActor, target()))
        check(!policy.canManageTarget(serviceUserActor, target()))
        check(!policy.canCreateRole(serviceUserActor, UserRole.SERVICE_USER))
    }

    // ------------------------------------------------------------------------------ SUPER_ADMIN

    @Test
    fun `SUPER_ADMIN can view every role including another SUPER_ADMIN`() {
        val admin = actor(UserRole.SUPER_ADMIN)
        check(policy.canViewTarget(admin, target(UserRole.SERVICE_USER)))
        check(policy.canViewTarget(admin, target(UserRole.MODERATOR)))
        check(policy.canViewTarget(admin, target(UserRole.SUPER_ADMIN)))
    }

    @Test
    fun `SUPER_ADMIN can create SERVICE_USER and MODERATOR but never SUPER_ADMIN`() {
        val admin = actor(UserRole.SUPER_ADMIN)
        check(policy.canCreateRole(admin, UserRole.SERVICE_USER))
        check(policy.canCreateRole(admin, UserRole.MODERATOR))
        check(!policy.canCreateRole(admin, UserRole.SUPER_ADMIN))
    }

    @Test
    fun `SUPER_ADMIN can manage any non-SUPER_ADMIN but never another SUPER_ADMIN`() {
        val admin = actor(UserRole.SUPER_ADMIN)
        check(policy.canManageTarget(admin, target(UserRole.SERVICE_USER, areas = listOf(area(alpha)))))
        check(policy.canManageTarget(admin, target(UserRole.MODERATOR)))
        check(!policy.canManageTarget(admin, target(UserRole.SUPER_ADMIN)))
        check(policy.canResetPassword(admin, target(UserRole.SERVICE_USER)))
        check(!policy.canResetPassword(admin, target(UserRole.SUPER_ADMIN)))
    }

    @Test
    fun `SUPER_ADMIN can change roles and grant or revoke any active area or global access`() {
        val admin = actor(UserRole.SUPER_ADMIN)
        check(policy.canChangeRole(admin))
        check(policy.canChangeGlobalAccess(admin))
        check(policy.canAssignArea(admin, area(alpha)))
        check(policy.canAssignArea(admin, area(beta)))
        check(!policy.canAssignArea(admin, area(retiredAreaId, active = false))) { "even SUPER_ADMIN cannot grant an inactive area" }
    }

    @Test
    fun `SUPER_ADMIN may leave a user with zero areas - no last-area rule applies`() {
        val admin = actor(UserRole.SUPER_ADMIN)
        check(!policy.wouldViolateLastAreaRule(admin, target(areas = listOf(area(alpha))), remainingAreaCount = 0))
    }

    // ------------------------------------------------------------------- territorial MODERATOR

    @Test
    fun `a territorial moderator can view a SERVICE_USER in overlapping scope`() {
        val moderator = actor(UserRole.MODERATOR, ownAreas = setOf(alpha))
        check(policy.canViewTarget(moderator, target(areas = listOf(area(alpha)))))
    }

    @Test
    fun `a territorial moderator can view an overlapping peer moderator read-only`() {
        val moderator = actor(UserRole.MODERATOR, ownAreas = setOf(alpha))
        val peer = target(UserRole.MODERATOR, areas = listOf(area(alpha)))
        check(policy.canViewTarget(moderator, peer))
        check(!policy.canManageTarget(moderator, peer)) { "a peer moderator is visible but never manageable" }
    }

    @Test
    fun `a territorial moderator cannot view an unrelated target`() {
        val moderator = actor(UserRole.MODERATOR, ownAreas = setOf(alpha))
        check(!policy.canViewTarget(moderator, target(areas = listOf(area(beta)))))
        check(!policy.canViewTarget(moderator, target(UserRole.MODERATOR, areas = listOf(area(beta)))))
    }

    @Test
    fun `a territorial moderator cannot manage a global SERVICE_USER even though it is visible`() {
        val moderator = actor(UserRole.MODERATOR, ownAreas = setOf(alpha))
        val globalUser = target(global = true)
        check(policy.canViewTarget(moderator, globalUser)) { "a global user's effective scope overlaps any non-empty moderator scope" }
        check(!policy.canManageTarget(moderator, globalUser))
    }

    @Test
    fun `a territorial moderator cannot manage a user with any assignment outside own scope`() {
        val moderator = actor(UserRole.MODERATOR, ownAreas = setOf(alpha))
        val partlyOutside = target(areas = listOf(area(alpha), area(beta)))
        check(!policy.canManageTarget(moderator, partlyOutside))
    }

    @Test
    fun `a territorial moderator cannot manage a user with a latent assignment to an inactive area`() {
        val moderator = actor(UserRole.MODERATOR, ownAreas = setOf(alpha))
        val withInactiveAssignment = target(areas = listOf(area(alpha), area(retiredAreaId, active = false)))
        check(!policy.canManageTarget(moderator, withInactiveAssignment)) {
            "an inactive assignment confers no scope to anyone, even if the target's other areas are fine"
        }
    }

    @Test
    fun `a territorial moderator cannot manage an unassigned SERVICE_USER`() {
        val moderator = actor(UserRole.MODERATOR, ownAreas = setOf(alpha))
        check(!policy.canManageTarget(moderator, target(areas = emptyList())))
    }

    @Test
    fun `a territorial moderator can grant only an active area inside their own scope`() {
        val moderator = actor(UserRole.MODERATOR, ownAreas = setOf(alpha))
        check(policy.canAssignArea(moderator, area(alpha)))
        check(!policy.canAssignArea(moderator, area(beta))) { "cannot widen anyone's reach beyond their own scope" }
        check(!policy.canAssignArea(moderator, area(alpha, active = false)))
    }

    @Test
    fun `a territorial moderator cannot remove a non-global user's last area`() {
        val moderator = actor(UserRole.MODERATOR, ownAreas = setOf(alpha))
        check(policy.wouldViolateLastAreaRule(moderator, target(areas = listOf(area(alpha))), remainingAreaCount = 0))
        check(!policy.wouldViolateLastAreaRule(moderator, target(areas = listOf(area(alpha), area(beta))), remainingAreaCount = 1))
    }

    @Test
    fun `a territorial moderator cannot change roles or global access`() {
        val moderator = actor(UserRole.MODERATOR, ownAreas = setOf(alpha))
        check(!policy.canChangeRole(moderator))
        check(!policy.canChangeGlobalAccess(moderator))
    }

    @Test
    fun `a moderator can only create SERVICE_USER`() {
        val moderator = actor(UserRole.MODERATOR, ownAreas = setOf(alpha))
        check(policy.canCreateRole(moderator, UserRole.SERVICE_USER))
        check(!policy.canCreateRole(moderator, UserRole.MODERATOR))
        check(!policy.canCreateRole(moderator, UserRole.SUPER_ADMIN))
    }

    // ------------------------------------------------------------------------- global MODERATOR

    @Test
    fun `a global moderator can manage non-global SERVICE_USERs everywhere, including unassigned`() {
        val globalModerator = actor(UserRole.MODERATOR, global = true)
        check(policy.canManageTarget(globalModerator, target(areas = emptyList())))
        check(policy.canManageTarget(globalModerator, target(areas = listOf(area(alpha), area(beta)))))
    }

    @Test
    fun `a global moderator can view peers globally`() {
        val globalModerator = actor(UserRole.MODERATOR, global = true)
        check(policy.canViewTarget(globalModerator, target(UserRole.MODERATOR, areas = listOf(area(beta)))))
        check(policy.canViewTarget(globalModerator, target(UserRole.SERVICE_USER)))
    }

    @Test
    fun `a global moderator cannot manage another MODERATOR`() {
        val globalModerator = actor(UserRole.MODERATOR, global = true)
        check(!policy.canManageTarget(globalModerator, target(UserRole.MODERATOR)))
    }

    @Test
    fun `a global moderator cannot manage a global SERVICE_USER`() {
        val globalModerator = actor(UserRole.MODERATOR, global = true)
        check(!policy.canManageTarget(globalModerator, target(global = true)))
    }

    @Test
    fun `a global moderator cannot change roles or global permission`() {
        val globalModerator = actor(UserRole.MODERATOR, global = true)
        check(!policy.canChangeRole(globalModerator))
        check(!policy.canChangeGlobalAccess(globalModerator))
    }

    @Test
    fun `a global moderator can assign any active area, not only their own`() {
        val globalModerator = actor(UserRole.MODERATOR, global = true, ownAreas = setOf(alpha))
        check(policy.canAssignArea(globalModerator, area(beta)))
        check(!policy.canAssignArea(globalModerator, area(retiredAreaId, active = false)))
    }

    // --------------------------------------------------------------------------------- MODERATOR never sees SUPER_ADMIN

    @Test
    fun `no moderator ever sees a SUPER_ADMIN row, territorial or global`() {
        val territorial = actor(UserRole.MODERATOR, ownAreas = setOf(alpha))
        val global = actor(UserRole.MODERATOR, global = true)
        val superAdminTarget = target(UserRole.SUPER_ADMIN)
        check(!policy.canViewTarget(territorial, superAdminTarget))
        check(!policy.canViewTarget(global, superAdminTarget))
    }
}
