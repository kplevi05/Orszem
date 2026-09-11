package hu.orszembejelento.backend.moderation.application

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository
import hu.orszembejelento.backend.moderation.domain.ModerationForbiddenException
import hu.orszembejelento.backend.moderation.domain.ModerationPolicy
import hu.orszembejelento.backend.moderation.domain.ModerationReason
import hu.orszembejelento.backend.moderation.domain.ReportAlreadyDeletedException
import hu.orszembejelento.backend.moderation.infrastructure.JdbcModerationRepository
import hu.orszembejelento.backend.reports.domain.Report
import hu.orszembejelento.backend.reports.domain.ReportStatus
import hu.orszembejelento.backend.reports.infrastructure.JdbcReportRepository
import hu.orszembejelento.backend.reportworkflow.application.ReportScopeResolver
import hu.orszembejelento.backend.reportworkflow.application.lockAndResolve
import hu.orszembejelento.backend.reportworkflow.domain.AssignmentEndReason
import hu.orszembejelento.backend.reportworkflow.domain.ReportNotVisibleException
import hu.orszembejelento.backend.reportworkflow.domain.ReportStateChangedException
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowActor
import hu.orszembejelento.backend.reportworkflow.infrastructure.JdbcReportAssignmentRepository
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Moderation soft-deletion (Phase 9 brief §1/§8/§11). Canonical lock order, unchanged from
 * Phase 7: the report is locked first ([lockAndResolve], step 1) and that is the **only**
 * lock this use case ever takes — never the assignee's own `users` row, exactly like
 * [hu.orszembejelento.backend.reportworkflow.application.CloseReportUseCase] (brief §11:
 * "deleting an assigned report does NOT require locking the assignee USER merely to close
 * the assignment episode"). This introduces no new lock edge at all: moderation never
 * contends with the Phase 6 mutations that lock `users` first (deactivation, role/scope
 * changes), so no new deadlock is possible between them (see `docs/PHASE_9_ENGINEERING_REPORT.md` §E).
 *
 * Every code path — NEW, IN_PROGRESS, ARCHIVED — always increments `workflow_version`
 * exactly once (brief §10), so a concurrent stale ordinary mutation reliably sees either a
 * version mismatch or (once [lockAndResolve] rejects a currently-deleted report, brief §12)
 * the report as not-found — never a silently-applied conflicting change (brief §28-30).
 */
@Service
class ModerationDeleteUseCase(
    private val reports: JdbcReportRepository,
    private val users: JdbcUserRepository,
    private val assignments: JdbcReportAssignmentRepository,
    private val moderation: JdbcModerationRepository,
    private val scopeResolver: ReportScopeResolver,
    private val policy: ModerationPolicy,
    private val audit: JdbcAuditRepository,
    private val clock: Clock,
) {

    @Transactional
    fun delete(actor: ReportWorkflowActor, publicReportId: UUID, expectedVersion: Long, reason: ModerationReason): Report {
        // Role check first, independent of any specific report (brief §2) - mirrors
        // ReassignReportUseCase's identical top-of-method SERVICE_USER gate: a 403, not a
        // report-shaped 404, since this has nothing to do with any one report's visibility.
        requireModerationActor(actor)

        // Deliberately `{ false }`: unlike every ordinary workflow mutation, a delete must
        // still be able to *see* a report that turns out to already be deleted, so it can
        // answer the specific REPORT_ALREADY_DELETED conflict (brief §22) rather than the
        // generic scope-hiding 404 the blanket check below would otherwise produce.
        val (locked, scope) = lockAndResolve(reports, scopeResolver, { false }, publicReportId)
        if (!policy.canModerate(actor, scope)) throw ReportNotVisibleException()

        // Real conflict, not a silent no-op (brief §22) - also what protects the two
        // concurrent same-report deletes race (brief §31): the report row lock above
        // serializes them, so the loser, unblocked after the winner commits, re-reads its
        // own fresh snapshot and finds the winner's episode here.
        if (moderation.hasOpenEpisode(locked.id)) throw ReportAlreadyDeletedException()

        if (locked.workflowVersion != expectedVersion) throw ReportStateChangedException()

        val now = clock.instant()
        val newVersion = locked.workflowVersion + 1
        val statusBeforeDelete = locked.status

        // Only an IN_PROGRESS report needs any reports-table write at all beyond the version
        // bump (brief §8): NEW and ARCHIVED are already exactly the state a delete leaves
        // them in. This is also why restore (ModerationRestoreUseCase) never needs to touch
        // `reports.status`/`assigned_user_id`/`archived_at` for any of the three cases.
        var resultStatus = locked.status
        var resultAssignedUserId = locked.assignedUserId
        if (statusBeforeDelete == ReportStatus.IN_PROGRESS) {
            assignments.endOpenAssignment(locked.id, now, actor.userId, AssignmentEndReason.MODERATION_DELETED)
            resultStatus = ReportStatus.NEW
            resultAssignedUserId = null
        }
        reports.updateWorkflowState(locked.id, resultStatus, resultAssignedUserId, locked.archivedAt, newVersion)
        moderation.openEpisode(locked.id, reason, actor.userId, now, statusBeforeDelete)

        val previousAssigneeServiceId = locked.assignedUserId?.let { users.findById(it)?.serviceId?.value }

        audit.record(
            operationId = UUID.randomUUID(),
            actorType = AuditActorType.USER,
            actorUserId = actor.userId,
            eventType = AuditEventType.REPORT_MODERATION_DELETED,
            targetType = AuditTargetType.REPORT,
            targetId = locked.id,
            metadata = buildMap {
                put("publicReportId", locked.publicId.toString())
                put("reason", reason.name)
                put("statusBeforeDelete", statusBeforeDelete.name)
                put("resultingWorkflowStatus", resultStatus.name)
                previousAssigneeServiceId?.let { put("previousAssigneeServiceId", it) }
                put("workflowVersion", newVersion.toString())
                scope.serviceAreaId?.let { put("serviceAreaId", it.toString()) }
            },
        )

        return locked.copy(status = resultStatus, assignedUserId = resultAssignedUserId, workflowVersion = newVersion)
    }
}

/** The role-only pre-check every moderation use case runs before touching any specific report (brief §2). */
internal fun requireModerationActor(actor: ReportWorkflowActor) {
    if (actor.role == UserRole.SERVICE_USER) throw ModerationForbiddenException()
}
