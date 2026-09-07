package hu.orszembejelento.backend.auth.application

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.auth.domain.OpaqueToken
import hu.orszembejelento.backend.auth.domain.RevocationReason
import hu.orszembejelento.backend.auth.infrastructure.JdbcSessionRepository
import hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Validates a presented access token and builds the [AuthenticatedActor].
 *
 * Every check reads current server-side state. The token carries no claims at all, so
 * there is nothing in it to trust: role, account status and the outstanding-password-change
 * flag all come from the database on every request. A user deactivated a second ago is
 * refused immediately rather than at the next token expiry.
 */
@Service
class AuthenticateAccessTokenUseCase(
    private val users: JdbcUserRepository,
    private val sessions: JdbcSessionRepository,
    private val clock: Clock,
) {

    /**
     * @param requireOperational when true, an account still owing its initial password
     *        change is rejected. Only the password-completion flow sets this to false.
     */
    @Transactional(readOnly = true)
    fun authenticate(rawAccessToken: String?, requireOperational: Boolean = true): AuthenticatedActor {
        // A refresh token presented as a bearer credential fails here on its prefix.
        val presented = OpaqueToken.parse(rawAccessToken, OpaqueToken.TokenType.ACCESS)
            ?: throw SessionInvalidException()

        val session = sessions.findSessionById(presented.id) ?: throw SessionInvalidException()
        val now = clock.instant()

        if (!OpaqueToken.secretMatches(presented.secret, session.accessSecretHash)) {
            throw SessionInvalidException()
        }
        if (session.isRevoked()) throw SessionInvalidException()
        if (session.isExpiredAt(now)) throw SessionInvalidException()
        if (session.isAccessTokenExpiredAt(now)) throw SessionInvalidException()

        val user = users.findById(session.userId) ?: throw SessionInvalidException()
        if (!user.isActive) throw SessionInvalidException()

        // An account that still owes a password change is authenticated but not operational.
        // Even if an inconsistent session somehow existed, ordinary protected work is denied.
        if (requireOperational && user.mustChangePassword) throw PasswordChangeRequiredException()

        return AuthenticatedActor(
            userId = user.id,
            serviceId = user.serviceId,
            role = user.role,
            sessionId = session.id,
        )
    }
}

/** Revokes the caller's current session. */
@Service
class LogoutUseCase(
    private val sessions: JdbcSessionRepository,
    private val audit: JdbcAuditRepository,
    private val clock: Clock,
) {

    @Transactional
    fun logout(actor: AuthenticatedActor) {
        val now = clock.instant()
        val revoked = sessions.revokeSession(actor.sessionId, RevocationReason.LOGOUT, now)

        // Only audit an actual state change, so repeated logout calls cannot pad the trail.
        if (revoked > 0) {
            audit.record(
                operationId = UUID.randomUUID(),
                actorType = AuditActorType.USER,
                actorUserId = actor.userId,
                eventType = AuditEventType.SESSION_REVOKED,
                targetType = AuditTargetType.SESSION,
                targetId = actor.sessionId,
                metadata = mapOf("reason" to RevocationReason.LOGOUT),
            )
        }
    }
}

/**
 * Revokes every session of the caller, including the current one.
 *
 * Locks the user row first, in the canonical order, so a login racing with this either
 * completes before it and is revoked, or waits and is created afterwards — never a session
 * created mid-revocation that silently survives.
 */
@Service
class LogoutAllUseCase(
    private val users: JdbcUserRepository,
    private val sessions: JdbcSessionRepository,
    private val audit: JdbcAuditRepository,
    private val clock: Clock,
) {

    @Transactional
    fun logoutAll(actor: AuthenticatedActor) {
        users.lockById(actor.userId) ?: throw SessionInvalidException()

        val now = clock.instant()
        val revoked = sessions.revokeAllSessionsOfUser(actor.userId, RevocationReason.LOGOUT_ALL, now)

        audit.record(
            operationId = UUID.randomUUID(),
            actorType = AuditActorType.USER,
            actorUserId = actor.userId,
            eventType = AuditEventType.LOGOUT_ALL,
            targetType = AuditTargetType.USER,
            targetId = actor.userId,
            metadata = mapOf("revokedSessions" to revoked.size.toString()),
        )
    }
}
