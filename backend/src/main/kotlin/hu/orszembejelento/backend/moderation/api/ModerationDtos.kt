package hu.orszembejelento.backend.moderation.api

import hu.orszembejelento.backend.moderation.application.DeletedReportDetailResult
import hu.orszembejelento.backend.moderation.infrastructure.DeletedReportPage
import hu.orszembejelento.backend.moderation.infrastructure.DeletedReportRow
import hu.orszembejelento.backend.reports.domain.ReportStatus
import hu.orszembejelento.backend.reportworkflow.api.AssignmentHistoryItemResponse
import hu.orszembejelento.backend.reportworkflow.api.ReportAreaSummary
import hu.orszembejelento.backend.reportworkflow.api.ReportCategorySummary
import hu.orszembejelento.backend.reportworkflow.api.ReportEventTypeSummary
import hu.orszembejelento.backend.reportworkflow.api.ReportRailwayLineSummary
import hu.orszembejelento.backend.reportworkflow.api.ReportSettlementSummary
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.time.Instant

private const val REASON_PATTERN = "SPAM|TROLL_OR_FALSE_REPORT|DUPLICATE|INCORRECT|IRRELEVANT|OTHER"

/**
 * One report in the deleted list (brief §19). Deliberately no internal report UUID, no
 * user-internal UUID, no Public capability/credential, no audit metadata — only what the
 * Android moderation list actually renders.
 */
data class DeletedReportListItemResponse(
    val publicReportId: String,
    val occurredAt: Instant,
    val submittedAt: Instant,
    val trainIdentifier: String?,
    val settlement: ReportSettlementSummary,
    val category: ReportCategorySummary,
    val eventType: ReportEventTypeSummary,
    val serviceArea: ReportAreaSummary?,
    val reason: String,
    val deletedAt: Instant,
    val deletedByServiceId: String,
    val statusBeforeDelete: String,
    val restoreTargetStatus: String,
    val workflowVersion: Long,
) {
    companion object {
        fun from(row: DeletedReportRow) = DeletedReportListItemResponse(
            publicReportId = row.publicId.toString(),
            occurredAt = row.occurredAt,
            submittedAt = row.submittedAt,
            trainIdentifier = row.trainIdentifier,
            settlement = ReportSettlementSummary(row.settlementId.toString(), row.settlementName, row.countyName),
            category = ReportCategorySummary(row.categoryCode, row.categoryDisplayName),
            eventType = ReportEventTypeSummary(row.eventTypeCode, row.eventTypeDisplayName),
            serviceArea = row.serviceAreaId?.let { ReportAreaSummary(it.toString(), row.serviceAreaName ?: "") },
            reason = row.reason.name,
            deletedAt = row.deletedAt,
            deletedByServiceId = row.deletedByServiceId,
            statusBeforeDelete = row.statusBeforeDelete.name,
            // Frozen restore-target rule (brief §9): NEW and IN_PROGRESS both restore to
            // NEW, only ARCHIVED restores to ARCHIVED - mirrors `ModerationEpisode.restoreTargetStatus` exactly.
            restoreTargetStatus = (if (row.statusBeforeDelete == ReportStatus.ARCHIVED) ReportStatus.ARCHIVED else ReportStatus.NEW).name,
            workflowVersion = row.workflowVersion,
        )
    }
}

data class DeletedReportPageResponse(
    val items: List<DeletedReportListItemResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    companion object {
        fun from(result: DeletedReportPage, page: Int, size: Int): DeletedReportPageResponse {
            val totalPages = if (size <= 0) 0 else (result.totalElements + size - 1) / size
            return DeletedReportPageResponse(result.items.map(DeletedReportListItemResponse::from), page, size, result.totalElements, totalPages)
        }
    }
}

/**
 * Deleted-report detail (brief §20): full normal operational fields, assignment history, and
 * the moderation episode. Never the Public capability/credential, a password/credential hash,
 * token/session data, or raw audit rows — audit UI is Phase 12, out of this phase's scope.
 */
data class DeletedReportDetailResponse(
    val publicReportId: String,
    val occurredAt: Instant,
    val submittedAt: Instant,
    val trainIdentifier: String?,
    val settlement: ReportSettlementSummary,
    val category: ReportCategorySummary,
    val eventType: ReportEventTypeSummary,
    val resolvedRailwayLine: ReportRailwayLineSummary?,
    val serviceArea: ReportAreaSummary?,
    val workflowVersion: Long,
    val assignmentHistory: List<AssignmentHistoryItemResponse>,
    val reason: String,
    val deletedAt: Instant,
    val deletedByServiceId: String,
    val statusBeforeDelete: String,
    val restoreTargetStatus: String,
) {
    companion object {
        fun from(result: DeletedReportDetailResult): DeletedReportDetailResponse {
            val row = result.row
            return DeletedReportDetailResponse(
                publicReportId = row.publicId.toString(),
                occurredAt = row.occurredAt,
                submittedAt = row.submittedAt,
                trainIdentifier = row.trainIdentifier,
                settlement = ReportSettlementSummary(row.settlementId.toString(), row.settlementName, row.countyName),
                category = ReportCategorySummary(row.categoryCode, row.categoryDisplayName),
                eventType = ReportEventTypeSummary(row.eventTypeCode, row.eventTypeDisplayName),
                resolvedRailwayLine = row.resolvedRailwayLineId?.let {
                    ReportRailwayLineSummary(it.toString(), row.resolvedRailwayLineDisplayName ?: "")
                },
                serviceArea = row.serviceAreaId?.let { ReportAreaSummary(it.toString(), row.serviceAreaName ?: "") },
                workflowVersion = row.workflowVersion,
                assignmentHistory = result.history.map(AssignmentHistoryItemResponse::from),
                reason = row.reason.name,
                deletedAt = row.deletedAt,
                deletedByServiceId = row.deletedByServiceId,
                statusBeforeDelete = row.statusBeforeDelete.name,
                restoreTargetStatus = result.episode.restoreTargetStatus.name,
            )
        }
    }
}

/**
 * Every moderation mutation body carries the optimistic-concurrency version it read (mirrors
 * `WorkflowMutationRequest`). [reason] is validated against the exact frozen vocabulary
 * (brief §4) the same way `ChangeRoleRequest.role` validates against its own closed set —
 * an unrecognised value is a normal 400 VALIDATION_ERROR, never a stable moderation code.
 */
data class ModerationDeleteRequest(
    @field:NotNull val expectedVersion: Long? = null,
    @field:NotBlank @field:Pattern(regexp = REASON_PATTERN) val reason: String = "",
)

data class ModerationRestoreRequest(@field:NotNull val expectedVersion: Long? = null)
