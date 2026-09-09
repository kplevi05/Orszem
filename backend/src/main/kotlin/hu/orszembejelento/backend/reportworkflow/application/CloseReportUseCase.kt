package hu.orszembejelento.backend.reportworkflow.application

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository
import hu.orszembejelento.backend.reports.domain.Report
import hu.orszembejelento.backend.reports.domain.ReportStatus
import hu.orszembejelento.backend.reports.infrastructure.JdbcReportRepository
import hu.orszembejelento.backend.reportworkflow.domain.AssignmentEndReason
import hu.orszembejelento.backend.reportworkflow.domain.ReportAlreadyArchivedException
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
 * Closes a report, from either NEW or IN_PROGRESS (brief §35-37). A SERVICE_USER may close
 * only their own IN_PROGRESS report — never an unclaimed NEW one, even though they can see
 * it (brief §37: this is a genuine 403, not a scope-hiding 404, since the report really is
 * visible to them). A MODERATOR/SUPER_ADMIN may close any visible NEW or IN_PROGRESS report
 * in scope, including a global actor closing an UNCLASSIFIED one directly (brief §70).
 *
 * Public status becomes CLOSED automatically through the existing, unmodified Phase 4
 * mapping the instant `status = 'ARCHIVED'` commits (brief §36/§49) — nothing here talks to
 * the Public API at all.
 */
@Service
class CloseReportUseCase(
    private val reports: JdbcReportRepository,
    private val users: JdbcUserRepository,
    private val assignments: JdbcReportAssignmentRepository,
    private val scopeResolver: ReportScopeResolver,
    private val policy: ReportWorkflowPolicy,
    private val audit: JdbcAuditRepository,
    private val clock: Clock,
) {

    @Transactional
    fun close(actor: ReportWorkflowActor, publicReportId: UUID, expectedVersion: Long): Report {
        val (locked, scope) = lockAndResolve(reports, scopeResolver, publicReportId)
        if (!policy.canClose(actor, scope)) throw ReportNotVisibleException()

        when (locked.status) {
            ReportStatus.ARCHIVED -> throw ReportAlreadyArchivedException()
            // A visible-but-unclaimed NEW report is a real, informative rejection for a
            // SERVICE_USER (§37) - they can see it, they simply may not close it directly.
            ReportStatus.NEW -> if (actor.role == UserRole.SERVICE_USER) throw ReportWorkflowForbiddenException()
            ReportStatus.IN_PROGRESS -> {}
        }
        if (locked.workflowVersion != expectedVersion) throw ReportStateChangedException()

        val now = clock.instant()
        val newVersion = locked.workflowVersion + 1
        val previousAssigneeId = locked.assignedUserId // null when closing directly from NEW

        reports.updateWorkflowState(locked.id, ReportStatus.ARCHIVED, null, now, newVersion)
        if (previousAssigneeId != null) {
            assignments.endOpenAssignment(locked.id, now, actor.userId, AssignmentEndReason.ARCHIVED)
        }

        val previousAssigneeServiceId = previousAssigneeId?.let { users.findById(it)?.serviceId?.value }

        audit.record(
            operationId = UUID.randomUUID(),
            actorType = AuditActorType.USER,
            actorUserId = actor.userId,
            eventType = AuditEventType.REPORT_ARCHIVED,
            targetType = AuditTargetType.REPORT,
            targetId = locked.id,
            metadata = buildMap {
                put("publicReportId", locked.publicId.toString())
                put("fromStatus", locked.status.name)
                put("toStatus", ReportStatus.ARCHIVED.name)
                previousAssigneeServiceId?.let { put("previousAssigneeServiceId", it) }
                put("workflowVersion", newVersion.toString())
                scope.serviceAreaId?.let { put("serviceAreaId", it.toString()) }
            },
        )

        return locked.copy(status = ReportStatus.ARCHIVED, assignedUserId = null, archivedAt = now, workflowVersion = newVersion)
    }
}
