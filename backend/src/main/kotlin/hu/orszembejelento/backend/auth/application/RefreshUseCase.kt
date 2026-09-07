package hu.orszembejelento.backend.auth.application

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.auth.domain.OpaqueToken
import hu.orszembejelento.backend.auth.domain.RefreshTokenRecord
import hu.orszembejelento.backend.auth.domain.RevocationReason
import hu.orszembejelento.backend.auth.infrastructure.JdbcSessionRepository
import hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Rotating refresh.
 *
 * Every successful use consumes the presented token and issues a new access token and a new
 * refresh token. The consumed row is kept, which is what makes replay detectable.
 *
 * ## Reuse detection
 * If an already-consumed refresh token is presented, the entire session is revoked — not
 * just that token. A replayed token means either it was stolen and the thief is using it
 * after the real client rotated, or the real client is using it after a thief did. Nothing
 * distinguishes those cases, so the safe response is to end the session and make everyone
 * authenticate again.
 *
 * ## Why this returns a result instead of throwing
 * Detecting reuse must *write*: the revocation and its audit rows. Throwing would roll the
 * transaction back and discard exactly that write, leaving the reused session alive. So the
 * failure path returns [RefreshResult.Failed] and the transaction commits.
 *
 * An earlier version revoked in a `REQUIRES_NEW` transaction instead. That deadlocked
 * against itself: the outer transaction already held a `FOR UPDATE` lock on the session row,
 * and the inner transaction blocked trying to update that same row while its own parent sat
 * suspended holding the lock. Returning a result avoids the second transaction entirely.
 *
 * ## Concurrency
 * Two callers presenting the same token are serialised by the user-row lock. One consumes it
 * and rotates; the other then sees the row already consumed and treats it as reuse, revoking
 * the session — which also invalidates the winner's freshly issued credentials, since they
 * belong to that session. Correctness comes from PostgreSQL row locks and a compare-and-set
 * update, never from an in-process lock, which would protect only one instance and silently
 * stop working as soon as a second backend existed.
 */
@Service
class RefreshUseCase(
    private val users: JdbcUserRepository,
    private val sessions: JdbcSessionRepository,
    private val sessionFactory: SessionFactory,
    private val audit: JdbcAuditRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun refresh(rawRefreshToken: String?): RefreshResult {
        // Only a refresh token is accepted here; an access token is rejected by its prefix,
        // so the two token types can never be confused.
        val presented = OpaqueToken.parse(rawRefreshToken, OpaqueToken.TokenType.REFRESH)
            ?: return RefreshResult.Failed

        // Non-locking resolution of token -> session -> user, purely to learn which rows to
        // lock. Nothing is decided here; every value is re-read under lock below.
        val preliminary = sessions.findRefreshToken(presented.id) ?: return RefreshResult.Failed
        val preliminarySession = sessions.findSessionById(preliminary.sessionId) ?: return RefreshResult.Failed

        // Canonical lock order: USER -> SESSION -> REFRESH TOKEN.
        val user = users.lockById(preliminarySession.userId) ?: return RefreshResult.Failed
        val session = sessions.lockSessionById(preliminary.sessionId) ?: return RefreshResult.Failed
        val token = sessions.lockRefreshToken(presented.id) ?: return RefreshResult.Failed

        val now = clock.instant()

        // Constant-time: a wrong secret for a known token id must be indistinguishable from
        // an unknown id.
        if (!OpaqueToken.secretMatches(presented.secret, token.secretHash)) {
            return RefreshResult.Failed
        }

        if (token.isConsumed()) {
            log.warn("refresh token reuse detected; revoking session {} of user {}", session.id, user.id)
            revokeForReuse(session.id, user.id, now)
            return RefreshResult.Failed
        }

        if (token.isExpiredAt(now) || !session.isLiveAt(now) || !user.canPerformProtectedOperations) {
            return RefreshResult.Failed
        }

        val newRefreshTokenId = UUID.randomUUID()
        val newRefreshToken = OpaqueToken.issue(OpaqueToken.TokenType.REFRESH, newRefreshTokenId)

        // The replacement row must exist before the old row can reference it: the consuming
        // update sets replaced_by_token_id, a foreign key into this same table.
        sessions.insertRefreshToken(
            RefreshTokenRecord(
                id = newRefreshTokenId,
                sessionId = session.id,
                secretHash = newRefreshToken.secretHash(),
                createdAt = now,
                // Never beyond the session's absolute expiry.
                expiresAt = session.sessionExpiresAt,
                consumedAt = null,
                replacedByTokenId = null,
            ),
        )

        // Compare-and-set. The user-row lock already serialises concurrent refreshes, so
        // this is a backstop: losing it is treated exactly like any other replay.
        if (!sessions.consumeRefreshToken(token.id, newRefreshTokenId, now)) {
            log.warn("concurrent refresh reuse detected; revoking session {}", session.id)
            revokeForReuse(session.id, user.id, now)
            return RefreshResult.Failed
        }

        val newAccessToken = OpaqueToken.issue(OpaqueToken.TokenType.ACCESS, session.id)

        // Capped at the session's absolute expiry, so refreshing can never extend a session.
        val accessExpiresAt = minOf(now.plus(sessionFactory.accessTokenLifetime()), session.sessionExpiresAt)
        sessions.rotateAccessSecret(session.id, newAccessToken.secretHash(), accessExpiresAt)

        return RefreshResult.Rotated(
            IssuedCredentials(
                accessToken = newAccessToken.serialize(),
                accessTokenExpiresAt = accessExpiresAt,
                refreshToken = newRefreshToken.serialize(),
                sessionExpiresAt = session.sessionExpiresAt,
                sessionId = session.id,
            ),
        )
    }

    /** Revokes the session and records why, in the caller's transaction, which commits. */
    private fun revokeForReuse(sessionId: UUID, userId: UUID, now: Instant) {
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
