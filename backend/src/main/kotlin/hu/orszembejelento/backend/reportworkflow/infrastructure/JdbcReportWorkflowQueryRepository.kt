package hu.orszembejelento.backend.reportworkflow.infrastructure

import hu.orszembejelento.backend.identity.domain.UserRole
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

/** Optional server-side filters common to every queue (brief §27/§28). Never bypasses scope (brief §29). */
data class ReportListFilter(
    val query: String? = null,
    val categoryCode: String? = null,
    val eventTypeCode: String? = null,
    val settlementId: UUID? = null,
    val areaId: UUID? = null,
    val assigneeServiceId: String? = null,
)

/**
 * One report, fully resolved — the single row shape both the list queues and the detail
 * endpoint read from, joined against the *unfiltered* reference/taxonomy lookups (brief
 * §51: a historical report must keep resolving even once its settlement/event type/line
 * has since been deactivated, or the current reference state is unavailable — none of that
 * is consulted here at all).
 */
data class ReportWorkflowRow(
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
    val status: ReportStatus,
    val routingStatus: RoutingSnapshotStatus,
    val routingReason: UnclassifiedReason?,
    val resolvedRailwayLineId: UUID?,
    val resolvedRailwayLineDisplayName: String?,
    val serviceAreaId: UUID?,
    val serviceAreaName: String?,
    val serviceAreaStatus: ServiceAreaStatus?,
    val assignedUserId: UUID?,
    val assigneeServiceId: String?,
    val workflowVersion: Long,
    val archivedAt: Instant?,
)

/** One page of [ReportWorkflowRow]s. */
data class ReportWorkflowPage(val items: List<ReportWorkflowRow>, val totalElements: Int)

/**
 * The read model behind every Service report-workflow endpoint: `reports` joined with its
 * routing snapshot and the unfiltered settlement/taxonomy/area/assignee lookups, with
 * role-and-scope visibility expressed directly in SQL for the three list queues so
 * pagination and filtering stay correct together (brief §26/§29), exactly the same
 * reasoning [hu.orszembejelento.backend.usermanagement.infrastructure.JdbcUserManagementRepository]
 * already applies to the user-management listing.
 *
 * Detail is different: [findByPublicId] is a single, unfiltered fetch, and the caller
 * (`ReportQueryUseCase`) applies [hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowPolicy.canViewReport]
 * itself — a single row does not need SQL-level scope filtering, and reusing the one
 * policy method here keeps detail visibility from ever silently drifting away from the
 * list rules.
 */
@Repository
class JdbcReportWorkflowQueryRepository(private val jdbc: JdbcClient) {

    fun findByPublicId(publicId: UUID): ReportWorkflowRow? =
        jdbc.sql("$SELECT_ROW WHERE r.public_id = :id")
            .param("id", publicId)
            .query(::mapRow)
            .optional()
            .orElse(null)

    // ---------------------------------------------------------------------------- NEW queue

    /**
     * The NEW queue (brief §18-21): RECENT (submitted within the last 168h, newest first)
     * before OLDER (submitted earlier, oldest first), with the public id as a deterministic
     * tie-breaker. [cutoff] is `now - 168h`, computed once by the caller from the injected
     * backend [java.time.Clock] — never local/device time.
     */
    fun findNewQueue(actor: ReportWorkflowActor, filter: ReportListFilter, cutoff: Instant, page: Int, size: Int): ReportWorkflowPage {
        val (where, params) = whereClause("r.status = 'NEW'", visibilityClause(actor), filterClause(filter))
        val total = jdbc.sql("SELECT COUNT(*) $FROM_JOINS $where").params(params).query(Int::class.java).single()

        val items = jdbc.sql(
            """
            SELECT r.id $FROM_JOINS $where
             ORDER BY (r.submitted_at < :cutoff) ASC,
                      CASE WHEN r.submitted_at >= :cutoff THEN r.submitted_at END DESC,
                      CASE WHEN r.submitted_at <  :cutoff THEN r.submitted_at END ASC,
                      r.public_id ASC
             LIMIT :limit OFFSET :offset
            """.trimIndent(),
        )
            .params(params)
            .param("cutoff", java.sql.Timestamp.from(cutoff))
            .param("limit", size)
            .param("offset", page * size)
            .query(UUID::class.java)
            .list()
            .filterNotNull()
            .let(::findByIdsPreservingOrder)

        return ReportWorkflowPage(items, total)
    }

