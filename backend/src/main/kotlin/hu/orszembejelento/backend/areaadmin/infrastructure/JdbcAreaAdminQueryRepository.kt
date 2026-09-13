package hu.orszembejelento.backend.areaadmin.infrastructure

import hu.orszembejelento.backend.areaadmin.domain.RailwayLineAdminListFilter
import hu.orszembejelento.backend.areaadmin.domain.RailwayLineAdminListPage
import hu.orszembejelento.backend.areaadmin.domain.RailwayLineAdminListRow
import hu.orszembejelento.backend.areaadmin.domain.RailwayLineAssignmentFilter
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaAdminListFilter
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaAdminListPage
import hu.orszembejelento.backend.areaadmin.domain.ServiceAreaAdminListRow
import java.sql.ResultSet
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/**
 * Read-only, joined queries behind the ServiceArea/RailwayLine admin list screens (brief
 * §36/§37/§39). Every write these two lists need (create/rename/activate/deactivate an area,
 * assign/move/unassign a line) goes through [hu.orszembejelento.backend.scope.infrastructure.JdbcServiceAreaRepository]
 * instead, exactly like [hu.orszembejelento.backend.moderation.infrastructure.JdbcModerationQueryRepository]
 * stays read-only beside [hu.orszembejelento.backend.moderation.infrastructure.JdbcModerationRepository]'s
 * mutations.
 */
@Repository
class JdbcAreaAdminQueryRepository(private val jdbc: JdbcClient) {

    // -------------------------------------------------------------- ServiceArea admin list

    fun findAreaList(filter: ServiceAreaAdminListFilter, page: Int, size: Int): ServiceAreaAdminListPage {
        val (where, params) = filterClause(filter)
        val total = jdbc.sql("SELECT COUNT(*) FROM service_areas sa $where").params(params).query(Int::class.java).single()

        val items = jdbc.sql(
            """
            SELECT sa.id, sa.name, sa.status, sa.admin_version,
                   (SELECT COUNT(*) FROM service_area_railway_lines m WHERE m.service_area_id = sa.id) AS mapped_line_count,
                   (
                       SELECT COUNT(*) FROM reports r
                         JOIN report_routing_snapshots rs ON rs.report_id = r.id
                        WHERE rs.service_area_id = sa.id
                          AND r.status IN ('NEW', 'IN_PROGRESS')
                          AND NOT EXISTS (
                              SELECT 1 FROM report_moderation_episodes rme
                               WHERE rme.report_id = r.id AND rme.restored_at IS NULL
                          )
                   ) AS open_report_count
              FROM service_areas sa
              $where
             ORDER BY (sa.status = 'ACTIVE') DESC, sa.name ASC, sa.id ASC
             LIMIT :limit OFFSET :offset
            """.trimIndent(),
        )
            .params(params)
            .param("limit", size)
            .param("offset", page * size)
            .query(::mapAreaRow)
            .list()

        return ServiceAreaAdminListPage(items, total)
    }

    private fun filterClause(filter: ServiceAreaAdminListFilter): Pair<String, Map<String, Any>> {
        val clauses = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()

        filter.active?.let { clauses += "sa.status = :fStatus"; params["fStatus"] = if (it) "ACTIVE" else "INACTIVE" }
        filter.query?.takeIf { it.isNotBlank() }?.let {
            clauses += "sa.name ILIKE :fQuery"
            params["fQuery"] = "%${escapeLike(it)}%"
        }

        return (if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")) to params
    }

    private fun mapAreaRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = ServiceAreaAdminListRow(
        id = rs.getObject("id", UUID::class.java),
        name = rs.getString("name"),
        active = rs.getString("status") == "ACTIVE",
        adminVersion = rs.getLong("admin_version"),
        mappedRailwayLineCount = rs.getInt("mapped_line_count"),
        openOperationalReportCount = rs.getInt("open_report_count"),
    )

    // ---------------------------------------------------------------- RailwayLine admin list

    fun findRailwayLineList(filter: RailwayLineAdminListFilter, page: Int, size: Int): RailwayLineAdminListPage {
        val (where, params) = railwayLineFilterClause(filter)
        val total = jdbc.sql("SELECT COUNT(*) $LINE_FROM_JOINS $where").params(params).query(Int::class.java).single()

        val items = jdbc.sql(
            "SELECT l.id, l.line_code, l.display_name, l.active, m.service_area_id, sa.name AS service_area_name " +
                "$LINE_FROM_JOINS $where ORDER BY l.line_code ASC LIMIT :limit OFFSET :offset",
        )
            .params(params)
            .param("limit", size)
            .param("offset", page * size)
            .query(::mapLineRow)
            .list()

        return RailwayLineAdminListPage(items, total)
    }

    private fun railwayLineFilterClause(filter: RailwayLineAdminListFilter): Pair<String, Map<String, Any>> {
        val clauses = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()

        filter.active?.let { clauses += "l.active = :fActive"; params["fActive"] = it }
        filter.serviceAreaId?.let { clauses += "m.service_area_id = :fArea"; params["fArea"] = it }
        when (filter.assignment) {
            RailwayLineAssignmentFilter.ASSIGNED -> clauses += "m.service_area_id IS NOT NULL"
            RailwayLineAssignmentFilter.UNASSIGNED -> clauses += "m.service_area_id IS NULL"
            RailwayLineAssignmentFilter.ALL -> {}
        }
        filter.query?.takeIf { it.isNotBlank() }?.let {
            clauses += "(l.line_code ILIKE :fQuery OR l.display_name ILIKE :fQuery)"
            params["fQuery"] = "%${escapeLike(it)}%"
        }

        return (if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")) to params
    }

    private fun mapLineRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = RailwayLineAdminListRow(
        id = rs.getObject("id", UUID::class.java),
        lineCode = rs.getString("line_code"),
        displayName = rs.getString("display_name"),
        active = rs.getBoolean("active"),
        currentServiceAreaId = rs.getObject("service_area_id", UUID::class.java),
        currentServiceAreaName = rs.getString("service_area_name"),
    )

    private fun escapeLike(raw: String): String = raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private companion object {
        const val LINE_FROM_JOINS = """
              FROM railway_lines l
              LEFT JOIN service_area_railway_lines m ON m.railway_line_id = l.id
              LEFT JOIN service_areas sa ON sa.id = m.service_area_id
        """
    }
}
