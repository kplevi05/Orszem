package hu.orszembejelento.backend.auth.application

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.auth.domain.RevocationReason
import hu.orszembejelento.backend.auth.infrastructure.JdbcSessionRepository
import hu.orszembejelento.backend.auth.infrastructure.LoginRateLimiter
import hu.orszembejelento.backend.identity.domain.PasswordHasher
import hu.orszembejelento.backend.identity.domain.PasswordNormalizer
import hu.orszembejelento.backend.identity.domain.PasswordPolicy
import hu.orszembejelento.backend.identity.domain.ServiceId
import hu.orszembejelento.backend.identity.domain.TemporaryCredentialGenerator
import hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Completes the forced initial password change.
 *
 * There is deliberately no temporary-session subsystem. Logging in with a temporary
 * credential issues no tokens at all; the client comes straight here with the temporary
 * password and the new one. That keeps a half-authenticated state from existing, and means
 * there is no partially privileged token to leak.
 *
 * The temporary credential stays valid until this operation succeeds. A login attempt does
 * not consume it — so if the app is killed between the login response and this call, the
 * user can simply start again rather than being locked out with an account nobody can reach.
 */
@Service
class CompleteInitialPasswordChangeUseCase(
    private val users: JdbcUserRepository,
    private val sessions: JdbcSessionRepository,
    private val hasher: PasswordHasher,
    private val policy: PasswordPolicy,
    private val sessionFactory: SessionFactory,
    private val audit: JdbcAuditRepository,
    private val rateLimiter: LoginRateLimiter,
    private val clock: Clock,
) {

    @Transactional
    fun complete(
        rawServiceId: String?,
        rawTemporaryPassword: String,
        rawNewPassword: String,
        sourceIp: String?,
    ): IssuedCredentials {
        val serviceId = ServiceId.parseOrNull(rawServiceId) ?: throw InvalidCredentialsException()
        rateLimiter.checkAllowed(serviceId.value, sourceIp)

        // Canonical lock order, step 1.
        val user = users.lockByServiceId(serviceId) ?: run {
            rateLimiter.recordFailure(serviceId.value, sourceIp)
            throw InvalidCredentialsException()
        }

        if (!user.isActive) {
            rateLimiter.recordFailure(serviceId.value, sourceIp)
            throw InvalidCredentialsException()
        }

        // Only an account that actually owes a change may use this endpoint; otherwise it
        // would be a way to change a password while knowing only the current one, bypassing
        // the authenticated change flow.
        if (!user.mustChangePassword) {
            rateLimiter.recordFailure(serviceId.value, sourceIp)
            throw InvalidCredentialsException()
        }

        // Temporary credentials are matched in their canonical form, so the user may type
        // them with or without the display hyphens and in any case.
        val candidates = listOf(
            rawTemporaryPassword,
            TemporaryCredentialGenerator.normalize(rawTemporaryPassword),
        ).distinct()

        if (candidates.none { hasher.matches(it, user.passwordHash) }) {
            rateLimiter.recordFailure(serviceId.value, sourceIp)
            throw InvalidCredentialsException()
        }

        rateLimiter.recordSuccess(serviceId.value)

        val newPassword = PasswordNormalizer.normalize(rawNewPassword)
        // The new password must differ from the temporary one it replaces.
        policy.validate(
            newPassword,
            currentPasswordForComparison = PasswordNormalizer.normalize(rawTemporaryPassword),
        )

        val now = clock.instant()
        val operationId = UUID.randomUUID()

        users.updatePassword(user.id, hasher.hash(newPassword), mustChangePassword = false, now = now)

        // Defensive: no session should exist for an account that still owed a change, but
        // if an inconsistent one somehow does, it must not survive the credential change.
        sessions.revokeAllSessionsOfUser(user.id, RevocationReason.INITIAL_PASSWORD_CHANGED, now)

        audit.record(
            operationId = operationId,
            actorType = AuditActorType.USER,
            actorUserId = user.id,
            eventType = AuditEventType.INITIAL_PASSWORD_CHANGED,
            targetType = AuditTargetType.USER,
            targetId = user.id,
        )

        return sessionFactory.createSession(user.id, operationId)
    }
}

/**
 * Password change by an already-authenticated user.
 *
 * Every existing session is revoked and exactly one fresh session is issued to the caller.
 * The current session is replaced rather than mutated in place: if a password change is a
 * response to compromise, every other device must lose access, and reusing the existing
 * session row would leave its old refresh tokens usable.
 */
@Service
class ChangeOwnPasswordUseCase(
    private val users: JdbcUserRepository,
    private val sessions: JdbcSessionRepository,
    private val hasher: PasswordHasher,
    private val policy: PasswordPolicy,
    private val sessionFactory: SessionFactory,
    private val audit: JdbcAuditRepository,
    private val clock: Clock,
) {

    @Transactional
    fun changePassword(userId: UUID, rawCurrentPassword: String, rawNewPassword: String): IssuedCredentials {
        // Canonical lock order, step 1.
        val user = users.lockById(userId) ?: throw SessionInvalidException()
        if (!user.isActive) throw SessionInvalidException()

        val currentPassword = PasswordNormalizer.normalize(rawCurrentPassword)
        if (!hasher.matches(currentPassword, user.passwordHash)) throw InvalidCredentialsException()

        val newPassword = PasswordNormalizer.normalize(rawNewPassword)
        policy.validate(newPassword, currentPasswordForComparison = currentPassword)

        val now = clock.instant()
        val operationId = UUID.randomUUID()

        users.updatePassword(user.id, hasher.hash(newPassword), mustChangePassword = false, now = now)

        val revoked = sessions.revokeAllSessionsOfUser(user.id, RevocationReason.PASSWORD_CHANGED, now)

        audit.record(
            operationId = operationId,
            actorType = AuditActorType.USER,
            actorUserId = user.id,
            eventType = AuditEventType.PASSWORD_CHANGED,
            targetType = AuditTargetType.USER,
            targetId = user.id,
            metadata = mapOf("revokedSessions" to revoked.size.toString()),
        )

        return sessionFactory.createSession(user.id, operationId)
    }
}
