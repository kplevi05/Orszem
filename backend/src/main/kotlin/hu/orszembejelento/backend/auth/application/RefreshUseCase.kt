package hu.orszembejelento.backend.auth.application

import hu.orszembejelento.backend.auth.domain.OpaqueToken
import hu.orszembejelento.backend.auth.infrastructure.JdbcSessionRepository
import hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository
import java.time.Clock
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Rotating refresh.
 *
 * Every successful use consumes the presented token and issues a new access token and a
 * new refresh token. The consumed row is kept, which is what makes replay detectable.
 *
 * ## Reuse detection
 * If an already-consumed refresh token is presented, the whole session is revoked — not
 * just that token. A consumed token being replayed means either the token was stolen and
 * the thief is using it after the legitimate client already rotated, or the legitimate
 * client is using it after a thief did. There is no way to tell which, so the safe action
 * is to end the session and make everyone authenticate again.
 *
 * ## Concurrency
 * Two callers presenting the same token are serialised by the user-row lock. One consumes
 * it and rotates; the other then observes the row as already consumed and treats it as
 * reuse, revoking the session. The winner's freshly issued credentials are invalidated too,
 * because they belong to the session that was just revoked. Correctness comes from
 * PostgreSQL row locks and a compare-and-set update, not from any in-process lock — a JVM
 * `synchronized` block would protect only one instance and would silently stop working the
 * moment a second backend existed.
 */
@Service
class RefreshUseCase(
    private val users: JdbcUserRepository,
    private val sessions: JdbcSessionRepository,
    private val sessionFactory: SessionFactory,
    private val revoker: SessionRevoker,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun refresh(rawRefreshToken: String?): IssuedCredentials {
        // Only a refresh token is accepted here. An access token presented instead is
        // rejected by its prefix, so the two token types can never be confused.
        val presented = OpaqueToken.parse(rawRefreshToken, OpaqueToken.TokenType.REFRESH)
            ?: throw SessionInvalidException()

        // Non-locking resolution of token -> session -> user, purely to learn which rows to
        // lock. Nothing is decided on this read; every value is re-read under lock below.
        val preliminary = sessions.findRefreshToken(presented.id) ?: throw SessionInvalidException()
        val preliminarySession = sessions.findSessionById(preliminary.sessionId) ?: throw SessionInvalidException()

        // Canonical lock order: USER -> SESSION -> REFRESH TOKEN.
        val user = users.lockById(preliminarySession.userId) ?: throw SessionInvalidException()
        val session = sessions.lockSessionById(preliminary.sessionId) ?: throw SessionInvalidException()
        val token = sessions.lockRefreshToken(presented.id) ?: throw SessionInvalidException()

        val now = clock.instant()

        // Verified in constant time; a wrong secret for a known token id must not be
        // distinguishable from an unknown id.
        if (!OpaqueToken.secretMatches(presented.secret, token.secretHash)) {
            throw SessionInvalidException()
        }

        if (token.isConsumed()) {
            // Replay. Revoke the entire session, not merely this token.
            log.warn(
                "refresh token reuse detected; revoking session {} of user {}",
                session.id,
                user.id,
            )
            revokeForReuse(session.id, user.id, now)
            throw SessionInvalidException()
        }

        if (token.isExpiredAt(now) || !session.isLiveAt(now) || !user.canPerformProtectedOperations) {
            throw SessionInvalidException()
        }

        val newRefreshTokenId = UUID.randomUUID()
        val newRefreshToken = OpaqueToken.issue(OpaqueToken.TokenType.REFRESH, newRefreshTokenId)

        // The replacement row must exist before the old row can point at it: the consuming
        // update sets replaced_by_token_id, which is a foreign key into this same table.
        sessions.insertRefreshToken(
            hu.orszembejelento.backend.auth.domain.RefreshTokenRecord(
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
        // this is a backstop: if another caller somehow consumed the token first, losing
        // here is treated exactly like any other replay.
        val consumed = sessions.consumeRefreshToken(token.id, newRefreshTokenId, now)
        if (!consumed) {
            log.warn("concurrent refresh reuse detected; revoking session {}", session.id)
            revokeForReuse(session.id, user.id, now)
            throw SessionInvalidException()
        }

        val newAccessToken = OpaqueToken.issue(OpaqueToken.TokenType.ACCESS, session.id)

        // Capped at the session's absolute expiry, so refreshing can never extend a session.
        val requestedExpiry = now.plus(sessionFactory.accessTokenLifetime())
        val accessExpiresAt = minOf(requestedExpiry, session.sessionExpiresAt)

        sessions.rotateAccessSecret(session.id, newAccessToken.secretHash(), accessExpiresAt)

        return IssuedCredentials(
            accessToken = newAccessToken.serialize(),
            accessTokenExpiresAt = accessExpiresAt,
            refreshToken = newRefreshToken.serialize(),
            sessionExpiresAt = session.sessionExpiresAt,
            sessionId = session.id,
        )
    }

    /**
     * Delegates to a `REQUIRES_NEW` transaction so the revocation commits even though this
     * request is about to fail and roll back. Writing it inline would roll the revocation
     * back with the request, leaving the reused session alive — the exact opposite of what
     * reuse detection is for.
     */
    private fun revokeForReuse(sessionId: UUID, userId: UUID, now: java.time.Instant) {
        revoker.revokeForRefreshTokenReuse(sessionId, userId, now)
    }
}