    // --------------------------------------------------------------------- IN_PROGRESS queue

    /** The IN_PROGRESS queue (brief §22): oldest submitted first, public id tie-breaker. */
    fun findInProgressQueue(actor: ReportWorkflowActor, filter: ReportListFilter, page: Int, size: Int): ReportWorkflowPage {
        val ownershipClause: Pair<String, Map<String, Any>>? = if (actor.role == UserRole.SERVICE_USER) {
            "r.assigned_user_id = :selfId" to mapOf("selfId" to actor.userId)
        } else {
            null
        }
        val (where, params) = whereClause(
            "r.status = 'IN_PROGRESS'",
            visibilityClause(actor),
            filterClause(filter),
            ownershipClause,
        )
        val total = jdbc.sql("SELECT COUNT(*) $FROM_JOINS $where").params(params).query(Int::class.java).single()

        val items = jdbc.sql(
            "SELECT r.id $FROM_JOINS $where ORDER BY r.submitted_at ASC, r.public_id ASC LIMIT :limit OFFSET :offset",
        )
            .params(params)
            .param("limit", size)
            .param("offset", page * size)
            .query(UUID::class.java)
            .list()
            .filterNotNull()
            .let(::findByIdsPreservingOrder)

        return ReportWorkflowPage(items, total)
    }

    // -------------------------------------------------------------------------- ARCHIVE queue

    /** The Archive queue (brief §23): most recently archived first, public id tie-breaker. */
    fun findArchiveQueue(actor: ReportWorkflowActor, filter: ReportListFilter, page: Int, size: Int): ReportWorkflowPage {
        val (where, params) = whereClause("r.status = 'ARCHIVED'", visibilityClause(actor), filterClause(filter))
        val total = jdbc.sql("SELECT COUNT(*) $FROM_JOINS $where").params(params).query(Int::class.java).single()

        val items = jdbc.sql(
            "SELECT r.id $FROM_JOINS $where ORDER BY r.archived_at DESC, r.public_id ASC LIMIT :limit OFFSET :offset",
        )
            .params(params)
            .param("limit", size)
            .param("offset", page * size)
            .query(UUID::class.java)
            .list()
            .filterNotNull()
            .let(::findByIdsPreservingOrder)

        return ReportWorkflowPage(items, total)
    }

    // ------------------------------------------------------------------------------- private

