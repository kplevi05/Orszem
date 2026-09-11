package hu.orszembejelento.backend.reportworkflow.application

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.identity.domain.ServiceId
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository
import hu.orszembejelento.backend.moderation.infrastructure.JdbcModerationRepository
import hu.orszembejelento.backend.reports.domain.Report
import hu.orszembejelento.backend.reports.domain.ReportStatus
import hu.orszembejelento.backend.reports.infrastructure.JdbcReportRepository
import hu.orszembejelento.backend.reportworkflow.domain.AssignmentEndReason
import hu.orszembejelento.backend.reportworkflow.domain.InvalidAssigneeException
import hu.orszembejelento.backend.reportworkflow.domain.ReassignTargetCandidate
import hu.orszembejelento.backend.reportworkflow.domain.ReportAlreadyArchivedException
import hu.orszembejelento.backend.reportworkflow.domain.ReportNotVisibleException
import hu.orszembejelento.backend.reportworkflow.domain.ReportStateChangedException
import hu.orszembejelento.backend.reportworkflow.domain.ReportUnclassifiedCannotAssignException
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowActor
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowForbiddenException
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowPolicy
import hu.orszembejelento.backend.reportworkflow.infrastructure.JdbcReportAssignmentRepository
import hu.orszembejelento.backend.scope.infrastructure.JdbcServiceAreaRepository
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Reassigns an IN_PROGRESS report to a different SERVICE_USER (brief §38-44) —
 * MODERATOR/SUPER_ADMIN only. Canonical lock order (brief §9): the report is locked first,
 * *then* the target user (brief §43: re-read role/status/global flag/current area
 * assignments inside this transaction — never trusted from a prior Phase 6 read) — this
 * ordering is exactly what makes the target-scope race (brief §44/§64) resolve safely: a
 * concurrent Phase 6 area revoke of the target also locks the same target user row, so
 * whichever transaction gets there first is authoritative for the other.
 */
@Service
class ReassignReportUseCase(
    private val reports: JdbcReportRepository,
    private val users: JdbcUserRepository,
    private val serviceAreas: JdbcServiceAreaRepository,
    private val assignments: JdbcReportAssignmentRepository,
    private val scopeResolver: ReportScopeResolver,
    private val policy: ReportWorkflowPolicy,
    private val audit: JdbcAuditRepository,
    private val moderation: JdbcModerationRepository,
    private val clock: Clock,
) {

    @Transactional
    fun reassign(actor: ReportWorkflowActor, publicReportId: UUID, expectedVersion: Long, rawTargetServiceId: String?): Report {
        if (actor.role == UserRole.SERVICE_USER) throw ReportWorkflowForbiddenException()

        val (locked, scope) = lockAndResolve(reports, scopeResolver, moderation::hasOpenEpisode, publicReportId)
        if (!policy.canReassign(actor, scope)) throw ReportNotVisibleException()

        // Checked before status, deliberately (brief §40): a global MODERATOR/SUPER_ADMIN
        // can see an UNCLASSIFIED report and must get this specific conflict, not the more
        // generic state-changed one a NEW/ARCHIVED status would otherwise produce.
        if (!scope.routed) throw ReportUnclassifiedCannotAssignException()

        when (locked.status) {
            ReportStatus.ARCHIVED -> throw ReportAlreadyArchivedException()
            ReportStatus.NEW -> throw ReportStateChangedException()
            ReportStatus.IN_PROGRESS -> {}
        }

        // Canonical lock order, step 2.
        val targetServiceId = ServiceId.parseOrNull(rawTargetServiceId) ?: throw InvalidAssigneeException()
        val targetUser = users.lockByServiceId(targetServiceId) ?: throw InvalidAssigneeException()
        val targetAreaActor = serviceAreas.loadAreaActor(targetUser.id) ?: throw InvalidAssigneeException()
        val targetCandidate = ReassignTargetCandidate(
            userId = targetUser.id,
            serviceId = targetUser.serviceId,
            role = targetAreaActor.role,
            status = targetUser.status,
            globalAreaAccess = targetAreaActor.globalAreaAccess,
            ownActiveAreaIds = serviceAreas.activeAssignedAreaIds(targetUser.id),
        )
        if (!policy.canAssignTarget(targetCandidate, scope)) throw InvalidAssigneeException()

        if (locked.workflowVersion != expectedVersion) throw ReportStateChangedException()

        // Idempotent no-op (brief §42): already the current assignee. Checked only after
        // the target has been fully re-validated above, so a same-user "reassignment" of a
        // target who has since lost eligibility still fails rather than silently no-opping.
        if (locked.assignedUserId == targetUser.id) return locked

        val now = clock.instant()
        val newVersion = locked.workflowVersion + 1
        val previousAssigneeId = requireNotNull(locked.assignedUserId)

        reports.updateWorkflowState(locked.id, ReportStatus.IN_PROGRESS, targetUser.id, null, newVersion)
        assignments.endOpenAssignment(locked.id, now, actor.userId, AssignmentEndReason.REASSIGNED)
        assignments.openAssignment(locked.id, assigneeUserId = targetUser.id, assignedByUserId = actor.userId, assignedAt = now)

        val previousAssigneeServiceId = users.findById(previousAssigneeId)?.serviceId?.value

        audit.record(
            operationId = UUID.randomUUID(),
            actorType = AuditActorType.USER,
            actorUserId = actor.userId,
            eventType = AuditEventType.REPORT_REASSIGNED,
            targetType = AuditTargetType.REPORT,
            targetId = locked.id,
            metadata = buildMap {
                put("publicReportId", locked.publicId.toString())
                put("fromStatus", ReportStatus.IN_PROGRESS.name)
                put("toStatus", ReportStatus.IN_PROGRESS.name)
                previousAssigneeServiceId?.let { put("previousAssigneeServiceId", it) }
                put("newAssigneeServiceId", targetUser.serviceId.value)
                put("workflowVersion", newVersion.toString())
                scope.serviceAreaId?.let { put("serviceAreaId", it.toString()) }
            },
        )

        return locked.copy(assignedUserId = targetUser.id, workflowVersion = newVersion)
    }
}
