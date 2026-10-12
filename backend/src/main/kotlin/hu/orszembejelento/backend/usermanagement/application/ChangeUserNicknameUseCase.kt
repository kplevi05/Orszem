package hu.orszembejelento.backend.usermanagement.application

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.auth.application.AuthenticatedActor
import hu.orszembejelento.backend.identity.domain.User
import hu.orszembejelento.backend.identity.domain.UserNickname
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository
import hu.orszembejelento.backend.usermanagement.domain.ManagedUser
import hu.orszembejelento.backend.usermanagement.domain.ManagementActor
import hu.orszembejelento.backend.usermanagement.domain.UserManagementForbiddenException
import hu.orszembejelento.backend.usermanagement.domain.UserManagementPolicy
import hu.orszembejelento.backend.usermanagement.infrastructure.JdbcUserManagementRepository
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Changes the optional display nickname while preserving service_id as the canonical
 * identity.
 *
 * SUPER_ADMIN is the sole self-service exception. For another account the existing
 * current-state user-management hierarchy applies unchanged: SUPER_ADMIN may manage a
 * non-SUPER_ADMIN, and a MODERATOR may manage an in-scope SERVICE_USER. Peers and higher
 * roles are never manageable. Both paths lock the target row and re-authorise inside the
 * transaction, so a concurrent role/scope change cannot race the decision.
 */
@Service
class ChangeUserNicknameUseCase(
    private val users: JdbcUserRepository,
    private val managedUsers: JdbcUserManagementRepository,
    private val policy: UserManagementPolicy,
    private val audit: JdbcAuditRepository,
    private val clock: Clock,
) {

    @Transactional
    fun changeOwn(actor: AuthenticatedActor, rawNickname: String?): User {
        if (actor.role != UserRole.SUPER_ADMIN) throw UserManagementForbiddenException()

        val locked = users.lockById(actor.userId) ?: throw UserManagementForbiddenException()
        // Re-check the live row, not only the principal assembled earlier in the request.
        if (locked.role != UserRole.SUPER_ADMIN) throw UserManagementForbiddenException()

        return applyChange(actor.userId, locked, rawNickname)
    }

    @Transactional
    fun changeManaged(actor: ManagementActor, rawServiceId: String?, rawNickname: String?): ManagedUser {
        val (locked, target) = lockAndAuthorize(users, managedUsers, policy, actor, rawServiceId)
        val updated = applyChange(actor.userId, locked, rawNickname)
        return target.copy(nickname = updated.nickname)
    }

    private fun applyChange(actorUserId: UUID, locked: User, rawNickname: String?): User {
        val nickname = UserNickname.normalize(rawNickname)
        if (nickname == locked.nickname) return locked

        val now = clock.instant()
        users.updateNickname(locked.id, nickname, now)
        audit.record(
            operationId = UUID.randomUUID(),
            actorType = AuditActorType.USER,
            actorUserId = actorUserId,
            eventType = AuditEventType.USER_NICKNAME_CHANGED,
            targetType = AuditTargetType.USER,
            targetId = locked.id,
            metadata = mapOf(
                "serviceId" to locked.serviceId.value,
                "oldNickname" to locked.nickname.orEmpty(),
                "newNickname" to nickname.orEmpty(),
            ),
        )
        return locked.copy(nickname = nickname, updatedAt = now)
    }
}
