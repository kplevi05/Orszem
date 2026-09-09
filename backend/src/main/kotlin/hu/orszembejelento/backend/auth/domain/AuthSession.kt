package hu.orszembejelento.backend.auth.domain

import java.time.Instant
import java.util.UUID

/**
 * A server-side session.
 *
 * Holds only the SHA-256 digest of the current access secret; the secret itself exists
 * once, in the response to the client, and is never persisted or logged.
 *
 * [sessionExpiresAt] is absolute. Refreshing rotates [accessSecretHash] and
 * [accessTokenExpiresAt] but never moves this, which is what prevents a session from being
 * kept alive forever by refreshing.
 */
data class AuthSession(
    val id: UUID,
    val userId: UUID,
    val accessSecretHash: ByteArray,
    val accessTokenExpiresAt: Instant,
    val createdAt: Instant,
    val sessionExpiresAt: Instant,
    val revokedAt: Instant?,
    val revocationReason: String?,
) {
    fun isRevoked(): Boolean = revokedAt != null

    fun isExpiredAt(now: Instant): Boolean = !now.isBefore(sessionExpiresAt)

    fun isAccessTokenExpiredAt(now: Instant): Boolean = !now.isBefore(accessTokenExpiresAt)

    /** Usable for refresh: not revoked and within its absolute lifetime. */
    fun isLiveAt(now: Instant): Boolean = !isRevoked() && !isExpiredAt(now)

    // ByteArray needs identity-independent equality, which data classes do not provide.
    override fun equals(other: Any?): Boolean = this === other || (other is AuthSession && id == other.id)

    override fun hashCode(): Int = id.hashCode()
}

/** Reasons a session was revoked. Recorded for the audit trail and for diagnosis. */
object RevocationReason {
    const val LOGOUT = "LOGOUT"
    const val LOGOUT_ALL = "LOGOUT_ALL"
    const val PASSWORD_CHANGED = "PASSWORD_CHANGED"
    const val INITIAL_PASSWORD_CHANGED = "INITIAL_PASSWORD_CHANGED"
    const val ADMIN_PASSWORD_RESET = "ADMIN_PASSWORD_RESET"
    const val ADMIN_USER_DEACTIVATED = "ADMIN_USER_DEACTIVATED"
    const val REFRESH_TOKEN_REUSE = "REFRESH_TOKEN_REUSE"
}

/**
 * A single rotating refresh token.
 *
 * A consumed row is kept for the life of the session on purpose: that record is what makes
 * replay detectable. Deleting it as soon as it is used would quietly disable reuse
 * detection, which is the main protection against a stolen refresh token.
 */
data class RefreshTokenRecord(
    val id: UUID,
    val sessionId: UUID,
    val secretHash: ByteArray,
    val createdAt: Instant,
    val expiresAt: Instant,
    val consumedAt: Instant?,
    val replacedByTokenId: UUID?,
) {
    fun isConsumed(): Boolean = consumedAt != null

    fun isExpiredAt(now: Instant): Boolean = !now.isBefore(expiresAt)

    override fun equals(other: Any?): Boolean = this === other || (other is RefreshTokenRecord && id == other.id)

    override fun hashCode(): Int = id.hashCode()
}
