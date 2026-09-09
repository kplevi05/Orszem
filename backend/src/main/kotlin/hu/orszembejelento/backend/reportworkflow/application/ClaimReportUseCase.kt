package hu.orszembejelento.backend.reportworkflow.application

import hu.orszembejelento.backend.audit.domain.AuditActorType
import hu.orszembejelento.backend.audit.domain.AuditEventType
import hu.orszembejelento.backend.audit.domain.AuditTargetType
import hu.orszembejelento.backend.audit.infrastructure.JdbcAuditRepository
import hu.orszembejelento.backend.identity.domain.UserRole
import hu.orszembejelento.backend.identity.domain.UserStatus
import hu.orszembejelento.backend.identity.infrastructure.JdbcUserRepository
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
import hu.orszembejelento.backend.scope.infrastructure.JdbcServiceAreaRepository
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
 *
 * **Cross-phase invariant (added after the initial Phase 7 review):** claim makes the
 * *actor themselves* the new current assignee, so their own role/status/area authority must
 * be re-read from a locked row inside this same report-locked transaction — never trusted
 * from the [ReportWorkflowActor] the controller built before this transaction even began.
 * `ReportWorkflowActor`'s own KDoc says "authentication already implies ACTIVE", which is
 * only true *at authentication time* — a Phase 6 role change, deactivation or area revoke
 * can still commit in the window between that read and this transaction's own report lock.
 * The fix mirrors [ReassignReportUseCase]'s already-correct target re-validation exactly:
 * canonical lock order step 2 (`users.lockByServiceId`) on the actor's own row, contending
 * for the *same* row a concurrent Phase 6 mutation locks first — so the two are always
 * serialized against each other, never merely raced. This introduces no new lock resource
 * and no lock-order conflict: it is the identical REPORT-then-USER order
 * [ReassignReportUseCase] already established, just applied to claim's own actor too.
 */
@Service
class ClaimReportUseCase(
    private val reports: JdbcReportRepository,
    private val users: JdbcUserRepository,
    private val serviceAreas: JdbcServiceAreaRepository,
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

        // Canonical lock order, step 2 (see class KDoc): re-validate the actor's own current
        // role/status/area authority from a locked row, never from the pre-transaction actor
        // object. `error(...)`, not a thrown business exception - a currently-authenticated
        // user's own row vanishing would be a genuine invariant violation (users are never
        // hard-deleted), not a normal outcome any caller can trigger.
        val selfUser = users.lockByServiceId(actor.serviceId)
            ?: error("authenticated user ${actor.userId} has no corresponding users row")
        if (selfUser.role != UserRole.SERVICE_USER || selfUser.status != UserStatus.ACTIVE) {
            // Role/status ineligibility is a forbidden-action outcome, not a visibility one -
            // mirrors the identical top-of-method gate above, just re-run with fresh data.
            throw ReportWorkflowForbiddenException()
        }
        val selfAreaActor = serviceAreas.loadAreaActor(selfUser.id)
            ?: error("authenticated user ${actor.userId} has no corresponding users row")
        val freshActor = actor.copy(
            role = selfAreaActor.role,
            globalAreaAccess = selfAreaActor.globalAreaAccess,
            ownActiveAreaIds = serviceAreas.activeAssignedAreaIds(selfUser.id),
        )
        // Area/routing eligibility loss is scope-hiding, exactly like the original gate above.
        if (!policy.canClaim(freshActor, scope)) throw ReportNotVisibleException()

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
