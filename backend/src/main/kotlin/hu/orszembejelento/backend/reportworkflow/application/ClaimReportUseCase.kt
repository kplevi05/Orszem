package hu.orszembejelento.backend.reportworkflow.application

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.reports.domain.Report
import hu.orszembejelento.backend.reports.domain.ReportStatus
import hu.orszembejelento.backend.reports.infrastructure.JdbcReportRepository
import hu.orszembejelento.backend.reportworkflow.domain.ReportAlreadyArchivedException
import hu.orszembejelento.backend.reportworkflow.domain.ReportAlreadyAssignedException
import hu.orszembejelento.backend.reportworkflow.domain.ReportNotVisibleException
import hu.orszembejelento.backend.reportworkflow.domain.ReportStateChangedException
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowActor
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowForbiddenException
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowPolicy
import hu.orszembejelento.backend.reportworkflow.infrastructure.JdbcReportAssignmentRepository
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Atomic self-claim of a NEW report (brief §30-32) — SERVICE_USER only. The visibility gate
 * ([ReportWorkflowPolicy.canClaim]) runs before the status/version checks so a SERVICE_USER
 * who legitimately saw this report as NEW a moment ago gets the specific
 * [ReportAlreadyAssignedException]/[ReportAlreadyArchivedException] when they lose a race,
 * rather than a scope-hiding 404 — that 404 is reserved for genuine authorisation loss
 * (out of scope, UNCLASSIFIED, or the report never existed at all).
 */
@Service
class ClaimReportUseCase(
    private val reports: JdbcReportRepository,
    private val assignments: JdbcReportAssignmentRepository,
    private val scopeResolver: ReportScopeResolver,
    private val policy: ReportWorkflowPolicy,
    private val audit: JdbcAuditRepository,
    private val clock: Clock,
) {

    @Transactional
    fun claim(actor: ReportWorkflowActor, publicReportId: UUID, expectedVersion: Long): Report {
        if (actor.role != UserRole.SERVICE_USER) throw ReportWorkflowForbiddenException()

        val (locked, scope) = lockAndResolve(reports, scopeResolver, publicReportId)
        if (!policy.canClaim(actor, scope)) throw ReportNotVisibleException()

        when (locked.status) {
            ReportStatus.ARCHIVED -> throw ReportAlreadyArchivedException()
            ReportStatus.IN_PROGRESS -> throw ReportAlreadyAssignedException()
            ReportStatus.NEW -> {}
        }
        if (locked.workflowVersion != expectedVersion) throw ReportStateChangedException()

        val now = clock.instant()
        val newVersion = locked.workflowVersion + 1

        reports.updateWorkflowState(locked.id, ReportStatus.IN_PROGRESS, actor.userId, null, newVersion)
        assignments.openAssignment(locked.id, assigneeUserId = actor.userId, assignedByUserId = actor.userId, assignedAt = now)

        audit.record(
            operationId = UUID.randomUUID(),
            actorType = AuditActorType.USER,
            actorUserId = actor.userId,
            eventType = AuditEventType.REPORT_CLAIMED,
            targetType = AuditTargetType.REPORT,
            targetId = locked.id,
            metadata = buildMap {
                put("publicReportId", locked.publicId.toString())
                put("fromStatus", ReportStatus.NEW.name)
                put("toStatus", ReportStatus.IN_PROGRESS.name)
                put("newAssigneeServiceId", actor.serviceId.value)
                put("workflowVersion", newVersion.toString())
                scope.serviceAreaId?.let { put("serviceAreaId", it.toString()) }
            },
        )

        return locked.copy(status = ReportStatus.IN_PROGRESS, assignedUserId = actor.userId, workflowVersion = newVersion)
    }
}