    private fun findByIdsPreservingOrder(ids: List<UUID>): List<ReportWorkflowRow> {
        if (ids.isEmpty()) return emptyList()
        val byId = jdbc.sql("$SELECT_ROW WHERE r.id IN (:ids)")
            .param("ids", ids)
            .query(::mapRow)
            .list()
            .associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    /**
     * Role-and-scope visibility (brief §13-15), expressed for a query already fixed to one
     * status by its caller — SUPER_ADMIN and a global MODERATOR (`users.global_area_access`)
     * see everything, UNCLASSIFIED included; a global SERVICE_USER sees every routed report
     * in any currently ACTIVE normal area (mirrors [hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowPolicy.canViewReport]'s
     * "global SERVICE_USER" rule, brief §14) but, like every SERVICE_USER, never UNCLASSIFIED;
     * a territorial MODERATOR or a non-global SERVICE_USER only their own current active
     * areas, never UNCLASSIFIED either. (Ownership, for IN_PROGRESS, is applied separately by
     * the caller — see [findInProgressQueue].)
     */
    private fun visibilityClause(actor: ReportWorkflowActor): Pair<String, Map<String, Any>>? {
        if (actor.role == UserRole.SUPER_ADMIN) return null
        if (actor.role == UserRole.MODERATOR && actor.globalAreaAccess) return null

        if (actor.role == UserRole.SERVICE_USER && actor.globalAreaAccess) {
            // Any active normal area, no ownership restriction - but still never UNCLASSIFIED.
            return "rs.routing_status = 'ROUTED' AND sa.status = 'ACTIVE'" to emptyMap()
        }

        // A territorial MODERATOR and a non-global SERVICE_USER: routed, area ACTIVE, area in
        // own scope. Never a genuinely empty IN-list without a sentinel - see
        // JdbcUserManagementRepository's identical note for why.
        val ids = actor.ownActiveAreaIds.ifEmpty { setOf(NEVER_MATCHES) }
        val clause = "rs.routing_status = 'ROUTED' AND sa.status = 'ACTIVE' AND rs.service_area_id IN (:ownAreaIds)"
        return clause to mapOf("ownAreaIds" to ids)
    }

    private fun filterClause(filter: ReportListFilter): Pair<String, Map<String, Any>>? {
        val clauses = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()

        filter.categoryCode?.let { clauses += "r.event_type_code IN (SELECT code FROM report_event_types WHERE category_code = :fCategory)"; params["fCategory"] = it }
        filter.eventTypeCode?.let { clauses += "r.event_type_code = :fEventType"; params["fEventType"] = it }
        filter.settlementId?.let { clauses += "r.settlement_id = :fSettlement"; params["fSettlement"] = it }
        filter.areaId?.let { clauses += "rs.service_area_id = :fArea"; params["fArea"] = it }
        filter.assigneeServiceId?.let { clauses += "au.service_id = :fAssignee"; params["fAssignee"] = it }
        filter.query?.takeIf { it.isNotBlank() }?.let {
            clauses += "(r.train_identifier ILIKE :fQuery OR s.name ILIKE :fQuery OR r.public_id::text ILIKE :fQuery)"
            params["fQuery"] = "%${escapeLike(it)}%"
        }

        if (clauses.isEmpty()) return null
        return clauses.joinToString(" AND ") to params
    }

    /** Combines a mandatory status predicate with any number of optional (clause, params) pairs. */
    private fun whereClause(
        statusPredicate: String,
        vararg optional: Pair<String, Map<String, Any>>?,
    ): Pair<String, Map<String, Any>> {
        val clauses = mutableListOf(statusPredicate)
        val params = mutableMapOf<String, Any>()
        optional.filterNotNull().forEach { (clause, clauseParams) ->
            clauses += "($clause)"
            params.putAll(clauseParams)
        }
        return ("WHERE " + clauses.joinToString(" AND ")) to params
    }

    private fun escapeLike(raw: String): String = raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun mapRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = ReportWorkflowRow(
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
        status = ReportStatus.valueOf(rs.getString("status")),
        routingStatus = RoutingSnapshotStatus.valueOf(rs.getString("routing_status")),
        routingReason = rs.getString("routing_reason")?.let(UnclassifiedReason::valueOf),
        resolvedRailwayLineId = rs.getObject("resolved_railway_line_id", UUID::class.java),
        resolvedRailwayLineDisplayName = rs.getString("resolved_railway_line_display_name"),
        serviceAreaId = rs.getObject("service_area_id", UUID::class.java),
        serviceAreaName = rs.getString("service_area_name"),
        serviceAreaStatus = rs.getString("service_area_status")?.let(ServiceAreaStatus::valueOf),
        assignedUserId = rs.getObject("assigned_user_id", UUID::class.java),
        assigneeServiceId = rs.getString("assignee_service_id"),
        workflowVersion = rs.getLong("workflow_version"),
        archivedAt = rs.getTimestamp("archived_at")?.toInstant(),
    )

    private companion object {
        val NEVER_MATCHES: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")

        // Every reference/taxonomy lookup is deliberately unfiltered by `active` (brief §51):
        // a historical report must keep resolving its settlement/category/event type/line
        // even once any of them has since been deactivated.
        const val FROM_JOINS = """
              FROM reports r
              JOIN report_routing_snapshots rs ON rs.report_id = r.id
              JOIN settlements s ON s.id = r.settlement_id
              JOIN report_event_types et ON et.code = r.event_type_code
              JOIN report_categories cat ON cat.code = et.category_code
              LEFT JOIN service_areas sa ON sa.id = rs.service_area_id
              LEFT JOIN railway_lines rl ON rl.id = rs.resolved_railway_line_id
              LEFT JOIN users au ON au.id = r.assigned_user_id
        """

        val SELECT_ROW = """
            SELECT r.id, r.public_id, r.occurred_at, r.submitted_at, r.train_identifier,
                   r.settlement_id, s.name AS settlement_name, s.county_name,
                   et.category_code AS category_code, cat.display_name AS category_display_name,
                   r.event_type_code, et.display_name AS event_type_display_name,
                   r.status,
                   rs.routing_status, rs.routing_reason,
                   rs.resolved_railway_line_id, rl.display_name AS resolved_railway_line_display_name,
                   rs.service_area_id, sa.name AS service_area_name, sa.status AS service_area_status,
                   r.assigned_user_id, au.service_id AS assignee_service_id,
                   r.workflow_version, r.archived_at
            $FROM_JOINS
        """
    }
}
