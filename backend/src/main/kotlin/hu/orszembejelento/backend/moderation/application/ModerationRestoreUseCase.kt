package hu.orszembejelento.backend.moderation.application

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.moderation.domain.ModerationForbiddenException
import hu.orszembejelento.backend.moderation.domain.ModerationPolicy
import hu.orszembejelento.backend.moderation.domain.ReportNotDeletedException
import hu.orszembejelento.backend.moderation.infrastructure.JdbcModerationRepository
import hu.orszembejelento.backend.reports.domain.Report
import hu.orszembejelento.backend.reports.infrastructure.JdbcReportRepository
import hu.orszembejelento.backend.reportworkflow.application.ReportScopeResolver
import hu.orszembejelento.backend.reportworkflow.application.lockAndResolve
import hu.orszembejelento.backend.reportworkflow.domain.ReportNotVisibleException
import hu.orszembejelento.backend.reportworkflow.domain.ReportStateChangedException
import hu.orszembejelento.backend.reportworkflow.domain.ReportWorkflowActor
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Restore — SUPER_ADMIN only (Phase 9 brief §2/§9, FROZEN). Canonical lock order: the report
 * is locked first, exactly like [ModerationDeleteUseCase]; no `users` row is ever locked.
 *
 * `reports.status`/`assigned_user_id`/`archived_at` never need to change here for any of the
 * three [hu.orszembejelento.backend.moderation.domain.ModerationEpisode.statusBeforeDelete]
 * cases — see `V005__report_moderation.sql`'s own comment and [ModerationDeleteUseCase]: a
 * delete already leaves the report row in exactly the state its own restore target requires,
 * for NEW, IN_PROGRESS *and* ARCHIVED alike. Restoring only ever closes the open episode and
 * bumps `workflow_version` — never resurrecting the terminated prior assignment (brief §9).
 */
@Service
class ModerationRestoreUseCase(
    private val reports: JdbcReportRepository,
    private val moderation: JdbcModerationRepository,
    private val scopeResolver: ReportScopeResolver,
    private val policy: ModerationPolicy,
    private val audit: JdbcAuditRepository,
    private val clock: Clock,
) {

    @Transactional
    fun restore(actor: ReportWorkflowActor, publicReportId: UUID, expectedVersion: Long): Report {
        requireModerationActor(actor)
        if (!policy.canRestore(actor)) throw ModerationForbiddenException()

        // Deliberately `{ false }` - a restore must be able to see an already-deleted
        // report; see ModerationDeleteUseCase's identical note. `lockAndResolve` itself
        // still throws the existence-safe 404 for a genuinely nonexistent report; no
        // further scope check is needed here — `canRestore` above already established the
        // actor is SUPER_ADMIN, who sees every report unconditionally (`ReportWorkflowPolicy.canViewReport`).
        val (locked, scope) = lockAndResolve(reports, scopeResolver, { false }, publicReportId)

        val episode = moderation.findOpenEpisode(locked.id) ?: throw ReportNotDeletedException()
        if (locked.workflowVersion != expectedVersion) throw ReportStateChangedException()

        val now = clock.instant()
        val newVersion = locked.workflowVersion + 1

        moderation.closeOpenEpisode(locked.id, now, actor.userId)
        reports.updateWorkflowState(locked.id, locked.status, locked.assignedUserId, locked.archivedAt, newVersion)

        audit.record(
            operationId = UUID.randomUUID(),
            actorType = AuditActorType.USER,
            actorUserId = actor.userId,
            eventType = AuditEventType.REPORT_MODERATION_RESTORED,
            targetType = AuditTargetType.REPORT,
            targetId = locked.id,
            metadata = buildMap {
                put("publicReportId", locked.publicId.toString())
                put("statusBeforeDelete", episode.statusBeforeDelete.name)
                put("resultingWorkflowStatus", episode.restoreTargetStatus.name)
                put("workflowVersion", newVersion.toString())
                scope.serviceAreaId?.let { put("serviceAreaId", it.toString()) }
            },
        )

        return locked.copy(workflowVersion = newVersion)
    }
}
