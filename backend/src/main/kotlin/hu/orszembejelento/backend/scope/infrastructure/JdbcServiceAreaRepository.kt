package hu.orszembejelento.backend.scope.infrastructure

import hu.orszembejelento.backend.reference.domain.ServiceArea
import hu.orszembejelento.backend.reference.domain.ServiceAreaStatus
import hu.orszembejelento.backend.scope.domain.AreaActor
import hu.orszembejelento.backend.identity.domain.UserRole
import java.sql.ResultSet
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Service areas, their current railway-line configuration, and user authorisation.
 *
 * Three separate concerns share this repository because they share the `service_areas`
 * table, but the tables underneath stay distinct: configuration
 * (`service_area_railway_lines`) and authorisation (`user_service_areas`) are never mixed.
 */
@Repository
class JdbcServiceAreaRepository(private val jdbc: JdbcClient) {

    // ------------------------------------------------------------ service areas

    fun findById(id: UUID): ServiceArea? =
        jdbc.sql("$SELECT_AREA WHERE id = :id")
            .param("id", id)
            .query(::mapArea)
            .optional()
            .orElse(null)

    fun findAll(): List<ServiceArea> =
        jdbc.sql("$SELECT_AREA ORDER BY name").query(::mapArea).list()

    /** Every currently ACTIVE area, name-ordered — the assignable-area list (Phase 6 brief §29). */
    fun findAllActive(): List<ServiceArea> =
        jdbc.sql("$SELECT_AREA WHERE status = 'ACTIVE' ORDER BY name").query(::mapArea).list()

    /**
     * Step 2 of the canonical lock order (`USER -> SERVICE AREA -> user_service_areas`,
     * Phase 6 brief §32) when a mutation also touches an area. Must be called inside a
     * transaction, after the target user row is already locked.
     */
    fun lockById(id: UUID): ServiceArea? =
        jdbc.sql("$SELECT_AREA WHERE id = :id FOR UPDATE")
            .param("id", id)
            .query(::mapArea)
            .optional()
            .orElse(null)

    fun insert(area: ServiceArea) {
        jdbc.sql(
            """
            INSERT INTO service_areas (id, name, status, created_at, updated_at)
            VALUES (:id, :name, :status, :createdAt, :updatedAt)
            """.trimIndent(),
        )
            .param("id", area.id)
            .param("name", area.name)
            .param("status", area.status.name)
            .param("createdAt", java.sql.Timestamp.from(area.createdAt))
            .param("updatedAt", java.sql.Timestamp.from(area.updatedAt))
            .update()
    }

    // ------------------------------------------------- line -> area configuration

    /**
     * The area currently responsible for a line, if any.
     *
     * At most one row can exist per line: the database enforces it with a unique index, so
     * this cannot ambiguously return two areas.
     */
    fun findAreaOfRailwayLine(railwayLineId: UUID): ServiceArea? =
        jdbc.sql(
            """
            SELECT a.id, a.name, a.status, a.created_at, a.updated_at
              FROM service_areas a
              JOIN service_area_railway_lines m ON m.service_area_id = a.id
             WHERE m.railway_line_id = :lineId
            """.trimIndent(),
        )
            .param("lineId", railwayLineId)
            .query(::mapArea)
            .optional()
            .orElse(null)

    fun assignRailwayLine(serviceAreaId: UUID, railwayLineId: UUID) {
        jdbc.sql(
            """
            INSERT INTO service_area_railway_lines (service_area_id, railway_line_id)
            VALUES (:areaId, :lineId)
            """.trimIndent(),
        )
            .param("areaId", serviceAreaId)
            .param("lineId", railwayLineId)
            .update()
    }

    // ------------------------------------------------------------ authorisation

    fun assignUserToArea(userId: UUID, serviceAreaId: UUID) {
        jdbc.sql(
            """
            INSERT INTO user_service_areas (user_id, service_area_id)
            VALUES (:userId, :areaId)
            """.trimIndent(),
        )
            .param("userId", userId)
            .param("areaId", serviceAreaId)
            .update()
    }

    fun assignedAreaIds(userId: UUID): Set<UUID> =
        jdbc.sql("SELECT service_area_id FROM user_service_areas WHERE user_id = :userId")
            .param("userId", userId)
            .query(UUID::class.java)
            .list()
            .filterNotNull()
            .toSet()

