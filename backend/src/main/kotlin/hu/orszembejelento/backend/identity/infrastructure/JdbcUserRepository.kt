package hu.orszembejelento.backend.identity.infrastructure

import hu.orszembejelento.backend.identity.domain.ServiceId
import hu.orszembejelento.backend.identity.domain.User
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.identity.domain.UserStatus
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Explicit SQL over [JdbcClient]. No JPA: the flows in this module depend on precise
 * `SELECT ... FOR UPDATE` semantics and on knowing exactly when a statement runs, both of
 * which an ORM's flush timing and lazy loading would obscure.
 *
 * ## Lock order
 * Every authentication flow that mutates state takes row locks in one canonical order:
 *
 * ```
 *   USER  ->  SESSION  ->  REFRESH TOKEN
 * ```
 *
 * Two flows taking these in opposite orders would deadlock under load. Locking the user
 * row *first* also closes a real credential race: a password reset and a login using the
 * old password serialise on the same row, so the login cannot slip a new session in
 * between the reset's verification and its commit.
 */
@Repository
class JdbcUserRepository(private val jdbc: JdbcClient) {

    fun findByServiceId(serviceId: ServiceId): User? =
        jdbc.sql("$SELECT_COLUMNS WHERE service_id = :serviceId")
            .param("serviceId", serviceId.value)
            .query(::mapUser)
            .optional()
            .orElse(null)

    fun findById(id: UUID): User? =
        jdbc.sql("$SELECT_COLUMNS WHERE id = :id")
            .param("id", id)
            .query(::mapUser)
            .optional()
            .orElse(null)

    /** Step 1 of the canonical lock order. Must be called inside a transaction. */
    fun lockById(id: UUID): User? =
        jdbc.sql("$SELECT_COLUMNS WHERE id = :id FOR UPDATE")
            .param("id", id)
            .query(::mapUser)
            .optional()
            .orElse(null)

    /** Step 1 of the canonical lock order, by service ID. Must be called inside a transaction. */
    fun lockByServiceId(serviceId: ServiceId): User? =
        jdbc.sql("$SELECT_COLUMNS WHERE service_id = :serviceId FOR UPDATE")
            .param("serviceId", serviceId.value)
            .query(::mapUser)
            .optional()
            .orElse(null)

    /**
     * Inserts a user, returning false when the service ID is already taken.
     *
     * The unique index is the authority on collisions, not a prior existence check: a
     * check-then-insert would still race between the two statements.
     */
    fun insertIfServiceIdFree(user: User): Boolean =
        try {
            jdbc.sql(
                """
                INSERT INTO users (
                    id, service_id, role, status, password_hash,
                    must_change_password, password_changed_at, created_at, updated_at
                ) VALUES (
                    :id, :serviceId, :role, :status, :passwordHash,
                    :mustChangePassword, :passwordChangedAt, :createdAt, :updatedAt
                )
                """.trimIndent(),
            )
                .param("id", user.id)
                .param("serviceId", user.serviceId.value)
                .param("role", user.role.name)
                .param("status", user.status.name)
                .param("passwordHash", user.passwordHash)
                .param("mustChangePassword", user.mustChangePassword)
                .param("passwordChangedAt", user.passwordChangedAt?.let(::toTimestamp))
                .param("createdAt", toTimestamp(user.createdAt))
                .param("updatedAt", toTimestamp(user.updatedAt))
                .update() == 1
        } catch (_: DuplicateKeyException) {
            false
        }

    fun updatePassword(
        userId: UUID,
        passwordHash: String,
        mustChangePassword: Boolean,
        now: Instant,
    ) {
        jdbc.sql(
            """
            UPDATE users
               SET password_hash        = :passwordHash,
                   must_change_password = :mustChangePassword,
                   password_changed_at  = :now,
                   updated_at           = :now
             WHERE id = :id
            """.trimIndent(),
        )
            .param("passwordHash", passwordHash)
            .param("mustChangePassword", mustChangePassword)
            .param("now", toTimestamp(now))
            .param("id", userId)
            .update()
    }

    private fun mapUser(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int): User = User(
        id = rs.getObject("id", UUID::class.java),
        serviceId = ServiceId.ofTrusted(rs.getString("service_id")),
        role = UserRole.valueOf(rs.getString("role")),
        status = UserStatus.valueOf(rs.getString("status")),
        passwordHash = rs.getString("password_hash"),
        mustChangePassword = rs.getBoolean("must_change_password"),
        passwordChangedAt = rs.getTimestamp("password_changed_at")?.toInstant(),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
    )

    private companion object {
        const val SELECT_COLUMNS = """
            SELECT id, service_id, role, status, password_hash,
                   must_change_password, password_changed_at, created_at, updated_at
              FROM users
        """

        fun toTimestamp(instant: Instant): java.sql.Timestamp = java.sql.Timestamp.from(instant)
    }
}
