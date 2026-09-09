package hu.orszembejelento.backend.usermanagement.application

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.auth.domain.RevocationReason
import hu.orszembejelento.backend.auth.infrastructure.JdbcSessionRepository
import hu.orszembejelento.backend.identity.domain.ServiceId
import hu.orszembejelento.backend.identity.domain.UserStatus
import hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository
import hu.orszembejelento.backend.usermanagement.domain.ManagedUser
import hu.orszembejelento.backend.usermanagement.domain.ManagementActor
import hu.orszembejelento.backend.usermanagement.domain.UserManagementPolicy
import hu.orszembejelento.backend.usermanagement.domain.UserNotFoundException
import hu.orszembejelento.backend.usermanagement.domain.UserNotManageableException
import hu.orszembejelento.backend.usermanagement.infrastructure.JdbcUserManagementRepository
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Deactivates a target (§20). Revokes every session so a deactivated account cannot keep
 * using credentials it already held — the one status change in this module that must
 * revoke sessions (§23 explicitly excludes ordinary role/scope changes from that rule).
 *
 * Repeated deactivation is idempotent: a target already DEACTIVATED is left untouched and
 * audited with nothing further, so the trail cannot be padded by retrying the same call.
 */
@Service
class DeactivateUserUseCase(
    private val users: JdbcUserRepository,
    private val managedUsers: JdbcUserManagementRepository,
    private val sessions: JdbcSessionRepository,
    private val policy: UserManagementPolicy,
    private val audit: JdbcAuditRepository,
    private val clock: Clock,
) {

    @Transactional
    fun deactivate(actor: ManagementActor, rawServiceId: String?): ManagedUser {
        val (locked, target) = lockAndAuthorize(users, managedUsers, policy, actor, rawServiceId)

        if (target.status == UserStatus.DEACTIVATED) return target

        val now = clock.instant()
        users.updateStatus(locked.id, UserStatus.DEACTIVATED, now)
        val revoked = sessions.revokeAllSessionsOfUser(locked.id, RevocationReason.ADMIN_USER_DEACTIVATED, now)

        audit.record(
            operationId = UUID.randomUUID(),
            actorType = AuditActorType.USER,
            actorUserId = actor.userId,
            eventType = AuditEventType.USER_DEACTIVATED,
            targetType = AuditTargetType.USER,
            targetId = locked.id,
            metadata = mapOf("serviceId" to locked.serviceId.value, "revokedSessions" to revoked.size.toString()),
        )

        return target.copy(status = UserStatus.DEACTIVATED)
    }
}

/**
 * Reactivates a target (§21). Deliberately does **not** touch the password, does not clear
 * `mustChangePassword`, and does not create a session: reactivation is purely a status flip,
 * and silently changing credential state alongside it would be a surprise no caller asked for.
 */
@Service
class ReactivateUserUseCase(
    private val users: JdbcUserRepository,
    private val managedUsers: JdbcUserManagementRepository,
    private val policy: UserManagementPolicy,
    private val audit: JdbcAuditRepository,
    private val clock: Clock,
) {

    @Transactional
    fun reactivate(actor: ManagementActor, rawServiceId: String?): ManagedUser {
        val (locked, target) = lockAndAuthorize(users, managedUsers, policy, actor, rawServiceId)

        if (target.status == UserStatus.ACTIVE) return target

        users.updateStatus(locked.id, UserStatus.ACTIVE, clock.instant())

        audit.record(
            operationId = UUID.randomUUID(),
            actorType = AuditActorType.USER,
            actorUserId = actor.userId,
            eventType = AuditEventType.USER_REACTIVATED,
            targetType = AuditTargetType.USER,
            targetId = locked.id,
            metadata = mapOf("serviceId" to locked.serviceId.value),
        )

        return target.copy(status = UserStatus.ACTIVE)
    }
}

/**
 * Shared lock-then-authorize sequence (canonical order step 1, Phase 6 brief §32-34): lock
 * the target user row, load its fresh scope-aware snapshot, then apply the visibility check
 * before the manageability check — an invisible target is reported identically to a
 * nonexistent one (§28/§41), and a visible-but-unmanageable one gets a distinct rejection.
 */
internal fun lockAndAuthorize(
    users: JdbcUserRepository,
    managedUsers: JdbcUserManagementRepository,
    policy: UserManagementPolicy,
    actor: ManagementActor,
    rawServiceId: String?,
): Pair<hu.orszembejelento.backend.identity.domain.User, ManagedUser> {
    val serviceId = ServiceId.parseOrNull(rawServiceId) ?: throw UserNotFoundException()
    val locked = users.lockByServiceId(serviceId) ?: throw UserNotFoundException()
    val target = managedUsers.findManagedUser(locked.id) ?: throw UserNotFoundException()

    if (!policy.canViewTarget(actor, target)) throw UserNotFoundException()
    if (!policy.canManageTarget(actor, target)) throw UserNotManageableException()

    return locked to target
}
