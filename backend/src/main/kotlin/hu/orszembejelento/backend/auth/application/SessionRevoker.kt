package hu.orszembejelento.backend.auth.application

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.auth.domain.RevocationReason
import hu.orszembejelento.backend.auth.infrastructure.JdbcSessionRepository
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Revokes a session in its **own** transaction.
 *
 * This exists for one specific reason. Refresh-token reuse is detected inside the refresh
 * transaction, which must then fail the request — and failing it means throwing, which
 * rolls that transaction back. If the revocation were written there, it would be rolled
 * back with everything else and the compromised session would quietly survive: reuse
 * detection would report a failure while silently leaving the attacker's session usable.
 *
 * `REQUIRES_NEW` suspends the caller's transaction and commits the revocation and its audit
 * rows independently, so the session is genuinely dead even though the refresh request
 * rolls back and returns an error.
 *
 * It is a separate bean rather than a method on the use case because Spring's transactional
 * proxying does not apply to self-invocation — calling it internally would silently join
 * the outer transaction and reintroduce the very bug this prevents.
 */
@Component
class SessionRevoker(
    private val sessions: JdbcSessionRepository,
    private val audit: JdbcAuditRepository,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun revokeForRefreshTokenReuse(sessionId: UUID, userId: UUID, now: Instant) {
        val operationId = UUID.randomUUID()

        sessions.revokeSession(sessionId, RevocationReason.REFRESH_TOKEN_REUSE, now)

        audit.record(
            operationId = operationId,
            actorType = AuditActorType.USER,
            actorUserId = userId,
            eventType = AuditEventType.REFRESH_TOKEN_REUSE_DETECTED,
            targetType = AuditTargetType.SESSION,
            targetId = sessionId,
        )
        audit.record(
            operationId = operationId,
            actorType = AuditActorType.USER,
            actorUserId = userId,
            eventType = AuditEventType.SESSION_REVOKED,
            targetType = AuditTargetType.SESSION,
            targetId = sessionId,
            metadata = mapOf("reason" to RevocationReason.REFRESH_TOKEN_REUSE),
        )
    }
}
