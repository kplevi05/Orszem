package hu.orszembejelento.backend.reportworkflow.application

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository
import hu.orszembejelento.backend.moderation.infrastructure.JdbcModerationRepository
import hu.orszembejelento.backend.reports.domain.Report
import hu.orszembejelento.backend.reports.domain.ReportStatus
import hu.orszembejelento.backend.reports.infrastructure.JdbcReportRepository
import hu.orszembejelento.backend.reportworkflow.domain.AssignmentEndReason
import hu.orszembejelento.backend.reportworkflow.domain.ReportAlreadyArchivedException
import hu.orszembejelento.backend.reportworkflow.domain.ReportNotVisibleException
import hu.orszembejelento.backend.reportworkflow.domain.ReportStateChangedException
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowActor
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowPolicy
import hu.orszembejelento.backend.reportworkflow.infrastructure.JdbcReportAssignmentRepository
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Returns an IN_PROGRESS report to NEW (brief §33-34) — a SERVICE_USER may return only
 * their own report, a MODERATOR/SUPER_ADMIN any visible one in scope. The SERVICE_USER
 * ownership rule needs no extra check here: [ReportWorkflowPolicy.canReturn] delegates to
 * `canViewReport`, whose own IN_PROGRESS rule for SERVICE_USER already requires
 * `assignedUserId == actor.userId` — a SERVICE_USER who cannot see another user's
 * IN_PROGRESS report at all can therefore never reach this method's mutation, and gets the
 * same [ReportNotVisibleException] a nonexistent report would (brief §34: never reveal
 * another user's assignment).
 */
@Service
class ReturnReportUseCase(
    private val reports: JdbcReportRepository,
    private val users: JdbcUserRepository,
    private val assignments: JdbcReportAssignmentRepository,
    private val scopeResolver: ReportScopeResolver,
    private val policy: ReportWorkflowPolicy,
    private val audit: JdbcAuditRepository,
    private val moderation: JdbcModerationRepository,
    private val clock: Clock,
) {

    @Transactional
    fun returnReport(actor: ReportWorkflowActor, publicReportId: UUID, expectedVersion: Long): Report {
        val (locked, scope) = lockAndResolve(reports, scopeResolver, moderation::hasOpenEpisode, publicReportId)
        if (!policy.canReturn(actor, scope)) throw ReportNotVisibleException()

        when (locked.status) {
            ReportStatus.ARCHIVED -> throw ReportAlreadyArchivedException()
            ReportStatus.NEW -> throw ReportStateChangedException()
            ReportStatus.IN_PROGRESS -> {}
        }
        if (locked.workflowVersion != expectedVersion) throw ReportStateChangedException()

        val now = clock.instant()
        val newVersion = locked.workflowVersion + 1
        // Guaranteed non-null: the reports.ck_reports_workflow_coherence constraint (V004)
        // requires an assignee for every IN_PROGRESS row.
        val previousAssigneeId = requireNotNull(locked.assignedUserId)

        reports.updateWorkflowState(locked.id, ReportStatus.NEW, null, null, newVersion)
        assignments.endOpenAssignment(locked.id, now, actor.userId, AssignmentEndReason.RETURNED)

        val previousAssigneeServiceId = users.findById(previousAssigneeId)?.serviceId?.value

        audit.record(
            operationId = UUID.randomUUID(),
            actorType = AuditActorType.USER,
            actorUserId = actor.userId,
            eventType = AuditEventType.REPORT_RETURNED_TO_NEW,
            targetType = AuditTargetType.REPORT,
            targetId = locked.id,
            metadata = buildMap {
                put("publicReportId", locked.publicId.toString())
                put("fromStatus", ReportStatus.IN_PROGRESS.name)
                put("toStatus", ReportStatus.NEW.name)
                previousAssigneeServiceId?.let { put("previousAssigneeServiceId", it) }
                put("workflowVersion", newVersion.toString())
                scope.serviceAreaId?.let { put("serviceAreaId", it.toString()) }
            },
        )

        return locked.copy(status = ReportStatus.NEW, assignedUserId = null, workflowVersion = newVersion)
    }
}
