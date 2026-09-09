package hu.orszembejelento.backend.usermanagement.application

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.identity.domain.ServiceId
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository
import hu.orszembejelento.backend.usermanagement.domain.InvalidRoleTransitionException
import hu.orszembejelento.backend.usermanagement.domain.ManagedUser
import hu.orszembejelento.backend.usermanagement.domain.ManagementActor
import hu.orszembejelento.backend.usermanagement.domain.UserManagementForbiddenException
import hu.orszembejelento.backend.usermanagement.domain.UserManagementPolicy
import hu.orszembejelento.backend.usermanagement.domain.UserNotFoundException
import hu.orszembejelento.backend.usermanagement.infrastructure.JdbcUserManagementRepository
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * SERVICE_USER <-> MODERATOR, SUPER_ADMIN-only (§22).
 *
 * The only two allowed transitions are exactly SERVICE_USER -> MODERATOR and
 * MODERATOR -> SERVICE_USER. Anything naming SUPER_ADMIN either as the actor's own
 * privilege, the target's current role or the requested role is rejected — there is no
 * HTTP path to that role in either direction (§46). Area assignments, `global_area_access`,
 * the password and the account status are all left exactly as they were: only the `role`
 * column changes.
 *
 * No session is revoked. Every protected request re-derives the actor's role and scope from
 * the database (§23), so the new role is authoritative on the very next request without
 * needing to invalidate whatever session the target already holds.
 */
@Service
class ChangeUserRoleUseCase(
    private val users: JdbcUserRepository,
    private val managedUsers: JdbcUserManagementRepository,
    private val policy: UserManagementPolicy,
    private val audit: JdbcAuditRepository,
    private val clock: Clock,
) {

    @Transactional
    fun changeRole(actor: ManagementActor, rawServiceId: String?, requestedRole: UserRole): ManagedUser {
        if (!policy.canChangeRole(actor)) throw UserManagementForbiddenException()

        val serviceId = ServiceId.parseOrNull(rawServiceId) ?: throw UserNotFoundException()
        // Canonical lock order, step 1 — re-authorized against the CURRENT role below, so a
        // target promoted or demoted a moment ago by someone else cannot be raced.
        val locked = users.lockByServiceId(serviceId) ?: throw UserNotFoundException()
        val target = managedUsers.findManagedUser(locked.id) ?: throw UserNotFoundException()

        val allowed = (target.role == UserRole.SERVICE_USER && requestedRole == UserRole.MODERATOR) ||
            (target.role == UserRole.MODERATOR && requestedRole == UserRole.SERVICE_USER)
        if (!allowed) throw InvalidRoleTransitionException()

        val now = clock.instant()
        users.updateRole(locked.id, requestedRole, now)

        audit.record(
            operationId = UUID.randomUUID(),
            actorType = AuditActorType.USER,
            actorUserId = actor.userId,
            eventType = AuditEventType.USER_ROLE_CHANGED,
            targetType = AuditTargetType.USER,
            targetId = locked.id,
            metadata = mapOf(
                "serviceId" to serviceId.value,
                "oldRole" to target.role.name,
                "newRole" to requestedRole.name,
            ),
        )

        return target.copy(role = requestedRole)
    }
}
