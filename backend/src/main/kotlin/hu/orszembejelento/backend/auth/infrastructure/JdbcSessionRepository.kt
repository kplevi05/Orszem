package hu.orszembejelento.backend.auth.infrastructure

import hu.orszembejelento.backend.auth.domain.AuthSession
import hu.orszembejelento.backend.auth.domain.RefreshTokenRecord
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Sessions and refresh tokens. Steps 2 and 3 of the canonical lock order
 * (`USER -> SESSION -> REFRESH TOKEN`); see `JdbcUserRepository` for why that order exists.
 */
@Repository
class JdbcSessionRepository(private val jdbc: JdbcClient) {

    // ---------------------------------------------------------------- sessions

    fun insertSession(session: AuthSession) {
        jdbc.sql(
            """
            INSERT INTO auth_sessions (
                id, user_id, access_secret_hash, access_token_expires_at,
                created_at, session_expires_at, revoked_at, revocation_reason
            ) VALUES (
                :id, :userId, :accessSecretHash, :accessTokenExpiresAt,
                :createdAt, :sessionExpiresAt, NULL, NULL
            )
            """.trimIndent(),
        )
            .param("id", session.id)
            .param("userId", session.userId)
            .param("accessSecretHash", session.accessSecretHash)
            .param("accessTokenExpiresAt", toTimestamp(session.accessTokenExpiresAt))
            .param("createdAt", toTimestamp(session.createdAt))
            .param("sessionExpiresAt", toTimestamp(session.sessionExpiresAt))
            .update()
    }

    fun findSessionById(id: UUID): AuthSession? =
        jdbc.sql("$SELECT_SESSION WHERE id = :id")
            .param("id", id)
            .query(::mapSession)
            .optional()
            .orElse(null)

    /** Step 2 of the canonical lock order. Must be called inside a transaction. */
    fun lockSessionById(id: UUID): AuthSession? =
        jdbc.sql("$SELECT_SESSION WHERE id = :id FOR UPDATE")
            .param("id", id)
            .query(::mapSession)
            .optional()
            .orElse(null)

    /** Rotates the access secret of an existing session without moving its absolute expiry. */
    fun rotateAccessSecret(sessionId: UUID, accessSecretHash: ByteArray, accessTokenExpiresAt: Instant) {
        jdbc.sql(
            """
            UPDATE auth_sessions
               SET access_secret_hash      = :hash,
                   access_token_expires_at = :expiresAt
             WHERE id = :id
            """.trimIndent(),
        )
            .param("hash", accessSecretHash)
            .param("expiresAt", toTimestamp(accessTokenExpiresAt))
            .param("id", sessionId)
            .update()
    }

    fun revokeSession(sessionId: UUID, reason: String, now: Instant): Int =
        jdbc.sql(
            """
            UPDATE auth_sessions
               SET revoked_at = :now, revocation_reason = :reason
             WHERE id = :id AND revoked_at IS NULL
            """.trimIndent(),
        )
            .param("now", toTimestamp(now))
            .param("reason", reason)
            .param("id", sessionId)
            .update()

    /** Revokes every live session of a user. Used by logout-all and every password change. */
    fun revokeAllSessionsOfUser(userId: UUID, reason: String, now: Instant): List<UUID> =
        jdbc.sql(
            """
            UPDATE auth_sessions
               SET revoked_at = :now, revocation_reason = :reason
             WHERE user_id = :userId AND revoked_at IS NULL
            RETURNING id
            """.trimIndent(),
        )
            .param("now", toTimestamp(now))
            .param("reason", reason)
            .param("userId", userId)
            .query(UUID::class.java)
            .list()
            .filterNotNull()

    fun countActiveSessions(userId: UUID, now: Instant): Int =
        jdbc.sql(
            """
            SELECT COUNT(*) FROM auth_sessions
             WHERE user_id = :userId AND revoked_at IS NULL AND session_expires_at > :now
            """.trimIndent(),
        )
            .param("userId", userId)
            .param("now", toTimestamp(now))
            .query(Int::class.java)
            .single()

    // ---------------------------------------------------------- refresh tokens

