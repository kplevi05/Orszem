package hu.orszembejelento.backend.moderation.infrastructure

import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.moderation.domain.ModerationReason
import hu.orszembejelento.backend.reference.domain.ServiceAreaStatus
import hu.orszembejelento.backend.reports.domain.ReportStatus
import hu.orszembejelento.backend.reports.domain.RoutingSnapshotStatus
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowActor
import hu.orszembejelento.backend.routing.domain.UnclassifiedReason
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

/** Optional server-side filters for the deleted-report list (brief §18). Never bypasses scope (mirrors `ReportListFilter`). */
data class DeletedReportListFilter(val query: String? = null, val reason: ModerationReason? = null, val areaId: UUID? = null)

/**
 * One currently-deleted report, fully resolved for the deleted list/detail (brief §19/§20) -
 * the moderation twin of [hu.orszembejelento.backend.reportworkflow.infrastructure.ReportWorkflowRow].
 * Joined against the *unfiltered* reference/taxonomy lookups for the identical reason that
 * row documents: a historical report must keep resolving even once its settlement/event
 * type/line/area has since been deactivated.
 */
data class DeletedReportRow(
    val id: UUID,
    val publicId: UUID,
    val occurredAt: Instant,
    val submittedAt: Instant,
    val trainIdentifier: String?,
    val settlementId: UUID,
    val settlementName: String,
    val countyName: String?,
    val categoryCode: String,
    val categoryDisplayName: String,
    val eventTypeCode: String,
    val eventTypeDisplayName: String,
    val routingStatus: RoutingSnapshotStatus,
    val routingReason: UnclassifiedReason?,
    val resolvedRailwayLineId: UUID?,
    val resolvedRailwayLineDisplayName: String?,
    val serviceAreaId: UUID?,
    val serviceAreaName: String?,
    val serviceAreaStatus: ServiceAreaStatus?,
    val workflowVersion: Long,
    val reason: ModerationReason,
    val deletedByServiceId: String,
    val deletedAt: Instant,
    val statusBeforeDelete: ReportStatus,
)

data class DeletedReportPage(val items: List<DeletedReportRow>, val totalElements: Int)

/**
 * The read model behind the deleted-report list and detail endpoints - `reports` joined with
 * its (untouched, brief §1) routing snapshot, the currently-open moderation episode, and the
 * unfiltered settlement/taxonomy/area lookups. Visibility is expressed directly in SQL for
 * the list, mirroring [hu.orszembejelento.backend.reportworkflow.infrastructure.JdbcReportWorkflowQueryRepository]'s
 * own `visibilityClause` exactly (brief §21: identical scope rule, deleted or not) so the
 * two can never silently drift apart.
 */
@Repository
class JdbcModerationQueryRepository(private val jdbc: JdbcClient) {

    fun findByPublicId(publicId: UUID): DeletedReportRow? =
        jdbc.sql("$SELECT_ROW WHERE r.public_id = :id")
            .param("id", publicId)
            .query(::mapRow)
            .optional()
            .orElse(null)

    fun findDeletedList(actor: ReportWorkflowActor, filter: DeletedReportListFilter, page: Int, size: Int): DeletedReportPage {
        val (where, params) = whereClause(visibilityClause(actor), filterClause(filter))
        val total = jdbc.sql("SELECT COUNT(*) $FROM_JOINS $where").params(params).query(Int::class.java).single()

        val items = jdbc.sql(
            "SELECT r.id $FROM_JOINS $where ORDER BY rme.deleted_at DESC, r.public_id ASC LIMIT :limit OFFSET :offset",
        )
            .params(params)
            .param("limit", size)
            .param("offset", page * size)
            .query(UUID::class.java)
            .list()
            .filterNotNull()
            .let(::findByIdsPreservingOrder)

        return DeletedReportPage(items, total)
    }

