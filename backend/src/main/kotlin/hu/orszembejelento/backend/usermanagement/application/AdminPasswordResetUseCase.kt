package hu.orszembejelento.backend.usermanagement.application

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.auth.domain.RevocationReason
import hu.orszembejelento.backend.auth.infrastructure.JdbcSessionRepository
import hu.orszembejelento.backend.identity.domain.PasswordHasher
import hu.orszembejelento.backend.identity.domain.ServiceId
import hu.orszembejelento.backend.identity.domain.TemporaryCredentialGenerator
import hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository
import hu.orszembejelento.backend.usermanagement.domain.ManagementActor
import hu.orszembejelento.backend.usermanagement.domain.UserManagementPolicy
import hu.orszembejelento.backend.usermanagement.domain.UserNotFoundException
import hu.orszembejelento.backend.usermanagement.domain.UserNotManageableException
import hu.orszembejelento.backend.usermanagement.infrastructure.JdbcUserManagementRepository
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** What is returned exactly once — never persisted or logged (§18, §40). */
data class AdminIssuedCredential(val serviceId: ServiceId, val temporaryCredential: String)

/**
 * Administrative password reset (§18-19).
 *
 * Works for an ACTIVE or a DEACTIVATED target and never changes [status][hu.orszembejelento.backend.identity.domain.UserStatus]
 * either way — reset and reactivation are deliberately independent decisions. There is no
 * HTTP path to a SUPER_ADMIN target: [UserManagementPolicy.canResetPassword] already
 * returns false for one, so it surfaces as an ordinary "not manageable" rejection rather
 * than a special case here.
 */
@Service
class AdminPasswordResetUseCase(
    private val users: JdbcUserRepository,
    private val managedUsers: JdbcUserManagementRepository,
    private val sessions: JdbcSessionRepository,
    private val hasher: PasswordHasher,
    private val policy: UserManagementPolicy,
    private val audit: JdbcAuditRepository,
    private val credentialGenerator: TemporaryCredentialGenerator,
    private val clock: Clock,
) {

    @Transactional
    fun reset(actor: ManagementActor, rawServiceId: String?): AdminIssuedCredential {
        val serviceId = ServiceId.parseOrNull(rawServiceId) ?: throw UserNotFoundException()

        // Canonical lock order, step 1. Serialises against any concurrent mutation of this
        // same target — including a role change that might make it manageable or not.
        val locked = users.lockByServiceId(serviceId) ?: throw UserNotFoundException()
        val target = managedUsers.findManagedUser(locked.id) ?: throw UserNotFoundException()

        if (!policy.canViewTarget(actor, target)) throw UserNotFoundException()
        if (!policy.canResetPassword(actor, target)) throw UserNotManageableException()

        val temporaryCredential = credentialGenerator.generate()
        val now = clock.instant()
        val operationId = UUID.randomUUID()

        users.updatePassword(
            userId = locked.id,
            passwordHash = hasher.hash(TemporaryCredentialGenerator.normalize(temporaryCredential)),
            mustChangePassword = true,
            now = now,
        )

        val revoked = sessions.revokeAllSessionsOfUser(locked.id, RevocationReason.ADMIN_PASSWORD_RESET, now)

        audit.record(
            operationId = operationId,
            actorType = AuditActorType.USER,
            actorUserId = actor.userId,
            eventType = AuditEventType.USER_PASSWORD_RESET,
            targetType = AuditTargetType.USER,
            targetId = locked.id,
            // Never the credential or its hash.
            metadata = mapOf("serviceId" to serviceId.value, "revokedSessions" to revoked.size.toString()),
        )

        return AdminIssuedCredential(serviceId, temporaryCredential)
    }
}