    /**
     * Only the areas [userId] may currently *act* in: assigned AND active. An assignment to
     * an area that has since gone INACTIVE is deliberately excluded — it confers no scope to
     * anyone (Phase 6 brief §7/§30), unlike [assignedAreaIds], which is the raw, unfiltered
     * assignment set used where the inactive rows themselves must be visible.
     */
    fun activeAssignedAreaIds(userId: UUID): Set<UUID> =
        jdbc.sql(
            """
            SELECT usa.service_area_id
              FROM user_service_areas usa
              JOIN service_areas sa ON sa.id = usa.service_area_id
             WHERE usa.user_id = :userId AND sa.status = 'ACTIVE'
            """.trimIndent(),
        )
            .param("userId", userId)
            .query(UUID::class.java)
            .list()
            .filterNotNull()
            .toSet()

    /**
     * Grants [serviceAreaId] to [userId], or does nothing if the grant already exists.
     *
     * Idempotent by construction: the `user_service_areas` primary key is the authority on
     * "already granted", not a prior existence check, which would still race (Phase 6 brief
     * §8/§35). Uses `ON CONFLICT DO NOTHING` rather than catching the constraint violation -
     * PostgreSQL aborts the *entire enclosing transaction* the instant any statement raises
     * an error, and catching the resulting `DuplicateKeyException` in application code does
     * not undo that: every later statement in the same transaction (including, here, the
     * caller's own follow-up read of the refreshed user) would then fail with "current
     * transaction is aborted" even though the Kotlin exception was already handled. A
     * conflict clause never raises an error in the first place, so no savepoint is needed.
     * Returns whether a new row was actually inserted, purely so a caller can decide whether
     * the state genuinely changed and an audit row is warranted.
     */
    fun grantAreaIfAbsent(userId: UUID, serviceAreaId: UUID): Boolean =
        jdbc.sql(
            """
            INSERT INTO user_service_areas (user_id, service_area_id)
            VALUES (:userId, :areaId)
            ON CONFLICT (user_id, service_area_id) DO NOTHING
            """.trimIndent(),
        )
            .param("userId", userId)
            .param("areaId", serviceAreaId)
            .update() == 1

    /** Revokes a grant. Returns the number of rows removed (0 or 1) — a no-op revoke is not an error. */
    fun revokeArea(userId: UUID, serviceAreaId: UUID): Int =
        jdbc.sql("DELETE FROM user_service_areas WHERE user_id = :userId AND service_area_id = :areaId")
            .param("userId", userId)
            .param("areaId", serviceAreaId)
            .update()

    fun countAssignedAreas(userId: UUID): Int =
        jdbc.sql("SELECT COUNT(*) FROM user_service_areas WHERE user_id = :userId")
            .param("userId", userId)
            .query(Int::class.java)
            .single()

    /**
     * A user's own assigned areas, name and status included, ordered by name. Used only for
     * the self-account view (`/account/me`) — the raw id set is [assignedAreaIds].
     */
    fun assignedAreas(userId: UUID): List<ServiceArea> =
        jdbc.sql(
            """
            SELECT sa.id, sa.name, sa.status, sa.created_at, sa.updated_at
              FROM user_service_areas usa
              JOIN service_areas sa ON sa.id = usa.service_area_id
             WHERE usa.user_id = :userId
             ORDER BY sa.name ASC
            """.trimIndent(),
        )
            .param("userId", userId)
            .query(this::mapArea)
            .list()

    /**
     * Builds the authorisation view of a user from current database state.
     *
     * Role, the global flag and the assignments are all read fresh, so a change to any of
     * them takes effect on the next request rather than when some cached token expires.
     */
    fun loadAreaActor(userId: UUID): AreaActor? {
        val base = jdbc.sql("SELECT role, global_area_access FROM users WHERE id = :id")
            .param("id", userId)
            .query { rs, _ -> UserRole.valueOf(rs.getString("role")) to rs.getBoolean("global_area_access") }
            .optional()
            .orElse(null) ?: return null

        return AreaActor(
            userId = userId,
            role = base.first,
            globalAreaAccess = base.second,
            assignedAreaIds = assignedAreaIds(userId),
        )
    }

    fun setGlobalAreaAccess(userId: UUID, value: Boolean) {
        jdbc.sql("UPDATE users SET global_area_access = :value, updated_at = now() WHERE id = :id")
            .param("value", value)
            .param("id", userId)
            .update()
    }

    private fun mapArea(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = ServiceArea(
        id = rs.getObject("id", UUID::class.java),
        name = rs.getString("name"),
        status = ServiceAreaStatus.valueOf(rs.getString("status")),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
    )

    private companion object {
        const val SELECT_AREA = "SELECT id, name, status, created_at, updated_at FROM service_areas"
    }
}