    private fun findByIdsPreservingOrder(ids: List<UUID>): List<DeletedReportRow> {
        if (ids.isEmpty()) return emptyList()
        val byId = jdbc.sql("$SELECT_ROW WHERE r.id IN (:ids)")
            .param("ids", ids)
            .query(::mapRow)
            .list()
            .associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    /** Identical rule to `JdbcReportWorkflowQueryRepository.visibilityClause` (brief §3/§21) - deleted or not, scope is decided the same way. */
    private fun visibilityClause(actor: ReportWorkflowActor): Pair<String, Map<String, Any>>? {
        if (actor.role == UserRole.SUPER_ADMIN) return null
        if (actor.role == UserRole.MODERATOR && actor.globalAreaAccess) return null

        val ids = actor.ownActiveAreaIds.ifEmpty { setOf(NEVER_MATCHES) }
        val clause = "rs.routing_status = 'ROUTED' AND sa.status = 'ACTIVE' AND rs.service_area_id IN (:ownAreaIds)"
        return clause to mapOf("ownAreaIds" to ids)
    }

    private fun filterClause(filter: DeletedReportListFilter): Pair<String, Map<String, Any>>? {
        val clauses = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()

        filter.reason?.let { clauses += "rme.reason = :fReason"; params["fReason"] = it.name }
        filter.areaId?.let { clauses += "rs.service_area_id = :fArea"; params["fArea"] = it }
        filter.query?.takeIf { it.isNotBlank() }?.let {
            clauses += "(r.train_identifier ILIKE :fQuery OR s.name ILIKE :fQuery OR r.public_id::text ILIKE :fQuery)"
            params["fQuery"] = "%${escapeLike(it)}%"
        }

        if (clauses.isEmpty()) return null
        return clauses.joinToString(" AND ") to params
    }

    private fun whereClause(vararg optional: Pair<String, Map<String, Any>>?): Pair<String, Map<String, Any>> {
        // Every row here is, by construction of FROM_JOINS' inner join, a currently-open
        // moderation episode - no extra status predicate is needed the way the ordinary
        // queues need `r.status = ...`.
        val clauses = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        optional.filterNotNull().forEach { (clause, clauseParams) ->
            clauses += "($clause)"
            params.putAll(clauseParams)
        }
        return (if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")) to params
    }

    private fun escapeLike(raw: String): String = raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun mapRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = DeletedReportRow(
        id = rs.getObject("id", UUID::class.java),
        publicId = rs.getObject("public_id", UUID::class.java),
        occurredAt = rs.getTimestamp("occurred_at").toInstant(),
        submittedAt = rs.getTimestamp("submitted_at").toInstant(),
        trainIdentifier = rs.getString("train_identifier"),
        settlementId = rs.getObject("settlement_id", UUID::class.java),
        settlementName = rs.getString("settlement_name"),
        countyName = rs.getString("county_name"),
        categoryCode = rs.getString("category_code"),
        categoryDisplayName = rs.getString("category_display_name"),
        eventTypeCode = rs.getString("event_type_code"),
        eventTypeDisplayName = rs.getString("event_type_display_name"),
        routingStatus = RoutingSnapshotStatus.valueOf(rs.getString("routing_status")),
        routingReason = rs.getString("routing_reason")?.let(UnclassifiedReason::valueOf),
        resolvedRailwayLineId = rs.getObject("resolved_railway_line_id", UUID::class.java),
        resolvedRailwayLineDisplayName = rs.getString("resolved_railway_line_display_name"),
        serviceAreaId = rs.getObject("service_area_id", UUID::class.java),
        serviceAreaName = rs.getString("service_area_name"),
        serviceAreaStatus = rs.getString("service_area_status")?.let(ServiceAreaStatus::valueOf),
        workflowVersion = rs.getLong("workflow_version"),
        reason = ModerationReason.valueOf(rs.getString("reason")),
        deletedByServiceId = rs.getString("deleted_by_service_id"),
        deletedAt = rs.getTimestamp("deleted_at").toInstant(),
        statusBeforeDelete = ReportStatus.valueOf(rs.getString("status_before_delete")),
    )

    private companion object {
        val NEVER_MATCHES: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")

        const val FROM_JOINS = """
              FROM reports r
              JOIN report_routing_snapshots rs ON rs.report_id = r.id
              JOIN report_moderation_episodes rme ON rme.report_id = r.id AND rme.restored_at IS NULL
              JOIN users du ON du.id = rme.deleted_by_user_id
              JOIN settlements s ON s.id = r.settlement_id
              JOIN report_event_types et ON et.code = r.event_type_code
              JOIN report_categories cat ON cat.code = et.category_code
              LEFT JOIN service_areas sa ON sa.id = rs.service_area_id
              LEFT JOIN railway_lines rl ON rl.id = rs.resolved_railway_line_id
        """

        val SELECT_ROW = """
            SELECT r.id, r.public_id, r.occurred_at, r.submitted_at, r.train_identifier,
                   r.settlement_id, s.name AS settlement_name, s.county_name,
                   et.category_code AS category_code, cat.display_name AS category_display_name,
                   r.event_type_code, et.display_name AS event_type_display_name,
                   rs.routing_status, rs.routing_reason,
                   rs.resolved_railway_line_id, rl.display_name AS resolved_railway_line_display_name,
                   rs.service_area_id, sa.name AS service_area_name, sa.status AS service_area_status,
                   r.workflow_version,
                   rme.reason, du.service_id AS deleted_by_service_id, rme.deleted_at, rme.status_before_delete
            $FROM_JOINS
        """
    }
}
