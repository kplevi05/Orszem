package hu.orszem.servicecase.application

import hu.orszem.audit.AuditAction
import hu.orszem.audit.AuditPort
import hu.orszem.reporting.domain.ReportStatus
import hu.orszem.servicecase.domain.Page
import hu.orszem.servicecase.domain.ServiceReportDetailView
import hu.orszem.servicecase.domain.ServiceReportListItem
import hu.orszem.servicecase.domain.ServiceReportRepository
import hu.orszem.servicecase.domain.TransitionOutcome
import hu.orszem.shared.error.ReportNotAcceptableException
import hu.orszem.shared.error.ReportNotArchivableException
import hu.orszem.shared.error.ReportNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

const val DEFAULT_PAGE_LIMIT = 30
const val MAX_PAGE_LIMIT = 100

private fun clampLimit(limit: Int?): Int = (limit ?: DEFAULT_PAGE_LIMIT).coerceIn(1, MAX_PAGE_LIMIT)

@Service
class ListActiveReportsUseCase(private val repository: ServiceReportRepository) {
    @Transactional(readOnly = true)
    fun execute(statuses: Set<ReportStatus>, cursor: String?, limit: Int?): Page<ServiceReportListItem> {
        val effective = statuses.ifEmpty { setOf(ReportStatus.NEW, ReportStatus.IN_PROGRESS) }
        require(effective.all { it == ReportStatus.NEW || it == ReportStatus.IN_PROGRESS })
        return repository.findActive(effective, cursor, clampLimit(limit))
    }
}

@Service
class ListArchivedReportsUseCase(private val repository: ServiceReportRepository) {
    @Transactional(readOnly = true)
    fun execute(cursor: String?, limit: Int?): Page<ServiceReportListItem> =
        repository.findArchived(cursor, clampLimit(limit))
}

@Service
class GetReportDetailUseCase(private val repository: ServiceReportRepository) {
    @Transactional(readOnly = true)
    fun execute(id: UUID): ServiceReportDetailView =
        repository.findDetail(id) ?: throw ReportNotFoundException()
}

/**
 * Demo v1.1: permanent deletion for pilot/test-data cleanup.
 *
 * Allowed from NEW, IN_PROGRESS and ARCHIVED alike — the point of the feature is
 * removing test rows, not modelling a workflow step. Analytics read the `reports`
 * table directly, so every count follows automatically with no "deleted" bucket.
 *
 * The use case is only reachable through a controller that exists solely in the
 * demo configuration; it additionally requires an authenticated actor.
 */
@Service
class DeleteReportUseCase(
    private val repository: ServiceReportRepository,
    private val audit: AuditPort,
) {
    @Transactional
    fun execute(id: UUID, actorUserId: UUID) {
        val previousStatus = repository.deletePermanently(id) ?: throw ReportNotFoundException()
        audit.record(
            action = AuditAction.REPORT_DELETED,
            targetType = "REPORT",
            // Null on purpose: the row is gone, so a target_id would dangle.
            targetId = null,
            actorUserId = actorUserId,
            metadata = mapOf("reportId" to id.toString(), "previousStatus" to previousStatus.name),
        )
    }
}

@Service
class AcceptReportUseCase(
    private val repository: ServiceReportRepository,
    private val audit: AuditPort,
    private val clock: Clock,
) {
    @Transactional
    fun execute(id: UUID, actorUserId: UUID): ServiceReportDetailView {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        when (repository.accept(id, actorUserId, now)) {
            TransitionOutcome.OK -> Unit
            TransitionOutcome.NOT_FOUND -> throw ReportNotFoundException()
            TransitionOutcome.WRONG_STATE -> throw ReportNotAcceptableException()
        }
        audit.record(AuditAction.REPORT_ACCEPTED, "REPORT", id, actorUserId)
        return repository.findDetail(id) ?: throw ReportNotFoundException()
    }
}

@Service
class ArchiveReportUseCase(
    private val repository: ServiceReportRepository,
    private val audit: AuditPort,
    private val clock: Clock,
) {
    @Transactional
    fun execute(id: UUID, actorUserId: UUID): ServiceReportDetailView {
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        when (repository.archive(id, actorUserId, now)) {
            TransitionOutcome.OK -> Unit
            TransitionOutcome.NOT_FOUND -> throw ReportNotFoundException()
            TransitionOutcome.WRONG_STATE -> throw ReportNotArchivableException()
        }
        audit.record(AuditAction.REPORT_ARCHIVED, "REPORT", id, actorUserId)
        return repository.findDetail(id) ?: throw ReportNotFoundException()
    }
}
