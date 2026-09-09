package hu.orszembejelento.backend.reportworkflow.api

import hu.orszembejelento.backend.reportworkflow.application.ReportDetailResult
import hu.orszembejelento.backend.reportworkflow.application.ReportQueueItem
import hu.orszembejelento.backend.reportworkflow.application.ReportQueuePageResult
import hu.orszembejelento.backend.reportworkflow.application.ResolvedAssignmentEpisode
import hu.orszembejelento.backend.reportworkflow.infrastructure.ReportWorkflowRow
import jakarta.validation.constraints.NotNull
import java.time.Instant

data class ReportSettlementSummary(val id: String, val name: String, val countyName: String?)
data class ReportCategorySummary(val code: String, val displayName: String)
data class ReportEventTypeSummary(val code: String, val displayName: String)
data class ReportAreaSummary(val id: String, val name: String)
data class ReportAssigneeSummary(val serviceId: String)

/**
 * One report as it appears in a list (brief §20/§22/§23). [assignee] is always null for the
 * NEW queue, always non-null for IN_PROGRESS, and reflects history for Archive (null unless
 * the report happened to be archived while still assigned to someone, which never happens
 * in Phase 7 - close always clears the assignee, see `CloseReportUseCase`). [ageBucket] is
 * only ever set for the NEW queue.
 */
data class ReportListItemResponse(
    val publicReportId: String,
    val occurredAt: Instant,
    val submittedAt: Instant,
    val trainIdentifier: String?,
    val settlement: ReportSettlementSummary,
    val category: ReportCategorySummary,
    val eventType: ReportEventTypeSummary,
    val routingClassification: String,
    val serviceArea: ReportAreaSummary?,
    val assignee: ReportAssigneeSummary?,
    val workflowVersion: Long,
    val ageBucket: String?,
    val archivedAt: Instant?,
) {
    companion object {
        fun from(item: ReportQueueItem) = item.row.let { row ->
            ReportListItemResponse(
                publicReportId = row.publicId.toString(),
                occurredAt = row.occurredAt,
                submittedAt = row.submittedAt,
                trainIdentifier = row.trainIdentifier,
                settlement = ReportSettlementSummary(row.settlementId.toString(), row.settlementName, row.countyName),
                category = ReportCategorySummary(row.categoryCode, row.categoryDisplayName),
                eventType = ReportEventTypeSummary(row.eventTypeCode, row.eventTypeDisplayName),
                routingClassification = row.routingStatus.name,
                serviceArea = row.serviceAreaId?.let { ReportAreaSummary(it.toString(), row.serviceAreaName ?: "") },
                assignee = row.assigneeServiceId?.let { ReportAssigneeSummary(it) },
                workflowVersion = row.workflowVersion,
                ageBucket = item.ageBucket?.name,
                archivedAt = row.archivedAt,
            )
        }
    }
}

data class ReportQueuePageResponse(
    val items: List<ReportListItemResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    companion object {
        fun from(result: ReportQueuePageResult) = ReportQueuePageResponse(
            items = result.items.map(ReportListItemResponse::from),
            page = result.page,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
        )
    }
}

data class ReportRailwayLineSummary(val id: String, val displayName: String)

data class AssignmentHistoryItemResponse(
    val assigneeServiceId: String,
    val assignedByServiceId: String,
    val assignedAt: Instant,
    val endedAt: Instant?,
    val endedByServiceId: String?,
    val endReason: String?,
) {
    companion object {
        fun from(episode: ResolvedAssignmentEpisode) = AssignmentHistoryItemResponse(
            assigneeServiceId = episode.assigneeServiceId,
            assignedByServiceId = episode.assignedByServiceId,
            assignedAt = episode.assignedAt,
            endedAt = episode.endedAt,
            endedByServiceId = episode.endedByServiceId,
            endReason = episode.endReason?.name,
        )
    }
}

/**
 * The full report-workflow detail (brief §24). Deliberately excludes the Public access
 * credential/hash, Service auth internals and audit-event implementation metadata — none of
 * which any field here is derived from.
 */
data class ReportDetailResponse(
    val publicReportId: String,
    val occurredAt: Instant,
    val submittedAt: Instant,
    val trainIdentifier: String?,
    val settlement: ReportSettlementSummary,
    val category: ReportCategorySummary,
    val eventType: ReportEventTypeSummary,
    val status: String,
    val workflowVersion: Long,
    val routingClassification: String,
    val routingReason: String?,
    val resolvedRailwayLine: ReportRailwayLineSummary?,
    val serviceArea: ReportAreaSummary?,
    val assignee: ReportAssigneeSummary?,
    val archivedAt: Instant?,
    val assignmentHistory: List<AssignmentHistoryItemResponse>,
) {
    companion object {
        fun from(result: ReportDetailResult): ReportDetailResponse {
            val row: ReportWorkflowRow = result.row
            return ReportDetailResponse(
                publicReportId = row.publicId.toString(),
                occurredAt = row.occurredAt,
                submittedAt = row.submittedAt,
                trainIdentifier = row.trainIdentifier,
                settlement = ReportSettlementSummary(row.settlementId.toString(), row.settlementName, row.countyName),
                category = ReportCategorySummary(row.categoryCode, row.categoryDisplayName),
                eventType = ReportEventTypeSummary(row.eventTypeCode, row.eventTypeDisplayName),
                status = row.status.name,
                workflowVersion = row.workflowVersion,
                routingClassification = row.routingStatus.name,
                routingReason = row.routingReason?.name,
                resolvedRailwayLine = row.resolvedRailwayLineId?.let {
                    ReportRailwayLineSummary(it.toString(), row.resolvedRailwayLineDisplayName ?: "")
                },
                serviceArea = row.serviceAreaId?.let { ReportAreaSummary(it.toString(), row.serviceAreaName ?: "") },
                assignee = row.assigneeServiceId?.let { ReportAssigneeSummary(it) },
                archivedAt = row.archivedAt,
                assignmentHistory = result.history.map(AssignmentHistoryItemResponse::from),
            )
        }
    }
}

/** Every workflow mutation body carries the optimistic-concurrency version it read (brief §7/§30/§33/§35/§38). */
data class WorkflowMutationRequest(@field:NotNull val expectedVersion: Long? = null)

data class ReassignRequest(
    @field:NotNull val expectedVersion: Long? = null,
    val targetServiceId: String? = null,
)
