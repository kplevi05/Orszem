package hu.orszembejelento.backend.auth.application

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.auth.domain.AuthSession
import hu.orszembejelento.backend.auth.domain.OpaqueToken
import hu.orszembejelento.backend.auth.domain.RefreshTokenRecord
import hu.orszembejelento.backend.auth.infrastructure.JdbcSessionRepository
import hu.orszembejelento.backend.common.config.AuthProperties
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component

/**
 * Creates sessions and their first refresh token.
 *
 * Shared by every flow that ends with the client holding fresh credentials — login,
 * initial password completion and password change — so the rules about lifetimes and
 * auditing exist in exactly one place.
 */
@Component
class SessionFactory(
    private val sessions: JdbcSessionRepository,
    private val audit: JdbcAuditRepository,
    private val properties: AuthProperties,
    private val clock: Clock,
) {

    /**
     * Creates a session for [userId] and issues its credentials.
     *
     * Must be called inside the caller's transaction, after the user row is locked, so the
     * session cannot be created against a user whose password is being changed concurrently.
     */
    fun createSession(userId: UUID, operationId: UUID): IssuedCredentials {
        val now = clock.instant()
        val sessionId = UUID.randomUUID()

        val accessToken = OpaqueToken.issue(OpaqueToken.TokenType.ACCESS, sessionId)
        val accessExpiresAt = now.plus(properties.accessTokenLifetime)
        val sessionExpiresAt = now.plus(properties.sessionLifetime)

        sessions.insertSession(
            AuthSession(
                id = sessionId,
                userId = userId,
                accessSecretHash = accessToken.secretHash(),
                accessTokenExpiresAt = accessExpiresAt,
                createdAt = now,
                sessionExpiresAt = sessionExpiresAt,
                revokedAt = null,
                revocationReason = null,
            ),
        )

        val refreshToken = issueRefreshToken(sessionId, sessionExpiresAt, now)

        audit.record(
            operationId = operationId,
            actorType = AuditActorType.USER,
            actorUserId = userId,
            eventType = AuditEventType.SESSION_CREATED,
            targetType = AuditTargetType.SESSION,
            targetId = sessionId,
        )

        return IssuedCredentials(
            accessToken = accessToken.serialize(),
            accessTokenExpiresAt = accessExpiresAt,
            refreshToken = refreshToken.serialize(),
            sessionExpiresAt = sessionExpiresAt,
            sessionId = sessionId,
        )
    }

    /**
     * Issues a refresh token for an existing session.
     *
     * The token expires with the session, never after it — that is what stops rotation
     * from extending a session past its absolute lifetime.
     */
    fun issueRefreshToken(sessionId: UUID, sessionExpiresAt: Instant, now: Instant): OpaqueToken {
        val refreshTokenId = UUID.randomUUID()
        val token = OpaqueToken.issue(OpaqueToken.TokenType.REFRESH, refreshTokenId)

        sessions.insertRefreshToken(
            RefreshTokenRecord(
                id = refreshTokenId,
                sessionId = sessionId,
                secretHash = token.secretHash(),
                createdAt = now,
                expiresAt = sessionExpiresAt,
                consumedAt = null,
                replacedByTokenId = null,
            ),
        )
        return token
    }

    fun accessTokenLifetime() = properties.accessTokenLifetime
}
