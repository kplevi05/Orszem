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