    fun insertRefreshToken(token: RefreshTokenRecord) {
        jdbc.sql(
            """
            INSERT INTO refresh_tokens (
                id, session_id, secret_hash, created_at, expires_at, consumed_at, replaced_by_token_id
            ) VALUES (
                :id, :sessionId, :secretHash, :createdAt, :expiresAt, NULL, NULL
            )
            """.trimIndent(),
        )
            .param("id", token.id)
            .param("sessionId", token.sessionId)
            .param("secretHash", token.secretHash)
            .param("createdAt", toTimestamp(token.createdAt))
            .param("expiresAt", toTimestamp(token.expiresAt))
            .update()
    }

    /**
     * Non-locking lookup used only to resolve token -> session -> user before the mutation
     * transaction takes its locks in the canonical order. The row is re-read under lock
     * afterwards, so nothing is decided on this read.
     */
    fun findRefreshToken(id: UUID): RefreshTokenRecord? =
        jdbc.sql("$SELECT_REFRESH WHERE id = :id")
            .param("id", id)
            .query(::mapRefresh)
            .optional()
            .orElse(null)

    /** Step 3 of the canonical lock order. Must be called inside a transaction. */
    fun lockRefreshToken(id: UUID): RefreshTokenRecord? =
        jdbc.sql("$SELECT_REFRESH WHERE id = :id FOR UPDATE")
            .param("id", id)
            .query(::mapRefresh)
            .optional()
            .orElse(null)

    /**
     * Marks a refresh token consumed, but only if it is still unconsumed.
     *
     * The `consumed_at IS NULL` predicate makes this a compare-and-set: under a concurrent
     * replay, exactly one caller can observe an update count of 1 and proceed, and the
     * other sees 0 and treats it as reuse.
     */
    fun consumeRefreshToken(id: UUID, replacedBy: UUID, now: Instant): Boolean =
        jdbc.sql(
            """
            UPDATE refresh_tokens
               SET consumed_at = :now, replaced_by_token_id = :replacedBy
             WHERE id = :id AND consumed_at IS NULL
            """.trimIndent(),
        )
            .param("now", toTimestamp(now))
            .param("replacedBy", replacedBy)
            .param("id", id)
            .update() == 1

    fun countRefreshTokensOfSession(sessionId: UUID): Int =
        jdbc.sql("SELECT COUNT(*) FROM refresh_tokens WHERE session_id = :sessionId")
            .param("sessionId", sessionId)
            .query(Int::class.java)
            .single()

    // ------------------------------------------------------------------ mapping

    private fun mapSession(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = AuthSession(
        id = rs.getObject("id", UUID::class.java),
        userId = rs.getObject("user_id", UUID::class.java),
        accessSecretHash = rs.getBytes("access_secret_hash"),
        accessTokenExpiresAt = rs.getTimestamp("access_token_expires_at").toInstant(),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        sessionExpiresAt = rs.getTimestamp("session_expires_at").toInstant(),
        revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
        revocationReason = rs.getString("revocation_reason"),
    )

    private fun mapRefresh(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = RefreshTokenRecord(
        id = rs.getObject("id", UUID::class.java),
        sessionId = rs.getObject("session_id", UUID::class.java),
        secretHash = rs.getBytes("secret_hash"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        expiresAt = rs.getTimestamp("expires_at").toInstant(),
        consumedAt = rs.getTimestamp("consumed_at")?.toInstant(),
        replacedByTokenId = rs.getObject("replaced_by_token_id", UUID::class.java),
    )

    private companion object {
        const val SELECT_SESSION = """
            SELECT id, user_id, access_secret_hash, access_token_expires_at,
                   created_at, session_expires_at, revoked_at, revocation_reason
              FROM auth_sessions
        """

        const val SELECT_REFRESH = """
            SELECT id, session_id, secret_hash, created_at, expires_at,
                   consumed_at, replaced_by_token_id
              FROM refresh_tokens
        """

        fun toTimestamp(instant: Instant): java.sql.Timestamp = java.sql.Timestamp.from(instant)
    }
}
