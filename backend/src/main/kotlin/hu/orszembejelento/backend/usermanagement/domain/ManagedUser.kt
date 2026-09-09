package hu.orszembejelento.backend.usermanagement.domain

import hu.orszembejelento.backend.identity.domain.ServiceId
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.identity.domain.UserStatus
import hu.orszembejelento.backend.reference.domain.ServiceAreaStatus
import java.util.UUID

/**
 * One area a user currently holds, as far as a management decision is concerned.
 *
 * [status] is carried alongside the id deliberately: whether an assignment counts as
 * "within scope" depends on the area being ACTIVE, and an assignment to a now-INACTIVE
 * area must still be visible (§30 of the Phase 6 brief) without granting any authority.
 */
data class AssignedArea(val id: UUID, val name: String, val status: ServiceAreaStatus) {
    val isActive: Boolean get() = status == ServiceAreaStatus.ACTIVE
}

/**
 * A user as seen by user management: current role/status/scope, nothing session- or
 * password-related beyond [mustChangePassword].
 *
 * Deliberately not the same type as [hu.orszembejelento.backend.identity.domain.User] —
 * that type is authentication's own read model (it carries the password hash, which must
 * never flow into this module) and this one adds the scope facts [UserManagementPolicy]
 * needs. Two small, purpose-built types beat one shared one that has to be trusted not to
 * leak a field it was never meant to expose here.
 */
data class ManagedUser(
    val id: UUID,
    val serviceId: ServiceId,
    val role: UserRole,
    val status: UserStatus,
    val mustChangePassword: Boolean,
    val globalAreaAccess: Boolean,
    val assignedAreas: List<AssignedArea>,
)

/**
 * The authorisation-relevant facts about the actor performing a user-management operation.
 *
 * [ownActiveAreaIds] is already filtered to areas the actor may currently act in — an
 * assignment of the actor's own to an INACTIVE area is excluded, exactly as it would be
 * from [hu.orszembejelento.backend.scope.domain.AreaScopePolicy]'s notion of scope. A
 * SUPER_ADMIN's value here is irrelevant, since every policy check for that role short
 * circuits on the role itself.
 */
data class ManagementActor(
    val userId: UUID,
    val role: UserRole,
    val globalAreaAccess: Boolean,
    val ownActiveAreaIds: Set<UUID>,
)
